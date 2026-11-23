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
    Invoke-Step "smoke command" { python scripts/smoke_command.py }
    Invoke-Step "smoke todos" { python scripts/smoke_todos.py }
    Invoke-Step "smoke v3" { python scripts/smoke_v3.py }
    Invoke-Step "smoke ask" { python scripts/smoke_ask.py }
    Invoke-Step "smoke team" { python scripts/smoke_team.py }
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
        # The 2026.3 platform is built for JVM 25, so an older JDK cannot read its class files.
        # 2026.3 平台用 Java 25 编译，旧 JDK 读不了它的 class 文件（错误的版本 69.0）。
        # Check the JDK Maven will actually use: JAVA_HOME first, PATH second.
        # 检查 Maven 实际会用的 JDK：优先 JAVA_HOME，其次才是 PATH 里的 javac。
        $javacExe = "javac"
        if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin/javac.exe"))) {
            $javacExe = Join-Path $env:JAVA_HOME "bin/javac.exe"
        }
        $javacVersion = (& $javacExe -version 2>&1) -join " "
        $javacMajor = 0
        if ($javacVersion -match 'javac (\d+)') { $javacMajor = [int]$Matches[1] }
        if ($javacMajor -lt 25) {
            Write-Host "SKIPPED: intellij compile needs JDK 25, current: $javacVersion" -ForegroundColor Yellow
            Write-Host "         set JAVA_HOME to a JDK 25 / JBR 25 before running this script." -ForegroundColor Yellow
            $failures.Add("intellij compile (needs JDK 25, found $javacVersion)")
        }
        else {
            Invoke-Step "intellij compile" { mvn -q -DskipTests compile }
        }
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
