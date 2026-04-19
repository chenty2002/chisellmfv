"""
JasperGold interactive runner using pexpect.

Handles the interactive verification process with JasperGold:
1. Run setup.sh which launches jg with verify.tcl
2. Wait for prove -all -bg to complete (SUMMARY output)
3. Send 'report' command to get all assertion results
4. For each CEX assertion, save VCD traces
5. Exit JasperGold
"""

import os
import re
import subprocess
from time import sleep
import pexpect
import logging
from typing import Dict, List, Optional, Tuple, Any
from dataclasses import dataclass, field


@dataclass
class AssertionResult:
    """Result of a single assertion from JasperGold."""
    name: str
    result: str  # 'proven', 'cex', 'covered', 'undetermined', etc.
    engine: str
    bound: str
    time: str
    fst_file: Optional[str] = None


@dataclass
class JasperGoldResult:
    """Complete result from JasperGold verification run."""
    success: bool
    summary: str
    assertions: List[AssertionResult] = field(default_factory=list)
    cex_assertions: List[AssertionResult] = field(default_factory=list)
    proven_assertions: List[AssertionResult] = field(default_factory=list)
    fst_files: List[str] = field(default_factory=list)
    raw_output: str = ""
    error: Optional[str] = None


class JasperGoldRunner:
    """
    Interactive JasperGold runner using pexpect.
    
    Handles the complete verification flow including:
    - Running setup.sh to launch JasperGold
    - Waiting for proof completion
    - Extracting assertion results
    - Generating VCD traces for counterexamples
    """
    
    # Timeout values (in seconds)
    PROVE_TIMEOUT = 7200  # 2 hours for prove
    COMMAND_TIMEOUT = 300  # 5 minutes for regular commands
    VCD_TIMEOUT = 120  # 2 minutes for VCD generation
    
    # Patterns for matching JasperGold output
    SUMMARY_PATTERN = r"SUMMARY\s*\n=+"
    PROMPT_PATTERN = r"\[<embedded>\] %"
    CEX_PATTERN = r"^\[\d+\]\s+(\S+)\s+cex\s+(\S+)\s+(\S+)\s+([\d.]+\s*\w*)"
    RESULT_LINE_PATTERN = r"^\[\d+\]\s+(\S+)\s+(proven|cex|covered|undetermined|unknown|error)\s+(\S+)\s+(\S+)\s+([\d.]+\s*\w*)"
    VCD_COMPLETE_PATTERN = r"Writing trace data to vcd file took Time"
    VISUALIZE_CEX_PATTERN = r"^cex\s*$"
    
    def __init__(self, verilog_dir: str, logger: logging.Logger):
        """
        Initialize JasperGold runner.
        
        Args:
            verilog_dir: Directory containing setup.sh and Verilog files
            logger: Logger instance
        """
        self.verilog_dir = verilog_dir
        self.logger = logger
        self.child: Optional["pexpect.spawn[str]"] = None
        self.raw_output = ""
    
    def run_verification(self) -> JasperGoldResult:
        """
        Run the complete JasperGold verification flow.
        
        Returns:
            JasperGoldResult with all verification results
        """
        self.logger.info(f"Starting JasperGold verification in {self.verilog_dir}")
        print(f"Starting JasperGold verification in {self.verilog_dir}")
        
        try:
            # Step 1: Launch JasperGold via setup.sh
            if not self._launch_jaspergold():
                print("Failed to launch JasperGold")
                return JasperGoldResult(
                    success=False,
                    summary="Failed to launch JasperGold",
                    error="setup.sh failed to start JasperGold",
                    raw_output=self.raw_output,
                )
            print("JasperGold launched successfully")
            
            # Step 2: Wait for prove to complete (SUMMARY appears)
            if not self._wait_for_prove_completion():
                print("Prove command did not complete")
                return JasperGoldResult(
                    success=False,
                    summary="Prove command did not complete",
                    error="Timeout waiting for prove completion",
                    raw_output=self.raw_output,
                )
            print("Prove command completed successfully")
            
            # Step 3: Send report command and parse results
            assertions = self._get_report_results()
            if not assertions:
                self.logger.warning("No assertions found in report output")
                print("No assertions found in report output")
            
            # Categorize assertions
            cex_assertions = [a for a in assertions if a.result == "cex"]
            proven_assertions = [a for a in assertions if a.result == "proven"]
            
            self.logger.info(f"Found {len(assertions)} assertions: "
                           f"{len(proven_assertions)} proven, {len(cex_assertions)} cex")
            print(f"Found {len(assertions)} assertions: {len(proven_assertions)} proven, {len(cex_assertions)} cex")
            
            # Step 4: Generate VCD traces for CEX assertions and convert to FST
            fst_files = []
            if cex_assertions:
                fst_files = self._generate_fst_traces(cex_assertions)
                for i, assertion in enumerate(cex_assertions):
                    if i < len(fst_files):
                        assertion.fst_file = fst_files[i]
            
            # Step 5: Exit JasperGold
            self._exit_jaspergold()
            print("JasperGold exited successfully")
            
            return JasperGoldResult(
                success=True,
                summary=self._build_summary(assertions, proven_assertions, cex_assertions),
                assertions=assertions,
                cex_assertions=cex_assertions,
                proven_assertions=proven_assertions,
                fst_files=fst_files,
                raw_output=self.raw_output,
            )
            
        except pexpect.ExceptionPexpect as e:
            self.logger.error(f"Pexpect error: {e}")
            return JasperGoldResult(
                success=False,
                summary="JasperGold interaction failed",
                error=str(e),
                raw_output=self.raw_output,
            )
        except Exception as e:
            self.logger.error(f"Unexpected error: {e}")
            return JasperGoldResult(
                success=False,
                summary="Unexpected error during verification",
                error=str(e),
                raw_output=self.raw_output,
            )
        finally:
            self._cleanup()
    
    def _launch_jaspergold(self) -> bool:
        """
        Launch JasperGold via setup.sh.
        
        Returns:
            True if launch successful
        """
        setup_script = os.path.join(self.verilog_dir, "setup.sh")
        if not os.path.exists(setup_script):
            self.logger.error(f"setup.sh not found in {self.verilog_dir}")
            return False
        
        jg_dir = os.path.join(self.verilog_dir, "jgproject")
        if os.path.exists(jg_dir):
            self.logger.info(f"Removing existing jgproject directory: {jg_dir}")
            try:
                import shutil
                shutil.rmtree(jg_dir)
            except Exception as e:
                self.logger.error(f"Failed to remove existing jgproject: {e}")
                return False
        
        self.logger.info(f"Launching JasperGold: bash setup.sh")
        
        # Spawn the setup.sh script
        self.child = pexpect.spawn(
            "bash",
            ["setup.sh"],
            cwd=self.verilog_dir,
            encoding="utf-8",
            timeout=self.COMMAND_TIMEOUT,
        )
        
        # Enable logging of all output
        self.child.logfile_read = LogCapture(self)
        
        return True
    
    def _ensure_child(self) -> "pexpect.spawn[str]":
        """Ensure child process is running and return it."""
        if self.child is None:
            raise RuntimeError("JasperGold process not started")
        return self.child
    
    def _wait_for_prove_completion(self) -> bool:
        """
        Wait for prove -all -bg to complete.
        
        The prove command runs in background and outputs SUMMARY when done.
        
        Returns:
            True if prove completed successfully
        """
        self.logger.info("Waiting for prove completion (this may take a while)...")
        child = self._ensure_child()
        
        try:
            # Wait for SUMMARY to appear, indicating prove completion
            index = child.expect(
                [self.SUMMARY_PATTERN, pexpect.EOF, pexpect.TIMEOUT],
                timeout=self.PROVE_TIMEOUT,
            )
            
            if index == 0:
                self.logger.info("Prove completed, SUMMARY found")
                # Wait a bit more for the full summary to be output
                sleep(5)
                return True
            elif index == 1:
                self.logger.error("JasperGold process ended unexpectedly")
                return False
            else:
                self.logger.error("Timeout waiting for prove completion")
                return False
                
        except pexpect.TIMEOUT:
            self.logger.error(f"Prove timed out after {self.PROVE_TIMEOUT} seconds")
            return False
    
    def _get_report_results(self) -> List[AssertionResult]:
        """
        Send 'report' command and parse assertion results.
        
        Returns:
            List of AssertionResult objects
        """
        self.logger.info("Sending 'report' command...")
        child = self._ensure_child()
        
        child.sendline("report")
        
        try:
            child.expect(self.PROMPT_PATTERN, timeout=self.COMMAND_TIMEOUT)
        except pexpect.TIMEOUT:
            self.logger.warning("Timeout waiting for report output")
            return []
        
        # Parse the output to extract assertion results
        output = child.before or ""
        return self._parse_report_output(output)
    
    def _parse_report_output(self, output: str) -> List[AssertionResult]:
        """
        Parse the report output to extract assertion results.
        
        Args:
            output: Raw output from report command
            
        Returns:
            List of AssertionResult objects
        """
        assertions = []
        
        # Match lines like:
        # [1]   Philo4.Adjacent_philosophers_0_and_1_cannot_eat_simultaneously   proven   N   Infinite   0.008 s
        # [9]   Philo4.Philosopher_0_can_only_eat_when_neighbors_are_not_eating   cex   N   35   0.050 s
        pattern = re.compile(
            r"^\[\d+\]\s+"           # [number]
            r"(\S+)\s+"              # assertion name
            r"(proven|cex|covered|bounded_proven|unreachable|undetermined|unknown|error)\s+"  # result
            r"(\S+)\s+"              # engine
            r"(\S+)\s+"              # bound
            r"([\d.]+\s*\w*)",       # time
            re.MULTILINE
        )
        
        for match in pattern.finditer(output):
            name, result, engine, bound, time_str = match.groups()
            assertions.append(AssertionResult(
                name=name,
                result=result,
                engine=engine,
                bound=bound,
                time=time_str.strip(),
            ))
            self.logger.debug(f"Parsed assertion: {name} -> {result}")
            print(f"Parsed assertion: {name} -> {result}")
        
        return assertions
    
    def _generate_fst_traces(self, cex_assertions: List[AssertionResult]) -> List[str]:
        """
        Generate VCD traces for all CEX assertions and convert to FST.
        
        For each CEX assertion:
        1. set_trace_optimization standard
        2. visualize -violation -property <name>
        3. visualize -save -force -vcd <name>.vcd
        4. Convert VCD to FST using vcd2fst command
        
        Args:
            cex_assertions: List of assertions with counterexamples
            
        Returns:
            List of generated FST file paths
        """
        fst_files = []
        child = self._ensure_child()
        
        # Set trace optimization once
        self.logger.info("Setting trace optimization to standard...")
        child.sendline("set_trace_optimization standard")
        try:
            child.expect(self.PROMPT_PATTERN, timeout=self.COMMAND_TIMEOUT)
        except pexpect.TIMEOUT:
            self.logger.warning("Timeout after set_trace_optimization")
        
        for assertion in cex_assertions:
            self.logger.info(f"Generating VCD for: {assertion.name}")
            
            try:
                # Step 1: Visualize the violation
                visualize_cmd = f"visualize -violation -property {assertion.name}"
                self.logger.debug(f"Sending: {visualize_cmd}")
                child.sendline(visualize_cmd)
                
                # Wait for 'cex' confirmation or prompt
                index = child.expect(
                    [self.VISUALIZE_CEX_PATTERN, self.PROMPT_PATTERN, pexpect.TIMEOUT],
                    timeout=self.VCD_TIMEOUT,
                )
                
                if index == 2:  # Timeout
                    self.logger.warning(f"Timeout visualizing {assertion.name}")
                    continue
                
                # If we got 'cex', wait for the prompt
                if index == 0:
                    try:
                        child.expect(self.PROMPT_PATTERN, timeout=self.COMMAND_TIMEOUT)
                    except pexpect.TIMEOUT:
                        pass
                
                # Step 2: Save to VCD file
                # Sanitize filename (replace special chars)
                safe_name = self._sanitize_filename(assertion.name)
                vcd_filename = f"{safe_name}.vcd"
                fst_filename = f"{safe_name}.fst"
                save_cmd = f"visualize -save -force -vcd {vcd_filename}"
                
                self.logger.debug(f"Sending: {save_cmd}")
                child.sendline(save_cmd)
                
                # Wait for VCD write completion
                index = child.expect(
                    [self.VCD_COMPLETE_PATTERN, self.PROMPT_PATTERN, pexpect.TIMEOUT],
                    timeout=self.VCD_TIMEOUT,
                )
                
                vcd_path = os.path.join(self.verilog_dir, vcd_filename)
                fst_path = os.path.join(self.verilog_dir, fst_filename)
                
                if index == 0:  # VCD write completed
                    self.logger.info(f"VCD saved: {vcd_filename}")
                    print(f"VCD saved: {vcd_filename}")
                    # Wait for prompt
                    try:
                        child.expect(self.PROMPT_PATTERN, timeout=self.COMMAND_TIMEOUT)
                    except pexpect.TIMEOUT:
                        pass
                    # Convert VCD to FST
                    fst_result = self._convert_vcd_to_fst(vcd_path, fst_path)
                    if fst_result:
                        fst_files.append(fst_path)
                elif index == 1:  # Got prompt without completion message
                    # VCD might still have been written
                    if os.path.exists(vcd_path):
                        self.logger.info(f"VCD saved: {vcd_filename}")
                        print(f"VCD saved: {vcd_filename}")
                        # Convert VCD to FST
                        fst_result = self._convert_vcd_to_fst(vcd_path, fst_path)
                        if fst_result:
                            fst_files.append(fst_path)
                else:
                    self.logger.warning(f"Timeout saving VCD for {assertion.name}")
                    
            except Exception as e:
                self.logger.error(f"Error generating VCD for {assertion.name}: {e}")
                continue
        
        return fst_files
    
    def _convert_vcd_to_fst(self, vcd_path: str, fst_path: str) -> bool:
        """
        Convert VCD file to FST format using vcd2fst command.
        
        Args:
            vcd_path: Path to the VCD file
            fst_path: Path to the output FST file
            
        Returns:
            True if conversion successful, False otherwise
        """
        if not os.path.exists(vcd_path):
            self.logger.error(f"VCD file not found: {vcd_path}")
            return False
        
        self.logger.info(f"Converting VCD to FST: {vcd_path} -> {fst_path}")
        print(f"Converting VCD to FST: {os.path.basename(vcd_path)} -> {os.path.basename(fst_path)}")
        
        try:
            result = subprocess.run(
                ["vcd2fst", vcd_path, fst_path],
                capture_output=True,
                text=True,
                timeout=self.VCD_TIMEOUT,
            )
            
            if result.returncode == 0 and os.path.exists(fst_path):
                self.logger.info(f"FST saved: {os.path.basename(fst_path)}")
                print(f"FST saved: {os.path.basename(fst_path)}")
                return True
            else:
                self.logger.error(f"vcd2fst failed: {result.stderr}")
                return False
                
        except subprocess.TimeoutExpired:
            self.logger.error(f"vcd2fst timed out for {vcd_path}")
            return False
        except FileNotFoundError:
            self.logger.error("vcd2fst command not found. Please install GTKWave or similar tool.")
            return False
        except Exception as e:
            self.logger.error(f"Error converting VCD to FST: {e}")
            return False
    
    def _sanitize_filename(self, name: str) -> str:
        """
        Sanitize assertion name for use as filename.
        
        Args:
            name: Original assertion name
            
        Returns:
            Sanitized filename
        """
        # Replace characters that might cause issues in filenames
        # Keep alphanumeric, dots, underscores, and hyphens
        sanitized = re.sub(r'[^\w.\-]', '_', name)
        return sanitized
    
    def _exit_jaspergold(self) -> None:
        """Send exit command to JasperGold."""
        self.logger.info("Exiting JasperGold...")
        if self.child is None:
            return
        try:
            self.child.sendline("exit")
            self.child.expect(pexpect.EOF, timeout=60)
        except (pexpect.TIMEOUT, pexpect.EOF):
            pass
    
    def _cleanup(self) -> None:
        """Clean up pexpect child process."""
        if self.child:
            try:
                if self.child.isalive():
                    self.child.terminate(force=True)
            except Exception:
                pass
            self.child = None
    
    def _build_summary(
        self,
        all_assertions: List[AssertionResult],
        proven: List[AssertionResult],
        cex: List[AssertionResult],
    ) -> str:
        """
        Build a human-readable summary of verification results.
        
        Args:
            all_assertions: All assertion results
            proven: Proven assertions
            cex: Counterexample assertions
            
        Returns:
            Summary string
        """
        total = len(all_assertions)
        proven_count = len(proven)
        cex_count = len(cex)
        other_count = total - proven_count - cex_count
        
        lines = [
            "=" * 60,
            "JASPER GOLD VERIFICATION SUMMARY",
            "=" * 60,
            f"Total assertions: {total}",
            f"  Proven: {proven_count}",
            f"  Counterexamples: {cex_count}",
            f"  Other: {other_count}",
            "",
        ]
        
        if cex:
            lines.append("Assertions with counterexamples:")
            for a in cex:
                fst_info = f" -> {os.path.basename(a.fst_file)}" if a.fst_file else ""
                lines.append(f"  - {a.name}{fst_info}")
        
        if proven:
            lines.append("\nProven assertions:")
            for a in proven:
                lines.append(f"  - {a.name}")
        
        lines.append("=" * 60)
        
        return "\n".join(lines)


