//! tree-sitter backed structural outlines.
//!
//! Only outlines are exposed (name, header line, line range). Tree-sitter
//! query support is a future extension; the Python fallback returns an empty
//! list for `query()` so callers already handle its absence.

use pyo3::exceptions::PyValueError;
use pyo3::prelude::*;
use pyo3::types::PyModule;
use tree_sitter::{Language, Node, Parser};

/// Node kinds that introduce a callable across the bundled grammars.
const FUNCTION_KINDS: &[&str] = &[
    "function_definition",     // python, c
    "function_item",           // rust
    "function_declaration",    // javascript, typescript, go
    "generator_function_declaration",
    "method_definition",       // javascript, typescript
    "method_declaration",      // java, c#
    "constructor_declaration", // java, c#
];

/// Node kinds that scope the names of everything nested inside them.
const TYPE_KINDS: &[&str] = &[
    "class_definition",     // python
    "class_declaration",    // javascript, typescript, java
    "interface_declaration", // java, typescript
    "impl_item",            // rust
];

fn language_for(name: &str) -> Option<Language> {
    Some(match name {
        "python" => Language::new(tree_sitter_python::LANGUAGE),
        "rust" => Language::new(tree_sitter_rust::LANGUAGE),
        "javascript" => Language::new(tree_sitter_javascript::LANGUAGE),
        "typescript" => Language::new(tree_sitter_typescript::LANGUAGE_TYPESCRIPT),
        "tsx" => Language::new(tree_sitter_typescript::LANGUAGE_TSX),
        "java" => Language::new(tree_sitter_java::LANGUAGE),
        "go" => Language::new(tree_sitter_go::LANGUAGE),
        _ => return None,
    })
}

#[pyclass(module = "agent_core_native")]
#[derive(Clone)]
pub struct FunctionNode {
    #[pyo3(get)]
    pub name: String,
    #[pyo3(get)]
    pub signature: String,
    #[pyo3(get)]
    pub start_line: usize,
    #[pyo3(get)]
    pub end_line: usize,
    #[pyo3(get)]
    pub language: String,
}

#[pyclass(module = "agent_core_native")]
pub struct ParsedFile {
    #[pyo3(get)]
    pub language: String,
    #[pyo3(get)]
    pub functions: Vec<FunctionNode>,
}

#[pymethods]
impl ParsedFile {
    /// Tree-sitter queries are not implemented yet; keeps the API compatible
    /// with the pure-Python fallback.
    fn query(&self, _s_expr: &str) -> Vec<FunctionNode> {
        Vec::new()
    }
}

fn header_line(node: Node<'_>, source: &str) -> String {
    let start = node.start_byte();
    let end = node.end_byte().min(source.len());
    if start >= end {
        return String::new();
    }
    let text = &source[start..end];
    text.lines()
        .next()
        .unwrap_or("")
        .trim()
        .chars()
        .take(160)
        .collect()
}

fn node_name(node: Node<'_>, source: &str) -> String {
    node.child_by_field_name("name")
        .or_else(|| node.child_by_field_name("type"))
        .and_then(|child| child.utf8_text(source.as_bytes()).ok())
        .map(|text| text.trim().to_string())
        .filter(|text| !text.is_empty())
        .unwrap_or_else(|| "<anonymous>".to_string())
}

fn to_function(node: Node<'_>, source: &str, language: &str, name: String) -> FunctionNode {
    FunctionNode {
        name,
        signature: header_line(node, source),
        start_line: node.start_position().row + 1,
        end_line: node.end_position().row + 1,
        language: language.to_string(),
    }
}

/// Depth-first walk that keeps two invariants of the Python fallback:
/// names are qualified with the enclosing type (`Greeter.hello`) and type
/// declarations themselves show up as outline entries.
fn collect(node: Node<'_>, source: &str, language: &str, prefix: &str, out: &mut Vec<FunctionNode>) {
    let mut cursor = node.walk();
    for child in node.children(&mut cursor) {
        let kind = child.kind();
        let mut child_prefix = prefix.to_string();
        if TYPE_KINDS.contains(&kind) {
            let name = node_name(child, source);
            out.push(FunctionNode {
                name: format!("{}{}", prefix, name),
                signature: "class".to_string(),
                start_line: child.start_position().row + 1,
                end_line: child.end_position().row + 1,
                language: language.to_string(),
            });
            child_prefix = format!("{}{}.", prefix, name);
        } else if FUNCTION_KINDS.contains(&kind) {
            let name = format!("{}{}", prefix, node_name(child, source));
            out.push(to_function(child, source, language, name));
        }
        collect(child, source, language, &child_prefix, out);
    }
}

#[pyfunction]
#[pyo3(signature = (path, content = None))]
pub fn detect_language(path: &str, content: Option<&str>) -> Option<String> {
    if let Some(extension) = std::path::Path::new(path)
        .extension()
        .and_then(|ext| ext.to_str())
    {
        let language = match extension.to_ascii_lowercase().as_str() {
            "py" | "pyi" => Some("python"),
            "rs" => Some("rust"),
            "js" | "jsx" | "mjs" | "cjs" => Some("javascript"),
            "ts" | "tsx" => Some("typescript"),
            "java" => Some("java"),
            "go" => Some("go"),
            _ => None,
        };
        if let Some(language) = language {
            return Some(language.to_string());
        }
    }

    if let Some(text) = content {
        if text.starts_with("#!") {
            let first = text.lines().next().unwrap_or("");
            if first.contains("python") {
                return Some("python".to_string());
            }
            if first.contains("node") {
                return Some("javascript".to_string());
            }
        }
    }
    None
}

#[pyfunction]
pub fn parse_text(content: &str, language: &str) -> PyResult<ParsedFile> {
    let grammar = language_for(language)
        .ok_or_else(|| PyValueError::new_err(format!("unsupported language: {language}")))?;
    let mut parser = Parser::new();
    parser
        .set_language(&grammar)
        .map_err(|err| PyValueError::new_err(err.to_string()))?;
    let tree = parser
        .parse(content, None)
        .ok_or_else(|| PyValueError::new_err("tree-sitter failed to parse the source"))?;

    let mut functions = Vec::new();
    collect(tree.root_node(), content, language, "", &mut functions);
    functions.sort_by_key(|function| function.start_line);
    Ok(ParsedFile {
        language: language.to_string(),
        functions,
    })
}

pub fn register(m: &Bound<'_, PyModule>) -> PyResult<()> {
    m.add_class::<FunctionNode>()?;
    m.add_class::<ParsedFile>()?;
    m.add_function(wrap_pyfunction!(detect_language, m)?)?;
    m.add_function(wrap_pyfunction!(parse_text, m)?)?;
    Ok(())
}
