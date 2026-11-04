# Compatibility matrix smoke: Core tests + all protocol smokes + IntelliJ + VS Code.
# 兼容矩阵冒烟：Core 单测 + 全部协议冒烟 + IntelliJ 编译 + VS Code 编译/冒烟。
#
# Usage: powershell -File scripts/verify_all.ps1 [-SkipJava] [-SkipNode] [-Native]

param(
    [switch]$SkipJava,
    [switch]$SkipNode,
    [switch]$Native
)

$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
$failures = New-Object System.Collections.Generic.List[string]

function Invoke-Step {
    param([string]$Name, [scriptblock]$Block)
    Write-Host ""
    Write-Host "== $Name ==" -ForegroundColor Cyan
    & $Block
    if ($LASTEXITCODE -ne 0) {
        $script:failures.Add($Name)
        Write-Host "FAILED: $Name" -ForegroundColor Red
    }
    else {
        Write-Host "OK: $Name" -ForegroundColor Green
    }
}

Push-Location (Join-Path $root "core")
try {
    if ($Native) {
        Invoke-Step "build native" { powershell -NoProfile -File (Join-Path $root "scripts/build_native.ps1") }
        # Fail loudly if the accelerator silently fell back to pure Python.
        $env:AGENT_CORE_EXPECT_RUST = "1"
    }
    Invoke-Step "core unit tests" { python -m unittest discover -s tests }
    Invoke-Step "smoke tools" { python scripts/smoke_tools.py }
    Invoke-Step "smoke memory" { python scripts/smoke_memory.py }
    Invoke-Step "smoke mcp" { python scripts/smoke_mcp.py }
    Invoke-Step "smoke subagent" { python scripts/smoke_subagent.py }
}
finally {
    Pop-Location
}

if (-not $SkipNode) {
    Push-Location (Join-Path $root "vscode")
    try {
        if (-not (Test-Path "node_modules")) {
            Invoke-Step "vscode npm install" { npm install --no-audit --no-fund }
        }
        Invoke-Step "vscode compile" { npm run compile }
        Invoke-Step "vscode rpc smoke" { npm run smoke }
    }
    finally {
        Pop-Location
    }
}

if (-not $SkipJava) {
    Push-Location $root
    try {
        Invoke-Step "intellij compile" { mvn -q -DskipTests compile }
    }
    finally {
        Pop-Location
    }
}

Write-Host ""
if ($failures.Count -gt 0) {
    Write-Host "FAILED STEPS:" -ForegroundColor Red
    $failures | ForEach-Object { Write-Host " - $_" -ForegroundColor Red }
    exit 1
}
Write-Host "VERIFY ALL PASS" -ForegroundColor Green
exit 0
