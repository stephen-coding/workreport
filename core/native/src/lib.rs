//! PyO3 entry point for the optional Rust accelerators.
//!
//! Modules are feature gated: `tokenizer` (tiktoken BPE counting) is enabled by
//! default, `codesoup` (tree-sitter outlines) is opt-in. The Python side lives
//! in `agent_core.native`, which falls back to pure Python when this module is
//! missing, so a failed build only costs performance, never functionality.

use pyo3::prelude::*;

#[cfg(feature = "tokenizer")]
mod tokenizer;

#[cfg(feature = "codesoup")]
mod codesoup;

#[pymodule]
fn agent_core_native(m: &Bound<'_, PyModule>) -> PyResult<()> {
    m.add("__version__", env!("CARGO_PKG_VERSION"))?;
    #[cfg(feature = "tokenizer")]
    tokenizer::register(m)?;
    #[cfg(feature = "codesoup")]
    codesoup::register(m)?;
    Ok(())
}
