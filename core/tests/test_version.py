"""Version resolution stays single-sourced across CLI, hello frame and MCP clientInfo."""

import os
import unittest
from unittest import mock

from agent_core import __version__, _resolve_version
from agent_core.protocol import CORE_VERSION


class VersionTest(unittest.TestCase):
    def test_env_var_wins(self):
        with mock.patch.dict(os.environ, {"AGENT_CORE_VERSION": "9.9.9"}):
            self.assertEqual(_resolve_version(), "9.9.9")

    def test_blank_env_falls_through(self):
        # An empty injected value must not wipe the version; source checkouts fall
        # back to the dev marker instead of an empty string.
        with mock.patch.dict(os.environ, {"AGENT_CORE_VERSION": "   "}):
            self.assertTrue(_resolve_version().strip())

    def test_protocol_reuses_package_version(self):
        self.assertEqual(CORE_VERSION, __version__)


if __name__ == "__main__":
    unittest.main()
