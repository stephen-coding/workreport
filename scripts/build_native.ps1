# Build the optional Rust accelerator and stage it next to the Python sources.
# 构建可选的 Rust 加速扩展，并放到 core/src 下供 agent_core.native 自动加载。
#
# Usage: powershell -File scripts/build_native.ps1 [-Toolchain name] [-Python python] [-Force]
#
# Windows 上优先使用 GNU 工具链（MinGW-w64）。原因：PyO3 的 abi3 模式在
# windows-gnu 上只链接 python3.dll，而该转发层并不导出 PyCMethod_New；因此
# 这里按当前解释器版本链接，扩展随 Core 一起分发。
# Linux / macOS 默认启用 abi3，不依赖 libpython 的链接期存在。
#
# 注意：本文件的运行时输出保持 ASCII，Windows PowerShell 5.1 会按 ANSI
# 读取无 BOM 的脚本，中文只放在注释里。

param(
    [string]$Toolchain = "",
    [string]$Python = "python",
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$nativeDir = Join-Path $root "core/native"
$srcDir = Join-Path $root "core/src"

function Resolve-Cargo {
    $cmd = Get-Command cargo -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $candidate = Join-Path $env:USERPROFILE ".cargo\bin\cargo.exe"
    if (Test-Path $candidate) { return $candidate }
    throw "cargo not found: install the Rust toolchain from https://rustup.rs"
}

function Resolve-Rustup {
    $cmd = Get-Command rustup -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $candidate = Join-Path $env:USERPROFILE ".cargo\bin\rustup.exe"
    if (Test-Path $candidate) { return $candidate }
    return $null
}

function Resolve-MinGW {
    $cmd = Get-Command gcc -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $base = Join-Path $env:LOCALAPPDATA "Microsoft\WinGet\Packages"
    if (Test-Path $base) {
        $gcc = Get-ChildItem $base -Filter gcc.exe -Recurse -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($gcc) { return $gcc.FullName }
    }
    return $null
}

$cargo = Resolve-Cargo
Write-Host "cargo: $cargo"

$isWindows = $IsWindows -or $env:OS -eq "Windows_NT"
$features = "tokenizer,codesoup"
$cargoArgs = @("build", "--release")
$envVars = @{}

if ($isWindows) {
    $gcc = Resolve-MinGW
    if (-not $gcc) {
        throw "MinGW-w64 gcc not found. Run: winget install BrechtSanders.WinLibs.POSIX.UCRT (or install the VS C++ build tools)"
    }
    Write-Host "gcc:   $gcc"
    $envVars["PATH"] = (Split-Path $gcc) + ";" + (Split-Path $cargo) + ";" + $env:PATH
    if (-not $Toolchain) { $Toolchain = "stable-x86_64-pc-windows-gnu" }
    $cargoArgs += "--no-default-features"
}
else {
    $features = "abi3," + $features
    if (-not $Toolchain) { $Toolchain = "stable" }
}

$installed = @()
$rustup = Resolve-Rustup
if ($rustup) {
    $installed = & $rustup toolchain list
}
$hasToolchain = @($installed | Where-Object { $_ -match [regex]::Escape($Toolchain) }).Count -gt 0
if (-not $hasToolchain) {
    if (-not $rustup) { throw "toolchain $Toolchain is missing and rustup is unavailable" }
    Write-Host "installing toolchain $Toolchain ..."
    & $rustup toolchain install $Toolchain
    if ($LASTEXITCODE -ne 0) { throw "rustup toolchain install $Toolchain failed" }
}

$cargoArgs += @("--features", $features)

Push-Location $nativeDir
try {
    $previous = @{}
    foreach ($key in $envVars.Keys) {
        $previous[$key] = [Environment]::GetEnvironmentVariable($key, "Process")
        [Environment]::SetEnvironmentVariable($key, $envVars[$key], "Process")
    }
    try {
        Write-Host "cargo +$Toolchain $($cargoArgs -join ' ')"
        & $cargo "+$Toolchain" @cargoArgs
        if ($LASTEXITCODE -ne 0) { throw "cargo build failed" }
    }
    finally {
        foreach ($key in $previous.Keys) {
            [Environment]::SetEnvironmentVariable($key, $previous[$key], "Process")
        }
    }

    $artifact = Get-ChildItem $nativeDir -Recurse -Include "agent_core_native.dll", "libagent_core_native.so", "libagent_core_native.dylib" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match "[\\/]release[\\/]" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $artifact) { throw "no release artifact found (agent_core_native is missing)" }

    $suffix = if ($artifact.Extension -eq ".dll") { ".pyd" } else { ".so" }
    $target = Join-Path $srcDir ("agent_core_native" + $suffix)

    $fresh = $false
    if ((Test-Path $target) -and -not $Force) {
        $fresh = (Get-Item $target).LastWriteTime -ge $artifact.LastWriteTime
    }
    if ($fresh) {
        Write-Host "up to date: $target"
    }
    else {
        Copy-Item $artifact.FullName $target -Force
        Write-Host "staged: $target"
    }

    & $Python -c "import os,sys; sys.path.insert(0, os.path.abspath(r'$srcDir')); from agent_core.native import native_info; info = native_info(); print('native_info:', info); sys.exit(0 if info['backend'] == 'rust' else 1)"
    if ($LASTEXITCODE -ne 0) { throw "extension built but not loaded: check that the Python version matches the toolchain" }
}
finally {
    Pop-Location
}

Write-Host "Rust accelerator ready. Without it the Core falls back to pure Python."
