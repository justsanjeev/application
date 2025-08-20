from __future__ import annotations

from qiutils.logger import log_manager

# Basic usage - gets logger with module name automatically
logger = log_manager.get_logger(__name__)

if __name__ == "__main__":
    logger.debug("This goes to file only")
    logger.info("This goes to both console and file")
    logger.error("This goes to both console and file")
    logger.warning(" This goes to both ")
    logger.critical("This is a critical system error")
