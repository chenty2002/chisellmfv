"""
Verilog to Chisel conversion workflow.

Workflow:
1. Read all .v/.sv files from verilog2chisel/verilog/<benchmark>/
2. Send to LLM to generate Chisel code in verilog2chisel/chisel/<benchmark>/
3. Compile with make, retry with error messages if compilation fails
"""

import os
import sys
import glob
import json
import logging
import shutil
import subprocess
from typing import Dict, List, Any, Optional, Tuple

from ..core.llm_client import LLMClient
from ..core.prompt_builder import build_assistant_tool_call_message
from ..utils.llm_properties import *
from ..utils.file_utils import read_file as utils_read_file, write_file as utils_write_file
from ..utils.llm_logging import LLMLogger
from .tool_schemas import get_verilog2chisel_tool_schemas, convert_tool_call_to_action
from .actions import execute_action


class Verilog2ChiselWorkflow:
    """
    Workflow for converting Verilog/SystemVerilog to Chisel.
    
    Steps:
    1. Read all Verilog files from verilog2chisel/verilog/<benchmark>/
    2. Generate Chisel code using LLM (write_files tool)
    3. Compile with make, retry with errors if needed
    """
    
    def __init__(
        self,
        llm_client: LLMClient,
        workspace_dir: str,
        benchmark: str,
        logger: logging.Logger,
        max_iterations: int = 5
    ):
        """
        Initialize the Verilog to Chisel conversion workflow.
        
        Args:
            llm_client: LLM client for API interactions
            workspace_dir: Root directory of the project
            benchmark: Benchmark name (e.g., 'gigamax', 'philo')
            logger: Logger instance
            max_iterations: Maximum compilation retry iterations
        """
        self.llm = llm_client
        self.workspace_dir = workspace_dir
        self.benchmark = benchmark
        self.logger = logger
        self.max_iterations = max_iterations
        
        # Derived paths with benchmark subdirectory
        self.verilog2chisel_dir = os.path.join(workspace_dir, "verilog2chisel")
        self.verilog_dir = os.path.join(self.verilog2chisel_dir, "verilog", benchmark)
        self.chisel_dir = os.path.join(self.verilog2chisel_dir, "chisel", benchmark)
        self.generated_dir = os.path.join(self.verilog2chisel_dir, "generated")
        
        # Track previous iteration's generated code for error feedback
        self.previous_chisel_files = {}
        
        # System prompt for conversion
        self.system_prompt = "You are an expert in Verilog and Chisel hardware description languages. Convert Verilog code to equivalent Chisel code."
        
        # Create directories if they don't exist
        os.makedirs(self.chisel_dir, exist_ok=True)
        os.makedirs(self.generated_dir, exist_ok=True)
    
    def read_verilog_files(self) -> Tuple[Dict[str, str], Dict[str, str]]:
        """
        Read all Verilog/SystemVerilog files and their instruction txt files from verilog directory.
        
        Returns:
            Tuple of (verilog_files, instruction_files)
            - verilog_files: Dictionary mapping filename to content
            - instruction_files: Dictionary mapping verilog filename to its instruction content
        """
        verilog_files = {}
        instruction_files = {}
        
        # Find all .v and .sv files
        v_files = glob.glob(os.path.join(self.verilog_dir, "*.v"))
        sv_files = glob.glob(os.path.join(self.verilog_dir, "*.sv"))
        all_files = v_files + sv_files
        
        self.logger.info(f"Found {len(all_files)} Verilog files: {[os.path.basename(f) for f in all_files]}")
        
        for file_path in all_files:
            filename = os.path.basename(file_path)
            try:
                content = utils_read_file(file_path)
                verilog_files[filename] = content
                self.logger.info(f"Read {filename}: {len(content)} chars")
                
                # Look for corresponding instruction txt file
                # Pattern: Prob156_review2015_fancytimer_ref.sv -> Prob156_review2015_fancytimer_prompt.txt
                base_name = os.path.splitext(filename)[0]
                # Remove _ref suffix if present
                if base_name.endswith('_ref'):
                    base_name = base_name[:-4]
                instruction_file = os.path.join(self.verilog_dir, f"{base_name}_prompt.txt")
                
                if os.path.exists(instruction_file):
                    try:
                        instruction_content = utils_read_file(instruction_file)
                        instruction_files[filename] = instruction_content
                        self.logger.info(f"Read instruction for {filename}: {len(instruction_content)} chars")
                    except Exception as e:
                        self.logger.warning(f"Failed to read instruction file {instruction_file}: {e}")
            except Exception as e:
                self.logger.error(f"Failed to read {filename}: {e}")
        
        return verilog_files, instruction_files
    
    def build_conversion_prompt(
        self, 
        verilog_files: Dict[str, str],
        instruction_files: Dict[str, str],
    ) -> str:
        """
        Build the initial user prompt for LLM to convert Verilog to Chisel.
        
        Args:
            verilog_files: Dictionary of Verilog filename -> content
            instruction_files: Dictionary of Verilog filename -> instruction content
            
        Returns:
            Formatted prompt string
        """
        prompt_parts = []
        
        # Header
        prompt_parts.extend([
            "# Task: Convert Verilog/SystemVerilog to Chisel",
            "",
            "Convert the following Verilog/SystemVerilog files to Chisel code.",
            ""
        ])
        
        # Instructions
        prompt_parts.extend([
            "## Requirements",
            "1. Generate syntactically correct Chisel code",
            "2. Preserve the original module names and functionality",
            "3. Use proper Chisel idioms (Bundle, Module, IO, etc.)",
            "4. Each Verilog file should become a Scala file with the same base name",
            "5. All modules should be in package 'llmverify'",
            "6. Include proper imports: chisel3._, chisel3.util._",
            "7. IMPORTANT: Chisel's Clock signals do not need to be explicitly defined; ignore Clock inputs from Verilog",
            "8. IMPORTANT: Create a main method to generate Verilog",
            "   Example structure:",
            "   ```scala",
            "   package llmverify",
            "   import chisel3._",
            "   object VerilogGenerator extends App {",
            "     emitVerilog(new YourModule(), args)",
            "   }",
            "   ```",
            "9. IMPORTANT: Chisel compiler optimizes the modules and removes unused signals, ",
            "   so you need to create extra Outputs in the top-level module to preserve the whole design if needed.",
            ""])
        
        # Verilog files with instructions
        prompt_parts.append("## Verilog Files to Convert")
        prompt_parts.append("")
        for filename, content in verilog_files.items():
            prompt_parts.append(f"### {filename}")
            prompt_parts.append("")
            
            # Add instruction if available
            if filename in instruction_files:
                prompt_parts.append("**Design Specification and Requirements:**")
                prompt_parts.append("```")
                prompt_parts.append(instruction_files[filename])
                prompt_parts.append("```")
                prompt_parts.append("")
            
            prompt_parts.append("**Verilog Implementation:**")
            prompt_parts.extend([
                "```verilog",
                content,
                "```",
                ""
            ])
        # Tool usage instruction
        prompt_parts.extend([
            "## Output",
            "",
            "Use the write_files tool to generate Chisel files.",
            "For each Verilog file, create a corresponding .scala file with:",
            "- file_path: Just the filename (e.g., 'gigamax.scala' for gigamax.sv)",
            "- content: Complete Chisel source code",
            "",
            "Set stage_complete=true when all files are converted.",
            ""
        ])
        
        return "\n".join(prompt_parts)
    
    def build_compilation_error_message(
        self,
        compilation_error: str,
        previous_chisel_files: Dict[str, str]
    ) -> str:
        """
        Build a tool result message containing compilation error for agent loop.
        
        Args:
            compilation_error: Compilation error output
            previous_chisel_files: Dictionary of previously generated Chisel files
            
        Returns:
            Formatted error message string
        """
        parts = [
            "## Compilation Failed",
            "",
            "The generated Chisel code failed to compile.",
            ""
        ]
        
        # Show compilation error
        parts.extend([
            "### Compilation Error",
            "",
            "```",
            compilation_error,
            "```",
            "",
            "Please analyze the error and fix the issues in the Chisel code.",
            "Make sure to:",
            "1. Address all compilation errors shown above",
            "2. Maintain correct Scala/Chisel syntax",
            "3. Preserve the module functionality",
            "",
            "Use the write_files tool to regenerate the fixed Chisel code.",
            ""
        ])
        
        return "\n".join(parts)
    
    def write_chisel_file(self, file_path: str, content: str) -> Tuple[bool, str]:
        """
        Write a Chisel file to the chisel directory.
        
        Args:
            file_path: Relative path under chisel/ directory
            content: File content
            
        Returns:
            Tuple of (success, message)
        """
        try:
            # Ensure file_path is relative and safe
            if os.path.isabs(file_path) or ".." in file_path:
                return False, f"Invalid file path (must be relative): {file_path}"
            
            full_path = os.path.join(self.chisel_dir, file_path)
            
            # Create parent directory if needed
            parent_dir = os.path.dirname(full_path)
            if parent_dir:
                os.makedirs(parent_dir, exist_ok=True)
            
            # Write file
            utils_write_file(full_path, content)
            self.logger.info(f"Wrote Chisel file: {file_path} ({len(content)} chars)")
            return True, f"Successfully wrote {file_path}"
        except Exception as e:
            error_msg = f"Failed to write {file_path}: {str(e)}"
            self.logger.error(error_msg)
            return False, error_msg
    
    def _prepare_benchmark_build_files(self) -> Tuple[bool, str]:
        """
        Copy build.sbt and Makefile from verilog2chisel/ to the benchmark chisel directory.
        
        Returns:
            Tuple of (success, message)
        """
        try:
            for filename in ["build.sbt", "Makefile"]:
                src_file = os.path.join(self.verilog2chisel_dir, filename)
                dest_file = os.path.join(self.chisel_dir, filename)
                if os.path.exists(src_file) and not os.path.exists(dest_file):
                    shutil.copy2(src_file, dest_file)
                    self.logger.info(f"Copied {filename} to {self.chisel_dir}")
                else:
                    self.logger.warning(f"Source file {src_file} not found or destination already exists")
            return True, "Build files copied successfully"
        except Exception as e:
            return False, f"Error copying build files: {str(e)}"
    
    def run_make(self) -> Tuple[bool, str]:
        """
        Run make in the benchmark chisel directory.
        First copies build.sbt and Makefile to the benchmark directory.
        
        Returns:
            Tuple of (success, output)
        """
        try:
            # Copy build files to benchmark directory
            ok, msg = self._prepare_benchmark_build_files()
            if not ok:
                return False, msg
            
            self.logger.info(f"Running make in {self.chisel_dir}...")
            command = f"cd {self.chisel_dir} && make"
            
            result = subprocess.run(
                command,
                shell=True,
                capture_output=True,
                text=True,
                timeout=300  # 5 minutes timeout
            )
            
            output = result.stdout + "\n" + result.stderr
            success = result.returncode == 0
            
            if success:
                self.logger.info("Compilation successful")
            else:
                self.logger.warning(f"Compilation failed with return code {result.returncode}")
            
            return success, output
        except subprocess.TimeoutExpired:
            error_msg = "Compilation timeout (5 minutes)"
            self.logger.error(error_msg)
            return False, error_msg
        except Exception as e:
            error_msg = f"Compilation error: {str(e)}"
            self.logger.error(error_msg)
            return False, error_msg
    
    def convert(self) -> Dict[str, Any]:
        """
        Run the complete Verilog to Chisel conversion workflow using standard agent loop.
        
        Uses chat_with_tools with message history for iterative compilation error feedback.
        
        Returns:
            Dictionary containing conversion results
        """
        self.logger.info(f"Starting Verilog to Chisel conversion workflow for benchmark: {self.benchmark}")
        print(f"Starting Verilog to Chisel conversion workflow for benchmark: {self.benchmark}")
        
        # Step 1: Read Verilog files and instructions
        verilog_files, instruction_files = self.read_verilog_files()
        if not verilog_files:
            return {
                "success": False,
                "error": f"No Verilog files found in verilog2chisel/verilog/{self.benchmark}/"
            }
        
        print(f"Found {len(verilog_files)} Verilog file(s)")
        if instruction_files:
            print(f"Found {len(instruction_files)} instruction file(s)")
            self.logger.info(f"Instruction files: {list(instruction_files.keys())}")
        
        # Build initial message history for agent loop
        tool_schemas = get_verilog2chisel_tool_schemas()
        user_prompt = self.build_conversion_prompt(verilog_files, instruction_files)
        
        messages: List[Dict[str, Any]] = [
            {"role": "system", "content": self.system_prompt},
            {"role": "user", "content": user_prompt}
        ]
        
        # Agent loop with compilation retry
        for iteration in range(1, self.max_iterations + 1):
            self.logger.info(f"=== Iteration {iteration}/{self.max_iterations} ===")
            print(f"\n=== Iteration {iteration}/{self.max_iterations} ===")
            
            # Log request with full details
            self._log_llm_request(iteration, messages, tool_schemas)
            
            try:
                response = self.llm.chat_with_tools(
                    messages=messages,
                    tools=tool_schemas,
                    temperature=0.3
                )
            except Exception as e:
                self.logger.error(f"LLM API error: {e}")
                return {
                    "success": False,
                    "error": f"LLM API error: {str(e)}"
                }
            
            # Log response with full details
            self._log_llm_response(iteration, response)
            
            if response["type"] != "function_calls":
                # Handle unexpected text response
                text = response.get("content", "")
                self.logger.warning(f"Got text response instead of function calls: {text[:500]}")
                
                # Add assistant text response to history and prompt for tool use
                if text:
                    messages.append({"role": "assistant", "content": text})
                messages.append({
                    "role": "user",
                    "content": "ERROR: You must respond with tool calls only. Use the write_files tool to generate Chisel code."
                })
                continue
            
            # Execute tool calls
            function_calls = response["function_calls"]
            raw_message = response.get("raw_message", {})
            
            # Add assistant message with tool_calls to history.
            assistant_message = build_assistant_tool_call_message(
                raw_message,
                function_calls,
            )
            messages.append(assistant_message)
            
            # Execute actions and build tool result messages
            stage_complete = False
            current_iteration_files = {}
            
            for fc in function_calls:
                action = convert_tool_call_to_action(fc)
                result = execute_action(action, self.write_chisel_file)
                
                self.logger.info(f"Action result: {result.get('success')}")
                
                # Add tool result message to history
                tool_message = {
                    "role": "tool",
                    "tool_call_id": fc["id"],
                    "name": fc["name"],
                    "content": json.dumps(result, ensure_ascii=False)
                }
                messages.append(tool_message)
                
                if not result["success"]:
                    # Continue to let LLM see the error and retry
                    self.logger.warning(f"Action failed: {result.get('error', 'Unknown error')}")
                    continue
                
                # Collect generated files for potential error feedback
                if "generated_files" in result:
                    current_iteration_files.update(result["generated_files"])
                
                if result.get("stage_complete"):
                    stage_complete = True
                    print("Conversion complete.")
            
            if not stage_complete:
                self.logger.warning("LLM did not set stage_complete=true")
            
            # Step 3: Compile Chisel code
            print("Compiling Chisel code...")
            compile_success, compile_output = self.run_make()
            
            if compile_success:
                print("✓ Compilation successful!")
                self.logger.info("Compilation successful")
                
                # Copy generated Chisel code to chisel/extra_bench/<benchmark>/
                copy_success, copy_msg = self._copy_to_extra_bench()
                if copy_success:
                    print(f"✓ Copied Chisel code to extra_bench/{self.benchmark}/")
                    self.logger.info(copy_msg)
                else:
                    print(f"⚠ Warning: {copy_msg}")
                    self.logger.warning(copy_msg)
                
                return {
                    "success": True,
                    "iterations": iteration,
                    "verilog_files": list(verilog_files.keys()),
                    "compile_output": compile_output,
                    "extra_bench_path": os.path.join(self.workspace_dir, "chisel", "extra_bench", self.benchmark)
                }
            else:
                print(f"✗ Compilation failed (attempt {iteration}/{self.max_iterations})")
                self.logger.warning(f"Compilation failed on iteration {iteration}")
                self.logger.info(f"Compilation output:\n{compile_output}")
                
                # Save current iteration's generated files for error feedback
                self.previous_chisel_files = current_iteration_files.copy()
                self.logger.info(f"Saved {len(self.previous_chisel_files)} Chisel files for error feedback")
                
                # Prepare error for next iteration
                error_lines = [line for line in compile_output.splitlines() if '[error]' in line.lower()]
                compilation_error = '\n'.join(error_lines) if error_lines else compile_output
                
                # If this was the last iteration, return failure
                if iteration == self.max_iterations:
                    return {
                        "success": False,
                        "error": "Max iterations reached, compilation still failing",
                        "compile_output": compile_output
                    }
                
                # Add compilation error as user message for next iteration
                error_message = self.build_compilation_error_message(
                    compilation_error, 
                    self.previous_chisel_files
                )
                messages.append({
                    "role": "user",
                    "content": error_message
                })
        
        # Should not reach here
        return {
            "success": False,
            "error": "Unexpected end of conversion loop"
        }
    
    def _copy_to_extra_bench(self) -> Tuple[bool, str]:
        """
        Copy generated Chisel files to chisel/extra_bench/<benchmark>/ directory.
        
        This allows the generated code to be used by the formal verification workflow.
        
        Returns:
            Tuple of (success, message)
        """
        try:
            # Target directory: <workspace_dir>/chisel/extra_bench/<benchmark>/
            extra_bench_dir = os.path.join(self.workspace_dir, "chisel", "extra_bench", self.benchmark)
            
            # Create target directory if it doesn't exist
            os.makedirs(extra_bench_dir, exist_ok=True)
            
            # Copy all .scala files from self.chisel_dir to extra_bench_dir
            scala_files = glob.glob(os.path.join(self.chisel_dir, "*.scala"))
            copied_files = []
            
            for src_file in scala_files:
                filename = os.path.basename(src_file)
                dst_file = os.path.join(extra_bench_dir, filename)
                shutil.copy2(src_file, dst_file)
                copied_files.append(filename)
                self.logger.info(f"Copied {filename} to extra_bench/{self.benchmark}/")
            
            if copied_files:
                return True, f"Copied {len(copied_files)} files to extra_bench/{self.benchmark}/: {copied_files}"
            else:
                return False, f"No Scala files found in {self.chisel_dir}"
            
        except Exception as e:
            error_msg = f"Failed to copy files to extra_bench: {str(e)}"
            self.logger.error(error_msg)
            return False, error_msg
    
    def _log_llm_request(self, iteration: int, messages: List[Dict[str, Any]], tool_schemas: List[Dict[str, Any]]) -> None:
        """Log LLM API request details using LLMLogger utility."""
        # Extract system and user prompts from messages for logging
        system_prompt = ""
        user_prompt = ""
        for msg in messages:
            if msg.get("role") == "system":
                system_prompt = msg.get("content", "")
            elif msg.get("role") == "user":
                user_prompt = msg.get("content", "")
        
        full_prompt = f"[System Prompt]\n{system_prompt}\n\n[User Prompt]\n{user_prompt}"
        log_msg = LLMLogger.format_request(
            full_prompt,
            tool_schemas,
            stage="Verilog2Chisel",
            iteration=iteration,
            include_details=(iteration == 1),
        )
        self.logger.info(log_msg)
    
    def _log_llm_response(self, iteration: int, response: Dict[str, Any]) -> None:
        """Log LLM API response details using LLMLogger utility."""
        log_msg = LLMLogger.format_response(
            response, stage="Verilog2Chisel", iteration=iteration, truncate_content=False
        )
        self.logger.info(log_msg)
