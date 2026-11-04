"""Tests for the semantic code index: chunking, vector store, incremental build."""

import asyncio
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from agent_core.codeindex.chunker import chunk_file, iter_source_files  # noqa: E402
from agent_core.codeindex.service import CodeIndexService  # noqa: E402
from agent_core.codeindex.store import CodeStore  # noqa: E402
from agent_core.protocol import ChatConfig  # noqa: E402


class FakeEmbedder:
    """Keyword-driven vectors: 'alpha' -> axis 0, anything else -> axis 1."""

    model = "fake-embed"

    async def embed(self, texts):
        vectors = []
        for text in texts:
            vectors.append([1.0, 0.0] if "alpha" in text else [0.0, 1.0])
        return vectors


class IndexFixture(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="agent-index-")
        self.project = os.path.join(self.tmp, "project")
        self.data = os.path.join(self.tmp, "data")
        os.makedirs(os.path.join(self.project, "src"), exist_ok=True)
        self._write("src/alpha.py", "def alpha_handler():\n    return 'alpha value'\n")
        self._write("src/beta.py", "def beta_handler():\n    return 'beta value'\n")
        self._write("node_modules/ignored.js", "function ignored(){ return 1; }\n")
        self.service = CodeIndexService(self.project, self.data, ChatConfig)
        self.service._embedder = lambda: FakeEmbedder()

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _write(self, relative, content):
        path = os.path.join(self.project, relative)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(content)


class TestChunker(IndexFixture):
    def test_skips_ignored_directories(self):
        files = list(iter_source_files(self.project))
        self.assertIn("src/alpha.py", files)
        self.assertNotIn("node_modules/ignored.js", files)

    def test_python_file_yields_function_chunks(self):
        content = "def first():\n    return 1\n\n\ndef second():\n    return 2\n"
        chunks = chunk_file("mod.py", content)
        self.assertTrue(chunks)
        names = {chunk.symbol for chunk in chunks}
        self.assertIn("first", names)

    def test_unknown_language_falls_back_to_windows(self):
        content = "\n".join("line %d" % i for i in range(200))
        chunks = chunk_file("notes.unknown", content)
        self.assertTrue(chunks)
        self.assertEqual(chunks[0].symbol, "")


class TestStore(IndexFixture):
    def test_cosine_search_prefers_matching_vector(self):
        store = CodeStore(os.path.join(self.data, "index.db"))
        chunks_a = chunk_file("src/alpha.py", self._read("src/alpha.py"))
        chunks_b = chunk_file("src/beta.py", self._read("src/beta.py"))
        store.replace_file(self.project, "src/alpha.py", "h1", "python",
                           chunks_a, [[1.0, 0.0] for _ in chunks_a], "fake-embed")
        store.replace_file(self.project, "src/beta.py", "h2", "python",
                           chunks_b, [[0.0, 1.0] for _ in chunks_b], "fake-embed")
        hits = store.search(self.project, [1.0, 0.05], "fake-embed", limit=3)
        self.assertTrue(hits)
        self.assertEqual(hits[0]["path"], "src/alpha.py")

    def test_model_change_returns_no_hits(self):
        store = CodeStore(os.path.join(self.data, "index.db"))
        chunks = chunk_file("src/alpha.py", self._read("src/alpha.py"))
        store.replace_file(self.project, "src/alpha.py", "h1", "python",
                           chunks, [[1.0, 0.0] for _ in chunks], "fake-embed")
        self.assertEqual(store.search(self.project, [1.0, 0.0], "other-model"), [])

    def _read(self, relative):
        with open(os.path.join(self.project, relative), encoding="utf-8") as handle:
            return handle.read()


class TestServiceBuild(IndexFixture):
    def test_build_then_incremental_skips_unchanged(self):
        first = asyncio.run(self.service.build())
        self.assertEqual(first["files"], 2)
        self.assertEqual(first["changed"], 2)
        self.assertGreater(first["chunks"], 0)

        second = asyncio.run(self.service.build())
        self.assertEqual(second["changed"], 0)

        self._write("src/alpha.py", "def alpha_v2():\n    return 'alpha two'\n")
        third = asyncio.run(self.service.build())
        self.assertEqual(third["changed"], 1)

    def test_search_returns_semantic_hit(self):
        asyncio.run(self.service.build())
        hits = asyncio.run(self.service.search("alpha", limit=3))
        self.assertTrue(hits)
        self.assertEqual(hits[0]["path"], "src/alpha.py")

    def test_search_before_build_is_empty(self):
        self.assertEqual(asyncio.run(self.service.search("alpha")), [])

    def test_status_reports_numpy_backend(self):
        asyncio.run(self.service.build())
        status = self.service.status()
        self.assertEqual(status["files"], 2)
        self.assertIn("numpy", status)


if __name__ == "__main__":
    unittest.main()
