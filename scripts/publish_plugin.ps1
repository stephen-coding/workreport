# Publish the plugin zip to JetBrains Marketplace: version check -> build -> sign -> upload.
# 发布到 JetBrains Marketplace：版本校验 -> 打包 -> 签名 -> 上传。
#
# Runtime output stays ASCII: Windows PowerShell 5.1 reads BOM-less scripts as ANSI.
#
# Usage:
#   $env:JB_MARKETPLACE_TOKEN = "<permanent token>"
#   powershell -File scripts/publish_plugin.ps1 -PluginId 12345
#   powershell -File scripts/publish_plugin.ps1 -SkipBuild -DryRun
#
# First release: upload once in the web UI (it creates the plugin entry and gives you the
# numeric plugin id), then use -PluginId for every later version.
# Token page: https://plugins.jetbrains.com/author/me/tokens

param(
    [string]$Token = $env:JB_MARKETPLACE_TOKEN,
    [int]$PluginId = 0,
    [ValidateSet("default", "beta", "eap")]
    [string]$Channel = "default",
    [switch]$SkipBuild,
    [switch]$SkipSign,
    [switch]$DryRun,
    [string]$SignerJar = "",
    [string]$KeyDir = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    # ---------------------------------------------------------------- 1. versions
    $pom = [xml](Get-Content -Raw (Join-Path $root "pom.xml"))
    $pomVersion = $pom.project.version

    $pluginXmlPath = Join-Path $root "src/main/resources/META-INF/plugin.xml"
    $pluginXml = [xml](Get-Content -Raw $pluginXmlPath)
    $pluginVersion = $pluginXml."idea-plugin".version
    $pluginName = $pluginXml."idea-plugin".name
    $pluginKey = $pluginXml."idea-plugin".id

    Write-Host "plugin : $pluginName ($pluginKey)"
    Write-Host "version: plugin.xml=$pluginVersion  pom.xml=$pomVersion"
    if ($pomVersion -ne $pluginVersion) {
        throw "version mismatch: bump both pom.xml and META-INF/plugin.xml before releasing"
    }

    # ---------------------------------------------------------------- 2. build
    $zip = Join-Path $root "target/workreport-ai-assistant-$pluginVersion.zip"
    if (-not $SkipBuild) {
        Write-Host ""
        Write-Host "== build =="
        # Core executable is reused from core/dist; rebuild it only when the Core changed.
        & powershell -NoProfile -File (Join-Path $root "scripts/build_plugin.ps1") -SkipNative -SkipCore
        if ($LASTEXITCODE -ne 0) { throw "build_plugin.ps1 failed" }
    }
    if (-not (Test-Path $zip)) {
        $latest = Get-ChildItem (Join-Path $root "target") -Filter "*.zip" -File |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if (-not $latest) { throw "no plugin zip found under target/" }
        $zip = $latest.FullName
        Write-Host "zip    : $zip (fallback: newest)"
    }
    else {
        Write-Host "zip    : $zip"
    }

    # ---------------------------------------------------------------- 3. sign
    $signed = Join-Path $root ("target/workreport-ai-assistant-$pluginVersion-signed.zip")
    if ($SkipSign) {
        Write-Host ""
        Write-Host "== sign: skipped (-SkipSign); IDE will warn about an unsigned plugin =="
        $upload = $zip
    }
    else {
        if (-not $SignerJar) {
            $SignerJar = Join-Path $env:USERPROFILE ".marketplace-signer/marketplace-zip-signer-cli.jar"
        }
        if (-not (Test-Path $SignerJar)) {
            throw @"
marketplace-zip-signer-cli.jar not found: $SignerJar
Download it from https://github.com/JetBrains/marketplace-zip-signer/releases and either
pass -SignerJar <path> or drop it at %USERPROFILE%\.marketplace-signer\.
"@
        }
        if (-not $KeyDir) {
            $KeyDir = Join-Path $env:USERPROFILE ".marketplace-signer/keys"
        }
        $cert = Join-Path $KeyDir "certificate.pem"
        $key = Join-Path $KeyDir "private.pem"
        if (-not (Test-Path $cert) -or -not (Test-Path $key)) {
            throw @"
Signing keys not found in $KeyDir
Create them once (keep private.pem secret, reuse it for every release):
  java -jar "$SignerJar" keygen -out "$KeyDir"
"@
        }
        Write-Host ""
        Write-Host "== sign =="
        & java -jar $SignerJar sign -in $zip -out $signed -cert $cert -key $key
        if ($LASTEXITCODE -ne 0) { throw "signing failed" }
        $upload = $signed
        Write-Host "signed : $upload"
    }

    # ---------------------------------------------------------------- 4. upload
    $uri = "https://plugins.jetbrains.com/api/plugins/upload"
    if ($PluginId -gt 0) {
        $uri = "$uri`?pluginId=$PluginId&channel=$Channel"
    }
    if ($DryRun) {
        Write-Host ""
        Write-Host "== dry run =="
        Write-Host "POST $uri"
        Write-Host "file: $upload"
        return
    }
    if (-not $Token) {
        throw @"
No token. Create a permanent token at https://plugins.jetbrains.com/author/me/tokens
then set it for this shell only:
  `$env:JB_MARKETPLACE_TOKEN = "<token>"
"@
    }

    Write-Host ""
    Write-Host "== upload =="
    Write-Host "POST $uri"
    Add-Type -AssemblyName System.Net.Http
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromMinutes(30)
    $client.DefaultRequestHeaders.Authorization =
        [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $Token)

    $content = [System.Net.Http.MultipartFormDataContent]::new()
    $bytes = [System.IO.File]::ReadAllBytes($upload)
    $filePart = [System.Net.Http.ByteArrayContent]::new($bytes)
    $filePart.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("application/zip")
    $content.Add($filePart, "file", [System.IO.Path]::GetFileName($upload))

    try {
        $response = $client.PostAsync($uri, $content).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            throw "upload failed: HTTP $([int]$response.StatusCode) $($response.ReasonPhrase)`n$body"
        }
        Write-Host "OK: $body"
        Write-Host ""
        Write-Host "Check the build at https://plugins.jetbrains.com/plugin/$PluginId/versions"
        Write-Host "First uploads are reviewed by JetBrains; later versions of a verified plugin publish"
        Write-Host "immediately when 'Auto-publish' is on in the plugin settings."
    }
    finally {
        $content.Dispose()
        $client.Dispose()
    }
}
finally {
    Pop-Location
}
