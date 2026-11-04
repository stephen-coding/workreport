"""Module entry point: ``python -m agent_core --project-id ... --data-dir ...``."""

import argparse
import asyncio
import json
import logging
import os
import sys

from agent_core.rpc.server import RpcServer


def _configure_streams() -> None:
    # Ensure UTF-8 on stdio regardless of the platform default codepage.
    for stream in (sys.stdin, sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass


def main() -> None:
    parser = argparse.ArgumentParser(prog="agent_core")
    parser.add_argument("--project-id", default="default")
    parser.add_argument(
        "--data-dir",
        default=os.path.join(os.path.expanduser("~"), ".agent", "data"),
    )
    parser.add_argument("--log-level", default="INFO")
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
    logging.basicConfig(
        level=getattr(logging, args.log_level.upper(), logging.INFO),
        stream=sys.stderr,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )

    server = RpcServer(project_id=args.project_id, data_dir=args.data_dir)
    try:
        asyncio.run(server.run())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
