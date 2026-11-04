# -*- mode: python ; coding: utf-8 -*-
# PyInstaller spec: builds a single-file ``agent-core`` executable.
# 构建单文件 Agent Core 可执行文件。
#
# Build from the repository root:
#   python -m PyInstaller --clean --noconfirm core/packaging/agent-core.spec
# or with the helper script:
#   powershell -File scripts/build_core.ps1

import os

from PyInstaller.utils.hooks import collect_submodules

PROJECT_ROOT = os.path.abspath(os.path.join(SPECPATH, ".."))
SRC = os.path.join(PROJECT_ROOT, "src")

# Bundled markdown (skills / subagents) is read from disk at runtime.
datas = [
    (os.path.join(SRC, "agent_core", "skills", "builtin"),
     os.path.join("agent_core", "skills", "builtin")),
    (os.path.join(SRC, "agent_core", "agent", "subagents_builtin"),
     os.path.join("agent_core", "agent", "subagents_builtin")),
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
