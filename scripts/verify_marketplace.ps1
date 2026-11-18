# Run the JetBrains Plugin Verifier against the built zip.
# 用 JetBrains Plugin Verifier 校验安装包（主要用来验证 since-build=252 是否真的可用）。
#
# Runtime output stays ASCII: Windows PowerShell 5.1 reads BOM-less scripts as ANSI.
#
# Usage:
#   powershell -File scripts/verify_marketplace.ps1                      # default: IC-2025.2 + IC-2026.3
#   powershell -File scripts/verify_marketplace.ps1 -Ide IC-2025.2
#   powershell -File scripts/verify_marketplace.ps1 -Download            # fetch the verifier jar first
#
# The IDE distributions (~1.2 GB each) are downloaded by the verifier on first use and cached
# under %USERPROFILE%\.marketplace-verifier\ides.

param(
    [string[]]$Ide = @("IC-2025.2", "IC-2026.3"),
    [string]$PluginZip = "",
    [string]$Version = "1.411",
    [string]$VerifierJar = "",
    [switch]$Download
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    if (-not $PluginZip) {
        $latest = Get-ChildItem (Join-Path $root "target") -Filter "workreport-ai-assistant-*.zip" -File |
            Where-Object { $_.Name -notlike "*-signed.zip" } |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if (-not $latest) { throw "no plugin zip under target/: run scripts/build_plugin.ps1 first" }
        $PluginZip = $latest.FullName
    }

    if (-not $VerifierJar) {
        $toolDir = Join-Path $env:USERPROFILE ".marketplace-verifier"
        $VerifierJar = Join-Path $toolDir "verifier-cli-all.jar"
        if ($Download -and -not (Test-Path $VerifierJar)) {
            New-Item -ItemType Directory -Force -Path $toolDir | Out-Null
            $url = "https://github.com/JetBrains/intellij-plugin-verifier/releases/download/$Version/verifier-cli-$Version-all.jar"
            Write-Host "downloading $url"
            Invoke-WebRequest -Uri $url -OutFile $VerifierJar
        }
    }
    if (-not (Test-Path $VerifierJar)) {
        throw @"
verifier jar not found: $VerifierJar
Fetch it once (or pass -Download):
  https://github.com/JetBrains/intellij-plugin-verifier/releases
"@
    }

    $reports = Join-Path $root "target/verifier"
    New-Item -ItemType Directory -Force -Path $reports | Out-Null

    Write-Host "plugin : $PluginZip"
    $failed = New-Object System.Collections.Generic.List[string]
    foreach ($ide in $Ide) {
        $safe = $ide -replace '[^A-Za-z0-9._-]', '_'
        $report = Join-Path $reports $safe
        if (Test-Path $report) { Remove-Item -Recurse -Force $report }
        Write-Host ""
        Write-Host "== $ide =="
        # -runtime-dir keeps downloaded IDEs out of the project; the verifier caches them.
        & java -jar $VerifierJar verify-plugin -plugin $PluginZip -ide $ide -verification-reports $report
        if ($LASTEXITCODE -ne 0) { $failed.Add($ide) }
    }

    Write-Host ""
    if ($failed.Count -eq 0) {
        Write-Host "OK: no verification problems reported. HTML reports: $reports"
    }
    else {
        Write-Host "FAILED: $($failed -join ', ') - see $reports"
        exit 1
    }
}
finally {
    Pop-Location
}
