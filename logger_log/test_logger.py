from __future__ import annotations

import logging

from common import utils
from qiutils.logger.log import initialize_logging

# logger instance
logger = logging.getLogger(__name__)

# Initialization of the handler
handlers = initialize_logging(
    log=logger,
    log_name="application.log",
    timestamp=True,
    color=True,
    thread_name=True,
    propagate=True,
)

if __name__ == "__main__":
    logger.debug("This goes to file only")
    logger.info("This goes to both console and file")
    logger.error("This goes to both console and file")
    logger.warning(" This goes to both ")
    logger.critical("This is a critical system error")

    logger.info("Calling code from common")
    utils.alllow_access()
