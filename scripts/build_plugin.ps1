# Build an installable IntelliJ plugin zip that bundles the Agent Core.
# 构建可直接安装的 IntelliJ 插件 zip（内置 Agent Core 可执行文件，免 Python 运行）。
#
# Usage: powershell -File scripts/build_plugin.ps1 [-SkipNative] [-SkipCore] [-Python python]
#
# 运行时输出保持 ASCII：Windows PowerShell 5.1 会按 ANSI 读取无 BOM 的脚本。

param(
    [switch]$SkipNative,
    [switch]$SkipCore,
    [string]$Python = "python"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    $cargo = Get-Command cargo -ErrorAction SilentlyContinue
    if (-not $cargo) {
        $fallback = Join-Path $env:USERPROFILE ".cargo\bin\cargo.exe"
        if (Test-Path $fallback) { $cargo = Get-Item $fallback }
    }

    if (-not $SkipNative) {
        if ($cargo) {
            Write-Host "== build native =="
            & powershell -NoProfile -File (Join-Path $root "scripts/build_native.ps1")
            if ($LASTEXITCODE -ne 0) { throw "build_native.ps1 failed" }
        }
        else {
            Write-Host "cargo not found: skipping the Rust accelerator (pure Python fallback stays active)"
        }
    }

    if (-not $SkipCore) {
        Write-Host "== build core executable =="
        & powershell -NoProfile -File (Join-Path $root "scripts/build_core.ps1") -Python $Python
        if ($LASTEXITCODE -ne 0) { throw "build_core.ps1 failed" }
    }

    $coreExe = Get-ChildItem (Join-Path $root "core/dist") -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -eq "agent-core" -or $_.Name -eq "agent-core.exe" } |
        Select-Object -First 1
    if (-not $coreExe) {
        throw "core/dist/agent-core* is missing: the plugin zip would ship without a Core (run without -SkipCore)"
    }

    Write-Host "== mvn package =="
    & mvn -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "mvn package failed" }

    $zip = Get-ChildItem (Join-Path $root "target") -Filter "*.zip" -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $zip) { throw "no plugin zip produced under target/" }

    $sizeMb = [math]::Round($zip.Length / 1MB, 1)
    Write-Host ""
    Write-Host "plugin zip: $($zip.FullName) ($sizeMb MB)"
    Write-Host "install: Settings > Plugins > gear > Install Plugin from Disk..."
    Write-Host "the Core executable is bundled at agent-core/ inside the zip, no Python needed."
}
finally {
    Pop-Location
}
