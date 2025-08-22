from __future__ import annotations

from qiutils.logger import jenkins_logger

# Basic usage - gets logger with module name automatically
logger = jenkins_logger.get_logger(__name__)

def alllow_access():
  logger.debug("Debug from common")
  logger.info("INFO - from common")
