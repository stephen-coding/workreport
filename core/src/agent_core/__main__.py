"""Module entry point: ``python -m agent_core --project-id ... --data-dir ...``."""

import argparse
import asyncio
import json
import logging
import os
import sys
import time
from logging.handlers import RotatingFileHandler
from typing import List

from agent_core import __version__
from agent_core.rpc.server import RpcServer
from agent_core.usage import default_log_dir


def _configure_streams() -> None:
    # Ensure UTF-8 on stdio regardless of the platform default codepage.
    for stream in (sys.stdin, sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass


def _default_log_file() -> str:
    # Same directory as usage.jsonl, so AGENT_LOG_DIR moves both.
    # 与 usage.jsonl 同目录，AGENT_LOG_DIR 一并挪走。
    return os.path.join(default_log_dir(), "core-%s.log" % time.strftime("%Y%m%d"))


def _configure_logging(level: str, log_file: str) -> None:
    """Keep stderr for the IDE console and mirror it into a rotating file.

    The IDE console scrolls away, which used to make it impossible to review a
    failure after the fact. The file is capped so a long-running session cannot
    fill the disk.
    控制台会滚走，失败细节事后无法回溯；文件按月分日、每段5MB、最多5份。
    """
    handlers: List[logging.Handler] = [logging.StreamHandler(sys.stderr)]
    if log_file:
        directory = os.path.dirname(log_file)
        if directory:
            os.makedirs(directory, exist_ok=True)
        handlers.append(RotatingFileHandler(log_file, maxBytes=5 * 1024 * 1024,
                                            backupCount=5, encoding="utf-8"))
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        handlers=handlers,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )


def main() -> None:
    parser = argparse.ArgumentParser(prog="agent_core")
    parser.add_argument("--project-id", default="default")
    parser.add_argument(
        "--data-dir",
        default=os.path.join(os.path.expanduser("~"), ".agent", "data"),
    )
    parser.add_argument("--log-level", default="INFO")
    parser.add_argument(
        "--log-file",
        default=None,
        help="append logs to this file (default ~/.agent/logs/core-YYYYMMDD.log)",
    )
    parser.add_argument(
        "--no-log-file",
        action="store_true",
        help="do not write a log file",
    )
    parser.add_argument(
        "--version",
        action="version",
        version="agent-core %s" % __version__,
        help="print the Core version and exit",
    )
    parser.add_argument(
        "--native-info",
        action="store_true",
        help="print the accelerator backend as JSON and exit",
    )
    args = parser.parse_args()

    if args.native_info:
        from agent_core.native import native_info

        print(json.dumps(native_info(), ensure_ascii=False))
        return

    _configure_streams()
    log_file = "" if args.no_log_file else (args.log_file or _default_log_file())
    _configure_logging(args.log_level, log_file)

    server = RpcServer(project_id=args.project_id, data_dir=args.data_dir)
    try:
        asyncio.run(server.run())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
