import logging
import os
from logging.handlers import RotatingFileHandler
from typing import Optional
from .llm_properties import LOG_PATH

# Constants
DEFAULT_BASE_NAME = "application-parameter-reduction.log"
LOG_FORMAT = '%(asctime)s - %(name)s - %(levelname)s - %(message)s'

# Initialize log directory
os.makedirs(LOG_PATH, exist_ok=True)

# Create formatter
formatter = logging.Formatter(LOG_FORMAT)

# Create root logger
logger = logging.getLogger("TileLinkLLM")
logger.setLevel(logging.INFO)

# Global state
log_file: Optional[str] = None
file_handler: Optional[RotatingFileHandler] = None

def get_unique_log_filename(base_name: str, directory: str = LOG_PATH) -> str:
    """
    Generate unique log filename by adding sequence number if file exists.
    
    Args:
        base_name: Base filename
        directory: Target directory (defaults to LOG_PATH)
        
    Returns:
        Unique file path
    """
    base_path = os.path.join(directory, base_name)
    
    if not os.path.exists(base_path):
        return base_path
    
    # Add sequence number if base file exists
    name, ext = os.path.splitext(base_name)
    counter = 1
    while True:
        new_path = os.path.join(directory, f"{name}-{counter}{ext}")
        if not os.path.exists(new_path):
            return new_path
        counter += 1


def _remove_file_handlers():
    global file_handler
    for h in list(logger.handlers):
        if isinstance(h, RotatingFileHandler):
            logger.removeHandler(h)
            try:
                h.close()
            except Exception:
                pass
    file_handler = None


def _configure_file_handler(base_name: str, clear_log: bool = False):
    global log_file, file_handler

    _remove_file_handlers()

    log_file = get_unique_log_filename(base_name)
    file_handler = RotatingFileHandler(
        log_file, maxBytes=10*1024*1024, backupCount=5
    )
    file_handler.setLevel(logging.INFO)
    file_handler.setFormatter(formatter)
    logger.addHandler(file_handler)

    if clear_log:
        clear_log_file()

# 创建控制台处理器
console_handler = logging.StreamHandler()
console_handler.setLevel(logging.INFO)
console_handler.setFormatter(formatter)

def clear_log_file():
    """
    清空日志文件内容
    """
    if log_file and os.path.exists(log_file):
        open(log_file, 'w').close()

def set_log_base_name(base_name: str, clear_log: bool = False):
    """根据新的基础文件名重新配置文件日志 handler。

    Args:
        base_name: 不含路径的日志基础文件名
        clear_log: 是否清空（仅针对新文件）
    Returns:
        logging.Logger: 根 logger
    """
    _configure_file_handler(base_name, clear_log=clear_log)
    return logger


def get_logger(name: Optional[str] = None, console_output: bool = True, clear_log: bool = False, base_name: Optional[str] = None):
    """
    获取logger实例
    
    Args:
        name (str, optional): logger名称
        console_output (bool): 是否同时输出到控制台，默认为True
        clear_log (bool): 是否清空之前的日志内容，默认为False
        
    Returns:
        logging.Logger: 配置好的logger实例
    """
    # 如果传入新的 base_name，则重设文件 handler；否则按需清空现有文件
    if base_name:
        set_log_base_name(base_name, clear_log=clear_log)
    else:
        if file_handler is None:
            _configure_file_handler(DEFAULT_BASE_NAME, clear_log=clear_log)
        elif clear_log:
            clear_log_file()
    
    # 根据name获取logger
    if name:
        target_logger = logger.getChild(name)
    else:
        target_logger = logger
    
    # 根据console_output参数决定是否添加控制台处理器
    if console_output and console_handler not in target_logger.handlers:
        target_logger.addHandler(console_handler)
    elif not console_output and console_handler in target_logger.handlers:
        target_logger.removeHandler(console_handler)
    
    return target_logger

def setup_logging(stage: Optional[str] = None, full: bool = False):
    """
    Set up logging configuration based on execution mode.
    
    Args:
        stage: Single stage name (for single-stage mode)
        full: Whether running full workflow
        
    Returns:
        Logger instance
    """
    if full:
        base_name = "application-formal-full.log"
    elif stage:
        base_name = f"application-formal-{stage}.log"
    else:
        base_name = "application-formal.log"
    
    return get_logger(__name__, console_output=False, clear_log=True, base_name=base_name)
