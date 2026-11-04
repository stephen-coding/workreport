# Build the Agent Core into a single-file executable with PyInstaller.
# 使用 PyInstaller 将 Agent Core 构建为单文件可执行程序。
#
# Usage: powershell -File scripts/build_core.ps1 [-Python python]

param(
    [string]$Python = "python"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location (Join-Path $root "core")
try {
    & $Python -m pip install --upgrade "pyinstaller>=6.3"
    if ($LASTEXITCODE -ne 0) { throw "failed to install pyinstaller" }

    & $Python -m PyInstaller --clean --noconfirm packaging/agent-core.spec
    if ($LASTEXITCODE -ne 0) { throw "pyinstaller build failed" }

    $exe = Join-Path $root "core/dist/agent-core"
    if ($IsWindows -or $env:OS -eq "Windows_NT") { $exe += ".exe" }
    Write-Host "Built: $exe"
    Write-Host "Packaged Core is picked up automatically via AGENT_CORE_EXE or core/dist."
}
finally {
    Pop-Location
}
