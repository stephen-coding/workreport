# -*- mode: python ; coding: utf-8 -*-
# PyInstaller spec: builds a single-file ``agent-core`` executable.
# 构建单文件 Agent Core 可执行文件。
#
# Build from the repository root:
#   python -m PyInstaller --clean --noconfirm core/packaging/agent-core.spec
# or with the helper script:
#   powershell -File scripts/build_core.ps1

import json
import os

from PyInstaller.utils.hooks import collect_submodules

PROJECT_ROOT = os.path.abspath(os.path.join(SPECPATH, ".."))
SRC = os.path.join(PROJECT_ROOT, "src")

# Stamp the version into the bundle: the exe must report the same number as the
# plugin even when no environment variable exists at runtime.
# 把版本号写进包内：即使运行时没有环境变量，exe 也要与插件报告同一个版本。
_build_info = os.path.join(PROJECT_ROOT, "build", "core-build-info.json")
os.makedirs(os.path.dirname(_build_info), exist_ok=True)
with open(_build_info, "w", encoding="utf-8") as _fh:
    json.dump({"version": os.environ.get("AGENT_CORE_VERSION", "").strip()}, _fh)

# Bundled markdown (skills / subagents) is read from disk at runtime.
datas = [
    (os.path.join(SRC, "agent_core", "skills", "builtin"),
     os.path.join("agent_core", "skills", "builtin")),
    (os.path.join(SRC, "agent_core", "agent", "subagents_builtin"),
     os.path.join("agent_core", "agent", "subagents_builtin")),
    (_build_info, "."),
]

hiddenimports = (
    collect_submodules("agent_core")
    + collect_submodules("pydantic")
    + ["httpx", "h11", "httpcore", "sqlite3"]
)

# Optional Rust accelerator staged by scripts/build_native.ps1.
binaries = []
for candidate in ("agent_core_native.pyd", "agent_core_native.so"):
    native = os.path.join(SRC, candidate)
    if os.path.exists(native):
        binaries.append((native, "."))

a = Analysis(
    [os.path.join(SRC, "agent_core", "__main__.py")],
    pathex=[SRC],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    runtime_hooks=[],
    excludes=["tkinter", "pytest", "setuptools"],
    noarchive=False,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name="agent-core",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=True,
)
