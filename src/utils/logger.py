"""Console logging for interactive commands."""

from __future__ import annotations

import logging
from typing import Optional


def get_logger(
    name: Optional[str] = None,
    console_output: bool = True,
    **_ignored: object,
) -> logging.Logger:
    logger = logging.getLogger(name or "chisellmfv")
    logger.setLevel(logging.INFO)
    if console_output and not logger.handlers:
        handler = logging.StreamHandler()
        handler.setFormatter(
            logging.Formatter("%(asctime)s %(name)s %(levelname)s %(message)s")
        )
        logger.addHandler(handler)
    return logger
