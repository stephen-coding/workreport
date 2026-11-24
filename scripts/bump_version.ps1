# Bump the release version everywhere it is declared, and add the release notes.
# Updates: pom.xml, META-INF/plugin.xml (version + change-notes), CHANGELOG.md,
#          README.md (badge / artifact names / compat table / release table), USER_GUIDE.md.
#
# Usage:
#   powershell -File scripts/bump_version.ps1 -Version 3.3.2
#   powershell -File scripts/bump_version.ps1 -Version 3.3.2 -DryRun
#   powershell -File scripts/bump_version.ps1 -Version 3.3.2 -NotesZh "custom summary"
#
# All Chinese copy and Chinese anchors are loaded at run time from
# scripts/version-notes.zh.txt, so this file stays pure ASCII: Windows PowerShell 5.1
# reads BOM-less scripts with the ANSI code page, and non-ASCII comment text there can
# swallow the following code line.
#
# After bumping: rebuild (scripts/build_plugin.ps1), upload
# (scripts/upload_plugin_local.ps1), then commit.

param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [string]$NotesZh = "",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$report = New-Object System.Collections.ArrayList
$warnings = New-Object System.Collections.ArrayList

function Read-Utf8([string]$path) { return [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8) }
function Write-Utf8([string]$path, [string]$text) { [System.IO.File]::WriteAllText($path, $text, $utf8NoBom) }
function Note([string]$message) { [void]$report.Add($message) }
function Warn([string]$message) { [void]$warnings.Add($message) }

# Replace every occurrence of a literal string; warn when the anchor is missing.
function Replace-All([string]$text, [string]$find, [string]$with, [string]$desc) {
    $count = ([regex]::Matches($text, [regex]::Escape($find))).Count
    if ($count -eq 0) { Warn "anchor not found: $desc"; return $text }
    Note ("{0} ({1}x)" -f $desc, $count)
    return $text.Replace($find, $with)
}

# Insert a block right before the first occurrence of an anchor.
function Insert-Before([string]$text, [string]$anchor, [string]$block, [string]$desc) {
    $i = $text.IndexOf($anchor)
    if ($i -lt 0) { Warn "anchor not found: $desc"; return $text }
    Note $desc
    return $text.Substring(0, $i) + $block + $text.Substring($i)
}

# Insert a block on the line following the first line that starts with a prefix.
function Insert-AfterLine([string]$text, [string]$lineStart, [string]$block, [string]$desc) {
    $i = $text.IndexOf($lineStart)
    if ($i -lt 0) { Warn "line not found: $desc"; return $text }
    $eol = $text.IndexOf("`n", $i)
    if ($eol -lt 0) { Warn "line has no newline: $desc"; return $text }
    Note $desc
    return $text.Substring(0, $eol + 1) + $block + $text.Substring($eol + 1)
}

# ------------------------------------------------------------------ 1. current version
if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw "invalid version '$Version': expected MAJOR.MINOR.PATCH, e.g. 3.3.2"
}

$pomPath = Join-Path $root "pom.xml"
$pluginXmlPath = Join-Path $root "src/main/resources/META-INF/plugin.xml"
$changelogPath = Join-Path $root "CHANGELOG.md"
$readmePath = Join-Path $root "README.md"
$guidePath = Join-Path $root "USER_GUIDE.md"
foreach ($p in @($pomPath, $pluginXmlPath, $changelogPath, $readmePath, $guidePath)) {
    if (-not (Test-Path $p)) { throw "missing file: $p" }
}

$pomText = Read-Utf8 $pomPath
$prev = ([xml]$pomText).project.version
if (-not $prev) {
    $m = [regex]::Match($pomText, '<version>\s*([^<\s]+)\s*</version>')
    if ($m.Success) { $prev = $m.Groups[1].Value }
}
if (-not $prev) { throw "cannot read the current version from pom.xml" }

$pluginXmlBefore = Read-Utf8 $pluginXmlPath
$pluginXmlPrev = ([xml]$pluginXmlBefore)."idea-plugin".version
if ($pluginXmlPrev -ne $prev) {
    throw "pom.xml says $prev but plugin.xml says $pluginXmlPrev -- fix the mismatch first"
}
if ([version]$Version -le [version]$prev) {
    throw "new version $Version must be greater than the current $prev"
}
Write-Host "bump: $prev -> $Version"

# ------------------------------------------------------------------ 2. copy and anchors
$summaryTpl = "{0} maintenance build: same feature set as {0}, version number only."
$bulletTpl = '- **Version sync**: `pom.xml`, `plugin.xml` and the bundled Core are all {0}.'
$rowTpl = '| v{0} | after {1} | Maintenance build: same features as {1}, version number only |'
$compatTpl = ""
$titleTpl = ""
$coverTpl = ""
$reinstallTpl = ""
$coreVerTpl = ""

$notesPath = Join-Path $PSScriptRoot "version-notes.zh.txt"
if (Test-Path $notesPath) {
    $tpl = @([System.IO.File]::ReadAllLines($notesPath, [System.Text.Encoding]::UTF8) |
        Where-Object { $_.Trim().Length -gt 0 })
    if ($tpl.Count -ge 8) {
        $summaryTpl = $tpl[0]
        $bulletTpl = $tpl[1]
        $rowTpl = $tpl[2]
        $compatTpl = $tpl[3]
        $titleTpl = $tpl[4]
        $coverTpl = $tpl[5]
        $reinstallTpl = $tpl[6]
        $coreVerTpl = $tpl[7]
    }
    else {
        Warn "version-notes.zh.txt needs 8 non-empty lines, found $($tpl.Count); Chinese doc anchors are skipped"
    }
}
else {
    Warn "version-notes.zh.txt not found; Chinese doc anchors are skipped"
}

