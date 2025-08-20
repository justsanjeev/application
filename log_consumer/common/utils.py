from __future__ import annotations

from qiutils.logger import log_manager

# Basic usage - gets logger with module name automatically
logger = log_manager.get_logger(__name__)

def alllow_access():
  logger.debug("Debug from common")
  logger.info("INFO - from common")
