"""
Build operations helper for formal verification workflow.

Handles compilation, Verilog file management, and verification setup.
"""

import os
import re
import subprocess
import shutil
import glob
from typing import Dict, List, Optional, Tuple, Any
import logging

from .jaspergold_runner import run_jaspergold_verification


class BuildOperations:
    """
    Helper class for build operations in the formal verification workflow.
    
    Provides methods for:
    - Running make commands
    - Managing Verilog output files
    - Running verification setup scripts
    - Preparing benchmark build files
    """
    
    def __init__(
        self,
        chisel_dir: str,
        work_dir: str,
        generated_dir: str,
        verilog_dir: str,
        workspace_dir: str,
        logger: logging.Logger,
    ):
        """
        Initialize build operations helper.
        
        Args:
            chisel_dir: Path to the chisel directory
            work_dir: Working directory for current target
            generated_dir: Directory for generated files
            verilog_dir: Directory for Verilog output
            workspace_dir: Root workspace directory
            is_benchmark: Whether the target is a benchmark
            logger: Logger instance
        """
        self.chisel_dir = chisel_dir
        self.work_dir = work_dir
        self.generated_dir = generated_dir
        self.verilog_dir = verilog_dir
        self.workspace_dir = workspace_dir
        self.logger = logger
    
    def prepare_benchmark_build_files(self) -> Tuple[bool, str]:
        """
        For benchmark targets, copy build.sbt and Makefile from extra_bench/ to the benchmark directory.
        
        Returns:
            Tuple of (success, message)
        """
        try:
            extra_bench_dir = os.path.join(self.chisel_dir, "extra_bench")
            for filename in ["build.sbt", "Makefile"]:
                src_file = os.path.join(extra_bench_dir, filename)
                dest_file = os.path.join(self.work_dir, filename)
                if os.path.exists(src_file):
                    shutil.copy2(src_file, dest_file)
                    self.logger.info(f"Synced {filename} to {self.work_dir}")
                else:
                    self.logger.warning(f"Source file {src_file} not found")
            return True, "Build files copied successfully"
        except Exception as e:
            return False, f"Error copying build files: {str(e)}"
    
    def run_make(self, target: str = "verilog") -> Tuple[bool, str]:
        """
        Run make in the work directory.
        
        Args:
            target: Make target to run (default: "verilog")
            
        Returns:
            Tuple of (success, output)
        """
        try:
            # For benchmark targets, ensure build files are in place
            ok, msg = self.prepare_benchmark_build_files()
            if not ok:
                return False, msg
            
            command = f"cd {self.work_dir} && make {target}"
            result = subprocess.run(
                command,
                shell=True,
                capture_output=True,
                text=True,
                timeout=600  # 10 minute timeout
            )
            output = result.stdout + "\n" + result.stderr
            success = result.returncode == 0
            if success:
                return True, output
            
            error_lines = [line for line in output.splitlines() if '[error]' in line.lower()]
            output = '\n'.join(error_lines) if error_lines else output
            return False, output
        except subprocess.TimeoutExpired:
            return False, "Make command timed out after 10 minutes"
        except Exception as e:
            return False, f"Error running make: {str(e)}"
    
    def move_verilog_to_output(self) -> Tuple[bool, str, Optional[str]]:
        """
        Move generated Verilog files to the verilog output directory.
        For benchmark targets, also copies set_testtop.py and setup.sh from verilog/.
        
        Returns:
            Tuple of (success, message, verilog_file_path)
        """
        try:
            # Find generated .sv files
            sv_pattern = os.path.join(self.generated_dir, "*.sv")
            sv_files = glob.glob(sv_pattern)
            
            if not sv_files:
                return False, f"No .sv files found in {self.generated_dir}", None
            
            # Ensure verilog output directory exists
            os.makedirs(self.verilog_dir, exist_ok=True)
            
            # Copy all .sv and .v files to verilog directory
            verilog_files = []
            for f in sv_files:
                dest_path = os.path.join(self.verilog_dir, os.path.basename(f))
                shutil.copy2(f, dest_path)
                verilog_files.append(dest_path)
                self.logger.info(f"Copied {f} to {dest_path}")
            
            # Also copy any .v files if they exist
            v_pattern = os.path.join(self.generated_dir, "*.v")
            v_files = glob.glob(v_pattern)
            for f in v_files:
                dest_path = os.path.join(self.verilog_dir, os.path.basename(f))
                shutil.copy2(f, dest_path)
                verilog_files.append(dest_path)
                self.logger.info(f"Copied {f} to {dest_path}")
            
            # For benchmark targets, copy set_testtop.py and setup.sh from verilog/
            self._copy_benchmark_scripts()
            
            # Find main file
            main_file = self._find_main_verilog_file(verilog_files)
            
            return True, f"Copied {len(verilog_files)} Verilog files to {self.verilog_dir}", main_file
            
        except Exception as e:
            return False, f"Error moving verilog files: {str(e)}", None
    
    def _copy_benchmark_scripts(self) -> None:
        """Copy set_testtop.py and setup.sh for benchmark targets."""
        base_verilog_dir = os.path.join(self.workspace_dir, "verilog", "extra_bench")
        for script in ["set_testtop.py", "setup.sh", "ResetCounter.sv"]:
            src_script = os.path.join(base_verilog_dir, script)
            dest_script = os.path.join(self.verilog_dir, script)
            if os.path.exists(src_script):
                shutil.copy2(src_script, dest_script)
                self.logger.info(f"Copied {script} to {self.verilog_dir}")
            else:
                self.logger.warning(f"Script {src_script} not found")
    
    def _find_main_verilog_file(self, verilog_files: List[str]) -> Optional[str]:
        """Find the main Verilog file from a list of files."""
        main_file = None
        for f in verilog_files:
            basename = os.path.basename(f)
            if "TestTop" in basename or "Main" in basename:
                main_file = f
                break
        
        if not main_file and verilog_files:
            main_file = verilog_files[0]
        
        return main_file

    def _find_generated_verilog_files(self) -> List[str]:
        """Return generated Verilog/SystemVerilog files under the build output directory."""
        patterns = [
            os.path.join(self.generated_dir, "**", "*.sv"),
            os.path.join(self.generated_dir, "**", "*.v"),
        ]
        files: List[str] = []
        for pattern in patterns:
            files.extend(glob.glob(pattern, recursive=True))
        return sorted(set(files))

    def _remove_generated_verilog_files(self) -> None:
        """Remove stale generated Verilog before checks that inspect current output."""
        for path in self._find_generated_verilog_files():
            try:
                os.remove(path)
                self.logger.debug(f"Removed stale generated Verilog file: {path}")
            except OSError as exc:
                self.logger.warning(f"Failed to remove stale generated Verilog {path}: {exc}")

    @staticmethod
    def _strip_verilog_comments(content: str) -> str:
        """Strip Verilog comments so comment text does not satisfy assertion checks."""
        content = re.sub(r"/\*.*?\*/", "", content, flags=re.DOTALL)
        content = re.sub(r"//.*", "", content)
        return content

    @classmethod
    def _count_verilog_assertions(cls, content: str) -> int:
        """Count common SystemVerilog assertion forms in generated code."""
        code = cls._strip_verilog_comments(content)
        assertion_patterns = [
            r"\bassert\s+property\b",
            r"\bassert\s+final\s*\(",
            r"\bassert\s*(?:#\s*\d+\s*)?\(",
        ]
        return sum(len(re.findall(pattern, code, flags=re.IGNORECASE)) for pattern in assertion_patterns)

    @classmethod
    def _contains_verilog_assertion(cls, content: str) -> bool:
        """Detect common SystemVerilog assertion forms in generated code."""
        return cls._count_verilog_assertions(content) > 0

    def check_generated_verilog_has_assertions(self) -> Dict[str, Any]:
        """
        Verify that generated Verilog/SystemVerilog contains at least one assertion.

        Stage 2 can compile successfully even when assertions were placed in an
        un-emitted wrapper module. This check catches that case before the stage
        is accepted.
        """
        verilog_files = self._find_generated_verilog_files()
        if not verilog_files:
            return {
                "success": False,
                "assertion_count": 0,
                "files_checked": [],
                "error": f"No generated Verilog/SystemVerilog files found in {self.generated_dir}",
            }

        assertion_files: List[str] = []
        assertion_count = 0
        read_errors: List[str] = []
        for path in verilog_files:
            try:
                with open(path, "r", encoding="utf-8", errors="ignore") as f:
                    content = f.read()
            except OSError as exc:
                read_errors.append(f"{path}: {exc}")
                continue

            file_assertion_count = self._count_verilog_assertions(content)
            if file_assertion_count:
                assertion_files.append(path)
                assertion_count += file_assertion_count

        if assertion_files:
            self.logger.info(
                "Generated Verilog assertion check passed: "
                f"found {assertion_count} assertions in "
                f"{len(assertion_files)} of {len(verilog_files)} files"
            )
            return {
                "success": True,
                "assertion_count": assertion_count,
                "files_checked": verilog_files,
                "assertion_files": assertion_files,
            }

        files_display = "\n".join(f"- {path}" for path in verilog_files)
        details = [
            "Compilation succeeded, but generated Verilog/SystemVerilog contains no assertions.",
            "",
            "This usually means the assertions were added only to a module/class that is not emitted by `VerilogGenerator`, such as a standalone `*Formal` wrapper or sibling module.",
            "",
            "Rewrite the Chisel source so the assertions are directly inside the original DUT module/class currently emitted by `VerilogGenerator`/`make verilog`.",
            "Do not create a separate `*Formal` module unless the existing generator already emits that exact instrumented original DUT.",
            "",
            "Generated Verilog files checked:",
            files_display,
        ]
        if read_errors:
            details.extend(["", "Files that could not be read:", *read_errors])

        error = "\n".join(details)
        self.logger.warning(error)
        return {
            "success": False,
            "assertion_count": 0,
            "files_checked": verilog_files,
            "error": error,
        }
    
    def run_verilog_setup(self, verilog_file: str, run_jaspergold: bool = True) -> Tuple[bool, str, Optional[Dict[str, Any]]]:
        """
        Run set_testtop.py and optionally JasperGold verification in the verilog directory.
        
        Args:
            verilog_file: Path to the verilog file
            run_jaspergold: Whether to run JasperGold verification (default: True)
            
        Returns:
            Tuple of (success, output, jaspergold_result)
        """
        try:
            verilog_filename = os.path.basename(verilog_file)
            
            # Run python set_testtop.py <verilog-file>
            set_testtop_cmd = f"cd {self.verilog_dir} && python set_testtop.py {verilog_filename}"
            self.logger.info(f"Running: {set_testtop_cmd}")
            
            result1 = subprocess.run(
                set_testtop_cmd,
                shell=True,
                capture_output=True,
                text=True,
                timeout=300
            )
            
            output1 = f"=== set_testtop.py output ===\n{result1.stdout}\n{result1.stderr}"
            
            if result1.returncode != 0:
                return False, f"set_testtop.py failed:\n{output1}", None
            
            if not run_jaspergold:
                return True, output1, None
            
            # Run JasperGold verification using pexpect-based runner
            self.logger.info("Starting JasperGold verification...")
            jg_result = self.run_jaspergold_verification()
            
            full_output = output1 + "\n\n" + jg_result.get("summary", "")
            
            # Verification success if JasperGold ran successfully
            # (even if there are counterexamples - that's a valid verification result)
            success = jg_result.get("success", False)
            
            return success, full_output, jg_result
            
        except subprocess.TimeoutExpired:
            return False, "Verification command timed out", None
        except Exception as e:
            return False, f"Error running verilog setup: {str(e)}", None
    
    def run_jaspergold_verification(self) -> Dict[str, Any]:
        """
        Run JasperGold formal verification using pexpect-based interactive runner.
        
        This method handles:
        1. Running setup.sh which launches JasperGold with verify.tcl
        2. Waiting for prove -all -bg to complete
        3. Sending 'report' command to get assertion results
        4. For each CEX assertion, generating VCD traces
        5. Exiting JasperGold gracefully
        
        Returns:
            Dict with verification results including:
            - success: Whether verification ran successfully
            - summary: Human-readable summary
            - assertions: List of all assertion results
            - cex_assertions: List of assertions with counterexamples
            - fst_files: List of generated FST waveform file paths
            - counterexample_path: Path to first FST file (for waveform analysis)
            - error: Error message if failed
        """
        self.logger.info(f"Running JasperGold verification in {self.verilog_dir}")
        return run_jaspergold_verification(self.verilog_dir, self.logger)
    
    def verify_compilation(self, require_assertions: bool = False) -> Dict[str, Any]:
        """
        Verify that the current code compiles successfully.
        
        Args:
            require_assertions: When true, also require generated Verilog to
                contain at least one assertion after compilation.

        Returns:
            Dict with 'success' and optionally 'error' if compilation failed
        """
        self.logger.info("Verifying compilation with 'make verilog'...")
        if require_assertions:
            self._remove_generated_verilog_files()
        
        ok, output = self.run_make("verilog")
        
        if ok:
            if require_assertions:
                assertion_result = self.check_generated_verilog_has_assertions()
                if not assertion_result["success"]:
                    return {
                        "success": False,
                        "error": assertion_result["error"],
                        "output": output,
                        "action_results": [
                            {
                                "type": "run_make",
                                "success": True,
                                "output": output,
                            },
                            {
                                "type": "assertion_check",
                                "success": False,
                                "output": assertion_result["error"],
                                "files_checked": assertion_result.get("files_checked", []),
                                "assertion_count": assertion_result.get("assertion_count", 0),
                            },
                        ],
                    }

            self.logger.info("Compilation successful")
            result = {"success": True, "output": output}
            if require_assertions:
                result["assertion_check"] = assertion_result
            return result
        else:
            # Extract only lines containing [error] for cleaner output
            error_lines = [line for line in output.splitlines() if '[error]' in line.lower()]
            error_output = '\n'.join(error_lines) if error_lines else output
            
            self.logger.warning(f"Compilation failed:\n{error_output}")
            return {
                "success": False,
                "error": error_output,
                "action_results": [{
                    "type": "run_make",
                    "success": False,
                    "output": error_output,
                }]
            }
    
    def run_full_verification_flow(self) -> Dict[str, Any]:
        """
        Run the full verification flow:
        1. Run make to compile
        2. Move verilog to output directory
        3. Run set_testtop.py and setup.sh
        
        Returns:
            Dict with verification results
        """
        self.logger.info("Running full verification flow...")
        
        # Step 1: Compile
        ok, compile_output = self.run_make("verilog")
        if not ok:
            self.logger.error("Compilation failed (unexpected)")
            print("Compilation failed")
            return {
                "success": False,
                "summary": "Compilation failed",
                "verification_passed": False,
                "output": compile_output,
            }
        
        self.logger.info("Compilation successful")
        print("Compilation successful")
        
        # Step 2: Move verilog to output directory
        ok, msg, verilog_path = self.move_verilog_to_output()
        if not ok:
            self.logger.error(f"Failed to move verilog: {msg}")
            print(f"Failed to move verilog: {msg}")
            return {
                "success": False,
                "summary": f"Failed to move verilog: {msg}",
                "verification_passed": False,
                "output": msg,
            }
        
        if not verilog_path:
            print("No verilog file path returned")
            return {
                "success": False,
                "summary": "No verilog file path returned",
                "verification_passed": False,
                "output": "Verilog file path is None",
            }
        
        self.logger.info(f"Verilog moved to: {verilog_path}")
        print(f"Verilog moved to: {verilog_path}")
        
        # Step 3: Run set_testtop.py and JasperGold verification
        ok, verify_output, jg_result = self.run_verilog_setup(verilog_path, run_jaspergold=True)
        
        if ok:
            self.logger.info("Verification completed successfully")
            
            # Check if there are counterexamples
            cex_count = jg_result.get("cex_count", 0) if jg_result else 0
            proven_count = jg_result.get("proven_count", 0) if jg_result else 0
            fst_files = jg_result.get("fst_files", []) if jg_result else []
            
            # Set counterexample_path to first FST file for waveform analysis
            counterexample_path = fst_files[0] if fst_files else None
            
            # Determine verification status
            if cex_count > 0:
                summary = f"Verification found {cex_count} counterexamples, {proven_count} proven"
                verification_passed = False
            else:
                summary = f"All {proven_count} assertions proven"
                verification_passed = True
            
            return {
                "success": True,
                "summary": summary,
                "verification_passed": verification_passed,
                "output": verify_output,
                "verilog_path": verilog_path,
                "jaspergold_result": jg_result,
                "cex_count": cex_count,
                "proven_count": proven_count,
                "fst_files": fst_files,
                "counterexample_path": counterexample_path,
            }
        else:
            self.logger.warning("Verification found issues or failed")
            
            # Try to find existing FST files even if JasperGold failed
            fst_files = []
            counterexample_path = None
            if jg_result:
                fst_files = jg_result.get("fst_files", [])
            
            return {
                "success": True,  # The stage completed, even if verification found bugs
                "summary": "Verification completed with potential issues",
                "verification_passed": False,
                "output": verify_output,
                "verilog_path": verilog_path,
                "jaspergold_result": jg_result,
                "fst_files": fst_files,
                "counterexample_path": counterexample_path,
            }
