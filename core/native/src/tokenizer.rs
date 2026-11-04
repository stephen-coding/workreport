//! tiktoken-rs backed token counting and budget truncation.

use std::sync::OnceLock;

use pyo3::exceptions::PyValueError;
use pyo3::prelude::*;
use pyo3::types::PyModule;
use tiktoken_rs::{cl100k_base, o200k_base, CoreBPE};

static O200K: OnceLock<CoreBPE> = OnceLock::new();
static CL100K: OnceLock<CoreBPE> = OnceLock::new();

const SENTENCE_ENDINGS: [char; 10] = ['。', '！', '？', '；', '.', '!', '?', ';', '\n', '\r'];
const TRUNCATION_MARKER: &str = "\n...(已截断)";

fn uses_cl100k(model: &str) -> bool {
    let m = model.to_ascii_lowercase();
    m.starts_with("gpt-3.5")
        || m.starts_with("gpt-4-")
        || m.starts_with("text-embedding")
        || m.contains("cl100k")
}

fn bpe_for(model: &str) -> PyResult<&'static CoreBPE> {
    if uses_cl100k(model) {
        if CL100K.get().is_none() {
            let bpe = cl100k_base().map_err(|e| PyValueError::new_err(e.to_string()))?;
            let _ = CL100K.set(bpe);
        }
        return CL100K
            .get()
            .ok_or_else(|| PyValueError::new_err("cl100k_base initialisation failed"));
    }
    if O200K.get().is_none() {
        let bpe = o200k_base().map_err(|e| PyValueError::new_err(e.to_string()))?;
        let _ = O200K.set(bpe);
    }
    O200K
        .get()
        .ok_or_else(|| PyValueError::new_err("o200k_base initialisation failed"))
}

#[pyfunction]
#[pyo3(signature = (text, model = "gpt-4o"))]
pub fn count_tokens(py: Python<'_>, text: &str, model: &str) -> PyResult<usize> {
    if text.is_empty() {
        return Ok(0);
    }
    let bpe = bpe_for(model)?;
    Ok(py.allow_threads(|| bpe.encode_with_special_tokens(text).len()))
}

#[pyfunction]
#[pyo3(signature = (text, budget_tokens, model = "gpt-4o"))]
pub fn truncate_to_budget(py: Python<'_>, text: &str, budget_tokens: usize, model: &str) -> PyResult<String> {
    if text.is_empty() || budget_tokens == 0 {
        return Ok(String::new());
    }
    let bpe = bpe_for(model)?;
    if py.allow_threads(|| bpe.encode_with_special_tokens(text).len()) <= budget_tokens {
        return Ok(text.to_string());
    }
    // The marker is appended afterwards, so it must fit inside the budget.
    let marker_tokens = bpe.encode_with_special_tokens(TRUNCATION_MARKER).len();
    let effective = budget_tokens.saturating_sub(marker_tokens);
    if effective == 0 {
        return Ok(TRUNCATION_MARKER.trim_start().to_string());
    }

    let boundaries: Vec<usize> = text
        .char_indices()
        .map(|(index, _)| index)
        .skip(1)
        .chain(std::iter::once(text.len()))
        .collect();

    let (mut low, mut high) = (0usize, boundaries.len() - 1);
    while low < high {
        let mid = (low + high + 1) / 2;
        let end = boundaries[mid];
        let tokens = py.allow_threads(|| bpe.encode_with_special_tokens(&text[..end]).len());
        if tokens <= effective {
            low = mid;
        } else {
            high = mid - 1;
        }
    }

    let mut cut = boundaries[low];
    let head = &text[..cut];
    if let Some((index, ch)) = head
        .char_indices()
        .rev()
        .find(|(_, c)| SENTENCE_ENDINGS.contains(c))
    {
        if (index as f64) >= (cut as f64) * 0.6 {
            cut = index + ch.len_utf8();
        }
    }

    Ok(format!("{}{}", text[..cut].trim_end(), TRUNCATION_MARKER))
}

pub fn register(m: &Bound<'_, PyModule>) -> PyResult<()> {
    m.add_function(wrap_pyfunction!(count_tokens, m)?)?;
    m.add_function(wrap_pyfunction!(truncate_to_budget, m)?)?;
    Ok(())
}