if ($NotesZh) { $summary = $NotesZh } else { $summary = $summaryTpl -f $Version }
$bullet = $bulletTpl -f $Version
$row = $rowTpl -f $Version, $prev
$summaryEn = "Maintenance build of $prev - same feature set, republished under a new version number."

# ------------------------------------------------------------------ 3. rewrite each file
$nl = "`n"
if ($pluginXmlBefore.Contains("`r`n")) { $nl = "`r`n" }

# pom.xml version
$newPom = Replace-All $pomText ("<version>$prev</version>") ("<version>$Version</version>") "pom.xml version"

# plugin.xml version
$newPluginXml = Replace-All $pluginXmlBefore ("<version>$prev</version>") ("<version>$Version</version>") "plugin.xml version"

# plugin.xml change-notes: newest entry goes right after the CDATA opener
$notesBlock = "<h3>$Version</h3>" + $nl + "<ul>" + $nl + "  <li>$summaryEn</li>" + $nl + "</ul>" + $nl +
    "<p>$summary</p>" + $nl + $nl
$cdataAnchor = "<change-notes><![CDATA["
$cdataIdx = $newPluginXml.IndexOf($cdataAnchor)
if ($cdataIdx -ge 0) {
    $cdataEol = $newPluginXml.IndexOf("`n", $cdataIdx)
    if ($cdataEol -ge 0) {
        $newPluginXml = $newPluginXml.Substring(0, $cdataEol + 1) + $notesBlock + $newPluginXml.Substring($cdataEol + 1)
        Note "plugin.xml change-notes entry"
    }
    else { Warn "change-notes opener has no newline" }
}
else { Warn "change-notes CDATA block not found" }

# CHANGELOG: newest section on top
$changelog = Read-Utf8 $changelogPath
$changelogBlock = "## $Version" + $nl + $nl + $summary + $nl + $nl + $bullet + $nl + $nl
$newChangelog = Insert-Before $changelog "## " $changelogBlock "CHANGELOG.md section"

# README: badge, artifact names, compat table, release table title and row
$readme = Read-Utf8 $readmePath
$newReadme = Replace-All $readme "badge/version-$prev-blue.svg" "badge/version-$Version-blue.svg" "README.md badge"
$newReadme = Replace-All $newReadme "workreport-ai-assistant-$prev.zip" "workreport-ai-assistant-$Version.zip" "README.md zip name"
$newReadme = Replace-All $newReadme "workreport-intellij-plugin-$prev.jar" "workreport-intellij-plugin-$Version.jar" "README.md jar name"
if ($compatTpl) {
    $newReadme = Replace-All $newReadme ($compatTpl -f $prev) ($compatTpl -f $Version) "README.md compat table"
}
if ($titleTpl) {
    $newReadme = Replace-All $newReadme ($titleTpl -f $prev) ($titleTpl -f $Version) "README.md release table title"
}
$newReadme = Insert-AfterLine $newReadme "| v$prev |" ($row + $nl) "README.md release table row"

# USER_GUIDE: cover line, zip name, reinstall hint, core version hint
$guide = Read-Utf8 $guidePath
$newGuide = Replace-All $guide "workreport-ai-assistant-$prev.zip" "workreport-ai-assistant-$Version.zip" "USER_GUIDE.md zip name"
if ($coverTpl) {
    $newGuide = Replace-All $newGuide ($coverTpl -f $prev) ($coverTpl -f $Version) "USER_GUIDE.md cover version"
}
if ($reinstallTpl) {
    $newGuide = Replace-All $newGuide ($reinstallTpl -f $prev) ($reinstallTpl -f $Version) "USER_GUIDE.md reinstall hint"
}
if ($coreVerTpl) {
    $newGuide = Replace-All $newGuide ($coreVerTpl -f $prev) ($coreVerTpl -f $Version) "USER_GUIDE.md core version"
}

# ------------------------------------------------------------------ 4. write
$edits = @(
    @{ Path = $pomPath; Text = $newPom; Desc = "pom.xml" },
    @{ Path = $pluginXmlPath; Text = $newPluginXml; Desc = "META-INF/plugin.xml" },
    @{ Path = $changelogPath; Text = $newChangelog; Desc = "CHANGELOG.md" },
    @{ Path = $readmePath; Text = $newReadme; Desc = "README.md" },
    @{ Path = $guidePath; Text = $newGuide; Desc = "USER_GUIDE.md" }
)

$changed = New-Object System.Collections.ArrayList
Write-Host ""
foreach ($e in $edits) {
    if ((Read-Utf8 $e.Path) -eq $e.Text) {
        Write-Host ("unchanged: {0}" -f $e.Desc)
        continue
    }
    if ($DryRun) {
        Write-Host ("would write: {0}" -f $e.Desc)
    }
    else {
        Write-Utf8 $e.Path $e.Text
        Write-Host ("written: {0}" -f $e.Desc)
    }
    [void]$changed.Add($e.Desc)
}

Write-Host ""
foreach ($r in $report) { Write-Host ("  - {0}" -f $r) }
if ($warnings.Count -gt 0) {
    Write-Host ""
    Write-Host "WARNINGS: check these before committing:"
    foreach ($w in $warnings) { Write-Host ("  ! {0}" -f $w) }
}

Write-Host ""
if ($DryRun) {
    Write-Host "dry run: no file was modified."
}
else {
    Write-Host ("done: {0} file(s) updated to {1}" -f $changed.Count, $Version)
    Write-Host "next: powershell -File scripts/build_plugin.ps1"
    Write-Host "then: powershell -File scripts/upload_plugin_local.ps1"
}
