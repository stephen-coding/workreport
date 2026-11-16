// Assert that both shells push the same settings/update field set (ROADMAP §4.5/§6.2).
// 断言两端下发的 settings/update 字段集合完全一致（路线图 §4.5 / §6.2）。

const fs = require("fs");
const path = require("path");

const vscodeRoot = path.resolve(__dirname, "..");
const javaSource = fs.readFileSync(path.resolve(
  vscodeRoot, "..", "src", "main", "java", "com", "workreport", "agent", "rpc", "AgentCoreService.java",
), "utf8");
const tsSource = fs.readFileSync(path.resolve(vscodeRoot, "src", "settings.ts"), "utf8");

// settings.put("key", ...) in pushSettings.
function javaKeys() {
  const keys = new Set();
  for (const match of javaSource.matchAll(/settings\.put\("([a-z_]+)"/g)) {
    keys.add(match[1]);
  }
  return keys;
}

// Object literal entries (4-space indent inside `const settings = {`) plus
// `settings.key = value` assignments added conditionally.
function tsKeys() {
  const keys = new Set();
  for (const match of tsSource.matchAll(/^\s{4}([a-z_]+):/gm)) {
    keys.add(match[1]);
  }
  for (const match of tsSource.matchAll(/settings\.([a-z_]+)\s*=/g)) {
    keys.add(match[1]);
  }
  return keys;
}

function diff(label, left, right) {
  const onlyLeft = [...left].filter((key) => !right.has(key)).sort();
  const onlyRight = [...right].filter((key) => !left.has(key)).sort();
  if (onlyLeft.length) {
    console.error(`FAIL: ${label} only: ${onlyLeft.join(", ")}`);
  }
  if (onlyRight.length) {
    console.error(`FAIL: other side only: ${onlyRight.join(", ")}`);
  }
  return onlyLeft.length === 0 && onlyRight.length === 0;
}

const java = javaKeys();
const ts = tsKeys();
console.log(`IntelliJ fields (${java.size}): ${[...java].sort().join(", ")}`);
console.log(`VS Code fields (${ts.size}): ${[...ts].sort().join(", ")}`);

if (!java.size || !ts.size) {
  console.error("FAIL: could not parse one of the settings payloads");
  process.exit(1);
}
if (!diff("IntelliJ", java, ts)) {
  process.exit(1);
}
console.log("SETTINGS PARITY OK");