class LogCapture:
    """Helper class to capture pexpect output to the runner's raw_output."""
    
    def __init__(self, runner: JasperGoldRunner):
        self.runner = runner
    
    def write(self, data: str) -> None:
        self.runner.raw_output += data
    
    def flush(self) -> None:
        pass


def run_jaspergold_verification(
    verilog_dir: str,
    logger: logging.Logger,
) -> Dict[str, Any]:
    """
    Convenience function to run JasperGold verification.
    
    Args:
        verilog_dir: Directory containing setup.sh and Verilog files
        logger: Logger instance
        
    Returns:
        Dictionary with verification results
    """
    runner = JasperGoldRunner(verilog_dir, logger)
    result = runner.run_verification()
    
    return {
        "success": result.success,
        "summary": result.summary,
        "assertions": [
            {
                "name": a.name,
                "result": a.result,
                "engine": a.engine,
                "bound": a.bound,
                "time": a.time,
                "fst_file": a.fst_file,
            }
            for a in result.assertions
        ],
        "cex_assertions": [
            {
                "name": a.name,
                "result": a.result,
                "fst_file": a.fst_file,
            }
            for a in result.cex_assertions
        ],
        "proven_count": len(result.proven_assertions),
        "cex_count": len(result.cex_assertions),
        "fst_files": result.fst_files,
        "error": result.error,
        "raw_output": result.raw_output,
    }
