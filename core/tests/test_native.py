"""Tests for the native facade: tokenizer fallback + structural parser."""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from agent_core.agent.compression import compress_tool_output, strip_line_numbers  # noqa: E402
from agent_core.native import (  # noqa: E402
    count_tokens,
    detect_language,
    native_info,
    outline_of,
    parse_text,
    truncate_to_budget,
)


class TestTokenizer(unittest.TestCase):
    """Contract tests that hold for both the heuristic fallback and real tiktoken BPE."""

    def test_empty_text(self):
        self.assertEqual(count_tokens(""), 0)

    def test_ascii_is_cheaper_than_cjk(self):
        # tiktoken merges repeated ASCII runs; the heuristic counts a quarter each.
        # Both agree that same-length CJK costs more.
        self.assertLess(count_tokens("a" * 100), count_tokens("好" * 100))

    def test_short_text_stays_cheap(self):
        self.assertLessEqual(count_tokens("hello world"), 4)

    def test_counting_is_monotonic(self):
        text = "agent core 处理中文与 english 混排。" * 20
        self.assertLessEqual(count_tokens(text[:50]), count_tokens(text))
        self.assertGreaterEqual(count_tokens(text), 20)

    def test_truncate_respects_budget(self):
        text = "第一句话。第二句话。第三句话。" * 100
        budget = 50
        cut = truncate_to_budget(text, budget)
        self.assertLessEqual(count_tokens(cut), budget + 5)
        self.assertTrue(cut.endswith("...(已截断)"))

    def test_truncate_noop_when_within_budget(self):
        self.assertEqual(truncate_to_budget("short", 100), "short")


class TestCodesoup(unittest.TestCase):
    PY_SOURCE = """import os


class Greeter:
    def hello(self, name):
        return "hi " + name


async def main(argv):
    return 0
"""

    RUST_SOURCE = """pub fn alpha(a: i32) -> i32 {
    a + 1
}

fn beta() {
    println!("b");
}
"""

    def test_detect_language_by_extension(self):
        self.assertEqual(detect_language("a/b/c.py", ""), "python")
        self.assertEqual(detect_language("main.rs", ""), "rust")
        self.assertEqual(detect_language("App.tsx", ""), "typescript")
        self.assertIsNone(detect_language("notes.txt", ""))

    def test_detect_language_by_shebang(self):
        self.assertEqual(detect_language("run", "#!/usr/bin/env python3\n"), "python")

    def test_python_outline(self):
        parsed = parse_text(self.PY_SOURCE, "python")
        names = [fn.name for fn in parsed.functions]
        self.assertIn("Greeter", names)
        self.assertIn("Greeter.hello", names)
        self.assertIn("main", names)

    def test_rust_outline_has_line_ranges(self):
        parsed = parse_text(self.RUST_SOURCE, "rust")
        names = [fn.name for fn in parsed.functions]
        self.assertEqual(names, ["alpha", "beta"])
        self.assertEqual(parsed.functions[0].start_line, 1)
        self.assertEqual(parsed.functions[0].end_line, 3)

    def test_unknown_language_has_no_functions(self):
        self.assertEqual(parse_text("whatever", "brainfuck").functions, [])

    def test_outline_of_returns_labelled_lines(self):
        outline = outline_of("sample.py", self.PY_SOURCE)
        self.assertIn("Greeter.hello  [L", outline)


class TestCompression(unittest.TestCase):
    def test_small_output_is_untouched(self):
        self.assertEqual(compress_tool_output("read_file", "ok", "a.py"), "ok")

    def test_line_numbers_are_stripped(self):
        self.assertEqual(strip_line_numbers("1→a\n2→b"), "a\nb")

    def test_large_python_file_gets_outline(self):
        body = "\n".join("def f%d():\n    return %d" % (i, i) for i in range(400))
        content = "\n".join("%d→%s" % (i + 1, line) for i, line in enumerate(body.splitlines()))
        result = compress_tool_output("read_file", content, "big.py", budget=200)
        self.assertIn("结构概览", result)
        self.assertIn("f0 ", result)

    def test_large_plain_text_falls_back_to_truncation(self):
        result = compress_tool_output("read_file", "x" * 20000, "notes.txt", budget=100)
        self.assertTrue(result.endswith("...(已截断)"))


class TestBackendParity(unittest.TestCase):
    """The Rust accelerator must expose the same shape as the Python fallback."""

    def test_detect_language_matches_fallback_table(self):
        from agent_core.native import codesoup as fallback

        for path in ("a.py", "b.rs", "c.ts", "d.tsx", "e.java", "f.go", "g.kt", "h.cpp", "notes.txt"):
            self.assertEqual(
                detect_language(path, ""),
                fallback.detect_language(path, ""),
                msg=path,
            )

    def test_outline_names_match_fallback(self):
        from agent_core.native import codesoup as fallback

        for source, language in (
            (TestCodesoup.PY_SOURCE, "python"),
            (TestCodesoup.RUST_SOURCE, "rust"),
        ):
            names = [fn.name for fn in parse_text(source, language).functions]
            fallback_names = [fn.name for fn in fallback.parse_text(source, language).functions]
            self.assertEqual(names, fallback_names, msg=language)


class TestNativeInfo(unittest.TestCase):
    def test_backend_is_reported(self):
        info = native_info()
        self.assertIn(info["backend"], ("rust", "python"))
        self.assertIn("tokenizer", info)

    def test_rust_backend_loads_when_built(self):
        """After `scripts/build_native.ps1` the accelerator must be picked up."""
        expected = os.environ.get("AGENT_CORE_EXPECT_RUST")
        if not expected:
            self.skipTest("set AGENT_CORE_EXPECT_RUST=1 when the native module is built")
        self.assertEqual(native_info()["backend"], "rust")
        self.assertEqual(native_info()["codesoup"], "rust")


if __name__ == "__main__":
    unittest.main()
