from __future__ import annotations

from qiutils.logger import jenkins_logger
from common import utils

# Basic usage - gets logger with module name automatically
logger = jenkins_logger.get_logger(__name__)

if __name__ == "__main__":
    logger.debug("This goes to file only")
    logger.info("This goes to both console and file")
    logger.error("This goes to both console and file")
    logger.warning(" This goes to both ")
    logger.critical("This is a critical system error")

    logger.info("Calling code from common")
    utils.alllow_access()
