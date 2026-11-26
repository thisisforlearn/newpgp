# SPDX-License-Identifier: GPL-3.0-or-later
# NewPGP manual release fallback: collect artifacts from the latest successful
# CI runs and create/update a single GitHub Release with the gh CLI.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\release.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\release.ps1 -Tag v0.1.2
#
# Idempotent: re-running re-downloads into a fresh temp folder and uploads with
# --clobber, so existing assets are replaced rather than duplicated. Requires
# `gh auth login` with repo scope (verified at startup). Does not commit/push.

#Requires -Version 5.1
param(
    [string]$Tag = '',
    [string]$Repo = 'thisisforlearn/newpgp',
    [switch]$KeepTemp
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:passes = 0
$script:failures = 0

function Write-Pass {
    param([string]$Message)
    $script:passes++
    Write-Host "PASS: $Message" -ForegroundColor Green
}

function Write-Fail {
    param([string]$Message)
    $script:failures++
    Write-Host "FAIL: $Message" -ForegroundColor Red
}

function Write-Warn {
    param([string]$Message)
    Write-Host "WARN: $Message" -ForegroundColor Yellow
}

# Native commands writing to stderr are not errors under PowerShell 5.1; keep
# them in the captured text but do not let $ErrorActionPreference = 'Stop'
# turn gh warnings into terminating exceptions.
function Invoke-Gh {
    param([string[]]$Arguments)
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $text = & gh @Arguments 2>&1 | Out-String
        $code = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
    return [pscustomobject]@{ Code = $code; Text = $text.Trim() }
}

function Get-LabelPath {
    param([string]$Root, [string]$Label)
    return Join-Path $Root ($Label -replace '[^\w.-]', '_')
}

function Rename-AndroidApks {
    param([string]$Root)
    $renamed = 0
    if (Test-Path -LiteralPath $Root) {
        $candidates = @(Get-ChildItem -LiteralPath $Root -File -Recurse |
            Where-Object { $_.Name -eq 'app-debug.apk' -or $_.Name -eq 'app-release.apk' })
        foreach ($file in $candidates) {
            $destination = Join-Path $file.Directory.FullName "$($file.Directory.Name)-$($file.Name)"
            if ($destination -ne $file.FullName) {
                Move-Item -LiteralPath $file.FullName -Destination $destination -Force
                $renamed++
            }
        }
    }
    return $renamed
}

function Rename-CliBinaries {
    param([string]$Root)
    $renamed = 0
    if (Test-Path -LiteralPath $Root) {
        $candidates = @(Get-ChildItem -LiteralPath $Root -File -Recurse |
            Where-Object { $_.Name -eq 'newpgp' -or $_.Name -eq 'newpgp.exe' })
        foreach ($file in $candidates) {
            $folder = $file.Directory.Name
            $target = $folder -replace '^newpgp-cli-', ''
            if ([string]::IsNullOrWhiteSpace($target)) { $target = 'unknown' }
            if ($file.Extension -eq '.exe') {
                $name = "newpgp-$target.exe"
            }
            else {
                $name = "newpgp-$target"
            }
            $destination = Join-Path $file.Directory.FullName $name
            if ($destination -ne $file.FullName) {
                Move-Item -LiteralPath $file.FullName -Destination $destination -Force
                $renamed++
            }
        }
    }
    return $renamed
}

$ghCommand = Get-Command gh -ErrorAction SilentlyContinue
if ($null -eq $ghCommand) {
    Write-Host 'FAIL: gh CLI not found. Install https://cli.github.com/ first.' -ForegroundColor Red
    exit 1
}

$tmpBase = Join-Path ([System.IO.Path]::GetTempPath()) ('newpgp-release-' + [guid]::NewGuid().ToString('N'))

try {
    $auth = Invoke-Gh @('auth', 'status')
    if ($auth.Code -ne 0) {
        throw "gh auth status: $($auth.Text)"
    }
    Write-Pass 'gh CLI is authenticated'

    # Default to the latest release tag; fall back to the newest local git tag
    # when the repository has no release yet.
    if ([string]::IsNullOrWhiteSpace($Tag)) {
        $latest = Invoke-Gh @('release', 'view', '--repo', $Repo, '--json', 'tagName', '--jq', '.tagName')
        if ($latest.Code -eq 0 -and -not [string]::IsNullOrWhiteSpace($latest.Text)) {
            $Tag = $latest.Text
        }
        else {
            $gitTag = & git tag --sort=-version:refname 2>$null | Select-Object -First 1
            if ($null -ne $gitTag) { $Tag = ([string]$gitTag).Trim() }
        }
    }
    if ([string]::IsNullOrWhiteSpace($Tag) -or -not $Tag.StartsWith('v')) {
        throw "Tag is empty or not a v* tag (resolved: '$Tag'). Pass -Tag explicitly."
    }
    Write-Pass "Resolved release tag: $Tag"

    New-Item -ItemType Directory -Path $tmpBase -Force | Out-Null

    # Browser Extension is only triggered by pushes to main, so "latest
    # successful run" is the right source for that artifact.
    $workflows = @(
        @{ File = 'desktop-release.yml'; Label = 'Desktop Release' },
        @{ File = 'cli-release.yml'; Label = 'CLI Binaries' },
        @{ File = 'android-apk.yml'; Label = 'Android APK' },
        @{ File = 'extension.yml'; Label = 'Browser Extension' }
    )

    foreach ($workflow in $workflows) {
        $label = $workflow.Label
        $lookup = Invoke-Gh @(
            'run', 'list', '--repo', $Repo,
            '--workflow', $workflow.File,
            '--status', 'success', '--limit', '10',
            '--json', 'databaseId',
            '--jq', '.[].databaseId'
        )
        if ($lookup.Code -ne 0 -or [string]::IsNullOrWhiteSpace($lookup.Text)) {
            Write-Warn "No successful run found for $label; skipping its artifacts."
            continue
        }
        # Prefer the newest successful run that actually has artifacts: some
        # jobs use `if-no-files-found: ignore` and can be green with no output.
        $runId = ''
        foreach ($candidate in ($lookup.Text -split "`n")) {
            $candidate = $candidate.Trim()
            if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
            $artifacts = Invoke-Gh @('api', "repos/$Repo/actions/runs/$candidate/artifacts", '--jq', '.total_count')
            if ($artifacts.Code -eq 0 -and $artifacts.Text -match '^\d+$' -and [int]$artifacts.Text -gt 0) {
                $runId = $candidate
                break
            }
        }
        if ([string]::IsNullOrWhiteSpace($runId)) {
            Write-Warn "Successful $label runs have no artifacts; skipping."
            continue
        }
        $destination = Get-LabelPath -Root $tmpBase -Label $label
        $download = Invoke-Gh @('run', 'download', $runId, '--repo', $Repo, '--dir', $destination)
        if ($download.Code -ne 0) {
            Write-Warn "Download failed for $label (run $runId): $($download.Text)"
            continue
        }
        $count = @(Get-ChildItem -LiteralPath $destination -File -Recurse -ErrorAction SilentlyContinue).Count
        if ($count -eq 0) {
            Write-Warn "Run $runId for $label contained no artifacts (job may have been skipped)."
        }
        else {
            Write-Pass "Downloaded $count file(s) from $label run $runId"
        }
    }

    $renamed = Rename-CliBinaries -Root (Get-LabelPath -Root $tmpBase -Label 'CLI Binaries')
    if ($renamed -gt 0) {
        Write-Pass "Renamed $renamed CLI binary/binary-names to platform-specific asset names"
    }
    $apkRenamed = Rename-AndroidApks -Root (Get-LabelPath -Root $tmpBase -Label 'Android APK')
    if ($apkRenamed -gt 0) {
        Write-Pass "Renamed $apkRenamed APK(s) to artifact-specific asset names"
    }

    # SHA256SUMS.txt lets users verify every asset with `sha256sum -c` after a
    # flat `gh release download`; it deliberately excludes itself.
    $assets = @(Get-ChildItem -LiteralPath $tmpBase -File -Recurse |
        Where-Object { $_.Name -ne 'SHA256SUMS.txt' })
    $sumFile = Join-Path $tmpBase 'SHA256SUMS.txt'
    if ($assets.Count -gt 0) {
        $lines = foreach ($file in $assets) {
            $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            "$hash  $($file.Name)"
        }
        $lines | Sort-Object | Set-Content -LiteralPath $sumFile -Encoding UTF8
        Write-Pass "Generated SHA256SUMS.txt with $($assets.Count) checksums"
    }
    else {
        Write-Warn 'No artifacts were downloaded; SHA256SUMS.txt was not generated.'
    }

    $existing = Invoke-Gh @('release', 'view', $Tag, '--repo', $Repo, '--json', 'tagName')
    if ($existing.Code -eq 0) {
        Write-Pass "Release $Tag already exists; it will be updated idempotently"
    }
    else {
        $create = Invoke-Gh @('release', 'create', $Tag, '--repo', $Repo, '--verify-tag', '--generate-notes', '--title', $Tag)
        if ($create.Code -ne 0) {
            Write-Fail "gh release create $Tag --generate-notes: $($create.Text)"
        }
        else {
            Write-Pass "Created release $Tag with generated notes"
        }
    }

    $uploadFiles = @(Get-ChildItem -LiteralPath $tmpBase -File -Recurse | ForEach-Object { $_.FullName })
    if ($uploadFiles.Count -eq 0) {
        Write-Fail 'No files available to upload; release was not updated with assets.'
    }
    else {
        $uploadArgs = @('release', 'upload', $Tag, '--repo', $Repo, '--clobber') + $uploadFiles
        $upload = Invoke-Gh $uploadArgs
        if ($upload.Code -ne 0) {
            Write-Fail "gh release upload $Tag --clobber: $($upload.Text)"
        }
        else {
            Write-Pass "Uploaded $($uploadFiles.Count) asset(s) to release $Tag with --clobber"
        }
    }

    Write-Host ''
    Write-Host "Release URL: https://github.com/$Repo/releases/tag/$Tag" -ForegroundColor Cyan
}
catch {
    Write-Fail "Unexpected error: $($_.Exception.Message)"
}
finally {
    if (-not $KeepTemp -and (Test-Path -LiteralPath $tmpBase)) {
        Remove-Item -LiteralPath $tmpBase -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Write-Host ''
Write-Host "$($script:passes) passed, $($script:failures) failed"
if ($script:failures -gt 0) { exit 1 }
exit 0
