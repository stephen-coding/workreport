# agent-core-native

Optional PyO3 accelerators for the Agent Core:

| module | backend | fallback when missing |
|---|---|---|
| `native.tokenizer` | `tiktoken-rs` BPE counting + budget truncation | character/4 heuristic in `agent_core/native/tokenizer.py` |
| `native.codesoup` | `tree-sitter` outlines (python, rust, js, ts, java, go) | `ast` + regex parser in `agent_core/native/codesoup.py` |

The Python facade (`core/src/agent_core/native/__init__.py`) imports this module in a
`try/except` block, so a missing or broken build **only costs performance**.

## Build

```powershell
# from the repository root
powershell -File scripts/build_native.ps1
```

The script locates `cargo` (PATH or `%USERPROFILE%\.cargo\bin`), locates a C linker,
builds with `--features tokenizer,codesoup`, then stages the artifact as
`core/src/agent_core_native.pyd` (Windows) or `.so` (Linux/macOS).

Manual equivalent:

```powershell
cd core/native
cargo +stable-x86_64-pc-windows-gnu build --release --no-default-features --features tokenizer,codesoup
copy target\release\agent_core_native.dll ..\src\agent_core_native.pyd
```

```bash
cd core/native
cargo build --release --features abi3,tokenizer,codesoup
cp target/release/libagent_core_native.so ../src/agent_core_native.so
```

## Toolchain notes

- **Windows**: needs a C linker. Either MinGW-w64
  (`winget install BrechtSanders.WinLibs.POSIX.UCRT`, then use the
  `stable-x86_64-pc-windows-gnu` toolchain) or the MSVC C++ build tools.
- **abi3 caveat**: on `windows-gnu`, PyO3's `abi3` feature links only
  `python3.dll`, whose forwarder table does not export `PyCMethod_New`, so the link
  fails. `abi3` is therefore an **opt-in** feature (`--features abi3`); Windows
  builds link the concrete interpreter and the module is shipped with the Core.
- The module must be built with the same Python version that runs the Core.
  `scripts/build_core.ps1` (PyInstaller) picks up the staged module automatically.

## Verify

```powershell
python -m agent_core --native-info        # {"backend": "rust", "tokenizer": "rust", "codesoup": "rust"}
powershell -File scripts/verify_all.ps1 -Native   # builds, then runs every suite expecting rust
```
