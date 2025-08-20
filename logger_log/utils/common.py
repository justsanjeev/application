from __future__ import annotations

import logging

from qiutils.logger.log import initialize_logging

# logger instance
logger = logging.getLogger(__name__)

# Initialization of the handler
handlers = initialize_logging(
    log=logger, log_name="application.log", timestamp=True, color=True
)


def log_caller() -> None:
    # Uses
    logger.debug("Hey I am from common module")
    logger.debug("Debug message - goes to file only")
    logger.info("Info message - goes to file only")
    logger.warning("Warning message - goes to both file and console")
    logger.error("Error message - goes to both file and console")
