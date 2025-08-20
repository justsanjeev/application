from __future__ import annotations

import logging

from qiutils.logger.log import initialize_logging

# logger instance
logger = logging.getLogger(__name__)

# Initialization of the handler
handlers = initialize_logging(
    log=logger, log_name="application.log", timestamp=True, color=True
)

def alllow_access() -> None:
  logger.debug("Debug from common")
  logger.info("INFO - from common")
