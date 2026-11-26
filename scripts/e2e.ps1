# SPDX-License-Identifier: GPL-3.0-or-later
# NewPGP end-to-end smoke test. Requires only Rust (cargo) and PowerShell.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts\e2e.ps1

#Requires -Version 5.1
Set-StrictMode -Version Latest

$root = Split-Path -Parent $PSScriptRoot
$exe = Join-Path $root 'target\debug\newpgp.exe'
$tmpBase = Join-Path ([System.IO.Path]::GetTempPath()) ('newpgp-e2e-' + [guid]::NewGuid().ToString('N'))
$script:homes = @()
$script:failures = 0
$script:passes = 0

function Assert-Condition {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Step {
    param([string]$Name, [scriptblock]$Body)
    try {
        & $Body
        $script:passes++
        Write-Host "PASS: $Name" -ForegroundColor Green
    }
    catch {
        $script:failures++
        Write-Host "FAIL: $Name -- $($_.Exception.Message)" -ForegroundColor Red
    }
}

function New-HomeDir {
    param([string]$Label)
    $path = Join-Path $tmpBase ($Label + '-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $path -Force | Out-Null
    $script:homes += $path
    return $path
}

function Invoke-Newpgp {
    param(
        [string]$HomeDir,
        [string[]]$Arguments,
        [switch]$AllowFailure
    )
    $oldHome = $env:NEWPGP_HOME
    $oldPassword = $env:NEWPGP_KEY_PASSWORD
    $env:NEWPGP_HOME = $HomeDir
    $env:NEWPGP_KEY_PASSWORD = ''
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $exe @Arguments 2>&1
        $code = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
        $env:NEWPGP_HOME = $oldHome
        if ($null -eq $oldPassword) {
            Remove-Item Env:NEWPGP_KEY_PASSWORD -ErrorAction SilentlyContinue
        }
        else {
            $env:NEWPGP_KEY_PASSWORD = $oldPassword
        }
    }
    $text = ($output | Out-String).Trim()
    if ($code -ne 0 -and -not $AllowFailure) {
        throw "newpgp $($Arguments -join ' ') exited with $code : $text"
    }
    return [pscustomobject]@{ Code = $code; Text = $text }
}

function Write-Frame {
    param([System.Diagnostics.Process]$Process, [string]$Json)
    $payload = [System.Text.Encoding]::UTF8.GetBytes($Json)
    $length = [System.BitConverter]::GetBytes([uint32]$payload.Length)
    $stream = $Process.StandardInput.BaseStream
    $stream.Write($length, 0, 4)
    $stream.Write($payload, 0, $payload.Length)
    $stream.Flush()
}

# Spawn the native host, send framed JSON requests, close stdin, and collect
# every framed response after the process exits.
function Invoke-Bridge {
    param([string]$HomeDir, [string[]]$Requests)
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo.FileName = $exe
    $process.StartInfo.Arguments = 'bridge'
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.RedirectStandardInput = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    $process.StartInfo.EnvironmentVariables['NEWPGP_HOME'] = $HomeDir
    $process.StartInfo.EnvironmentVariables['NEWPGP_KEY_PASSWORD'] = ''
    $process.Start() | Out-Null
    try {
        foreach ($request in $Requests) { Write-Frame $process $request }
        $process.StandardInput.Close()
        Assert-Condition ($process.WaitForExit(20000)) 'bridge did not exit after stdin was closed'
        Assert-Condition ($process.ExitCode -eq 0) "bridge exited with $($process.ExitCode)"

        $buffer = [System.IO.MemoryStream]::new()
        $process.StandardOutput.BaseStream.CopyTo($buffer)
        $raw = $buffer.ToArray()
        $frames = @()
        $position = 0
        while ($position + 4 -le $raw.Length) {
            $length = [System.BitConverter]::ToUInt32($raw, $position)
            $position += 4
            Assert-Condition ($position + $length -le $raw.Length) 'truncated bridge frame'
            $json = [System.Text.Encoding]::UTF8.GetString($raw, $position, [int]$length)
            $frames += ($json | ConvertFrom-Json)
            $position += [int]$length
        }
        Assert-Condition ($position -eq $raw.Length) "unexpected trailing bridge bytes ($($raw.Length - $position))"
        return $frames
    }
    finally {
        if (-not $process.HasExited) { $process.Kill() }
        $process.Dispose()
    }
}

# ---------------------------------------------------------------------------
# Build first: everything below drives the freshly built binary.
# ---------------------------------------------------------------------------
$cargoCommand = Get-Command cargo -ErrorAction SilentlyContinue
if ($null -ne $cargoCommand) {
    $cargoPath = $cargoCommand.Source
}
else {
    # rustup proxies are sometimes missing from PATH; ask rustup itself.
    $cargoPath = (& rustup which cargo 2>$null | Out-String).Trim()
    if ([string]::IsNullOrEmpty($cargoPath) -or -not (Test-Path $cargoPath)) {
        Write-Host 'FAIL: cargo not found. Install Rust and add it to PATH.' -ForegroundColor Red
        exit 1
    }
}

Write-Host 'Building newpgp-cli ...'
Push-Location $root
try {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $cargoPath build -p newpgp-cli
    $buildCode = $LASTEXITCODE
    $ErrorActionPreference = $previousPreference
}
finally {
    Pop-Location
}
if ($buildCode -ne 0) {
    Write-Host 'FAIL: cargo build -p newpgp-cli' -ForegroundColor Red
    exit 1
}
Assert-Condition (Test-Path $exe) "binary not found at $exe"
Write-Host 'PASS: cargo build -p newpgp-cli' -ForegroundColor Green
New-Item -ItemType Directory -Path $tmpBase -Force | Out-Null

try {
    $alice = New-HomeDir 'alice'

    Step 'init creates identity.asc and prints a fingerprint' {
        $result = Invoke-Newpgp -HomeDir $alice -Arguments @('init', '--name', 'Alice Example', '--email', 'alice@example.com')
        $match = [regex]::Match($result.Text, 'Fingerprint:\s*([0-9A-F ]{40,49})')
        Assert-Condition $match.Success "no fingerprint printed: $($result.Text)"
        $script:aliceFp = ($match.Groups[1].Value -replace '\s', '').ToUpperInvariant()
        Assert-Condition ($script:aliceFp.Length -eq 40) "unexpected fingerprint: $($script:aliceFp)"
        Assert-Condition (Test-Path (Join-Path $alice 'identity.asc')) 'identity.asc was not created'
    }

    Step 'key show and key list find a real imported contact key' {
        $show = Invoke-Newpgp -HomeDir $alice -Arguments @('key', 'show')
        Assert-Condition ($show.Text.Contains('-----BEGIN PGP PUBLIC KEY BLOCK-----')) "key show printed no public key: $($show.Text)"
        Assert-Condition (($show.Text -replace '\s', '').Contains($script:aliceFp)) 'key show fingerprint differs from init'

        $bob = New-HomeDir 'bob'
        $bobInit = Invoke-Newpgp -HomeDir $bob -Arguments @('init', '--name', 'Bob Example', '--email', 'bob@example.com')
        $bobMatch = [regex]::Match($bobInit.Text, 'Fingerprint:\s*([0-9A-F ]{40,49})')
        Assert-Condition $bobMatch.Success "no bob fingerprint: $($bobInit.Text)"
        $script:bobFp = ($bobMatch.Groups[1].Value -replace '\s', '').ToUpperInvariant()

        $bobShow = Invoke-Newpgp -HomeDir $bob -Arguments @('key', 'show')
        $armor = [regex]::Match($bobShow.Text, '(?s)-----BEGIN PGP PUBLIC KEY BLOCK-----.*?-----END PGP PUBLIC KEY BLOCK-----')
        Assert-Condition $armor.Success 'could not export bob public key'
        $keyFile = Join-Path $alice 'bob.asc'
        [System.IO.File]::WriteAllText($keyFile, $armor.Value)
        $import = Invoke-Newpgp -HomeDir $alice -Arguments @('key', 'import', 'bob@example.com', $keyFile)
        Assert-Condition ($import.Text.Contains('Safety number:')) "key import gave no safety number: $($import.Text)"

        $list = Invoke-Newpgp -HomeDir $alice -Arguments @('key', 'list')
        Assert-Condition ($list.Text.Contains('bob@example.com')) "key list omitted bob: $($list.Text)"
        Assert-Condition (($list.Text -replace '\s', '').Contains($script:bobFp)) 'key list printed the wrong fingerprint'
        $script:bobHome = $bob
    }

    Step 'key invite returns an https link with a fragment' {
        $invite = Invoke-Newpgp -HomeDir $alice -Arguments @('key', 'invite', 'friend@example.com')
        $url = $invite.Text.Trim()
        Assert-Condition $url.StartsWith('https://') "invite is not https: $url"
        $parts = $url.Split('#', 2)
        Assert-Condition ($parts.Length -eq 2) "invite has no fragment: $url"
        Assert-Condition ($parts[1].Contains('addr=friend%40example.com') -and $parts[1].Contains('fpr=') -and $parts[1].Contains('key=')) "fragment is incomplete: $url"
        Assert-Condition ($parts[1].Contains($script:aliceFp)) 'fragment does not carry our fingerprint'
    }

    Step 'compose --mode both keeps plaintext and PGP block; pgp-only hides plaintext' {
        $plaintext = 'Lunch at noon tomorrow'
        $both = Invoke-Newpgp -HomeDir $alice -Arguments @('compose', '--body', $plaintext, '-r', 'bob@example.com', '--mode', 'both')
        Assert-Condition ($both.Text.Contains($plaintext)) "send-both dropped plaintext: $($both.Text)"
        Assert-Condition ($both.Text.Contains('-----BEGIN PGP MESSAGE-----')) "send-both dropped ciphertext: $($both.Text)"

        $pgpOnly = Invoke-Newpgp -HomeDir $alice -Arguments @('compose', '--body', $plaintext, '-r', 'bob@example.com', '--mode', 'pgp-only')
        Assert-Condition (-not $pgpOnly.Text.Contains($plaintext)) "pgp-only leaked plaintext: $($pgpOnly.Text)"
        Assert-Condition ($pgpOnly.Text.Contains('-----BEGIN PGP MESSAGE-----')) "pgp-only has no ciphertext: $($pgpOnly.Text)"
    }

    Step 'encrypt then decrypt round-trips a binary file between two homes' {
        $input = Join-Path $alice 'payload.bin'
        $encrypted = Join-Path $alice 'payload.bin.pgp'
        $decrypted = Join-Path $script:bobHome 'payload.decrypted.bin'
        $bytes = [byte[]](0..255) + [byte[]](0..255)
        [System.IO.File]::WriteAllBytes($input, $bytes)
        $encodedOriginal = [Convert]::ToBase64String($bytes)

        $enc = Invoke-Newpgp -HomeDir $alice -Arguments @('encrypt', '-r', 'bob@example.com', '-f', $input, '-o', $encrypted)
        Assert-Condition ($enc.Text.Contains('Encrypted 512 bytes')) "unexpected encrypt output: $($enc.Text)"
        $script:encryptedFile = $encrypted
        $dec = Invoke-Newpgp -HomeDir $script:bobHome -Arguments @('decrypt', '-f', $encrypted, '-o', $decrypted)
        Assert-Condition ($dec.Text.Contains('Decrypted to')) "unexpected decrypt output: $($dec.Text)"
        $encodedResult = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($decrypted))
        Assert-Condition ($encodedResult -ceq $encodedOriginal) 'decrypted bytes differ from the original binary file'
    }

    Step 'backup create prints a 9x4 code and backup restore recovers the key' {
        $backup = Join-Path $alice 'alice-backup.asc'
        $created = Invoke-Newpgp -HomeDir $alice -Arguments @('backup', 'create', $backup)
        $codeMatch = [regex]::Match($created.Text, '\b\d{4}(?:-\d{4}){8}\b')
        Assert-Condition $codeMatch.Success "no 9x4 setup code printed: $($created.Text)"
        $backupText = [System.IO.File]::ReadAllText($backup)
        Assert-Condition ($backupText.Contains('-----BEGIN PGP MESSAGE-----')) 'backup is not an encrypted PGP message'

        $restored = Join-Path $alice 'restored.asc'
        # Keep the setup code out of the process list and shell history.
        $oldSetupCode = $env:NEWPGP_SETUP_CODE
        $env:NEWPGP_SETUP_CODE = $codeMatch.Value
        try {
            $restore = Invoke-Newpgp -HomeDir $alice -Arguments @('backup', 'restore', $backup, $restored)
        } finally {
            if ($null -eq $oldSetupCode) { Remove-Item Env:NEWPGP_SETUP_CODE -ErrorAction SilentlyContinue }
            else { $env:NEWPGP_SETUP_CODE = $oldSetupCode }
        }
        Assert-Condition ($restore.Text.Contains('Key restored.')) "unexpected restore output: $($restore.Text)"
        $original = [System.IO.File]::ReadAllText((Join-Path $alice 'identity.asc'))
        Assert-Condition (([System.IO.File]::ReadAllText($restored)) -ceq $original) 'restored key does not match the original'
    }

    Step 'decrypt with the wrong home fails with a friendly hint' {
        $stranger = New-HomeDir 'stranger'
        Invoke-Newpgp -HomeDir $stranger -Arguments @('init', '--name', 'Charlie Example', '--email', 'charlie@example.com') | Out-Null
        $wrongOutput = Join-Path $stranger 'wrong.bin'
        $bad = Invoke-Newpgp -HomeDir $stranger -Arguments @('decrypt', '-f', $script:encryptedFile, '-o', $wrongOutput) -AllowFailure
        Assert-Condition ($bad.Code -ne 0) 'wrong home decrypted successfully'
        Assert-Condition ($bad.Text.Contains("This message wasn't encrypted for the key you selected")) "no human-readable error: $($bad.Text)"
        Assert-Condition ($bad.Text.Contains('Hint:') -and $bad.Text.Contains('Check which identity is active')) "no actionable hint: $($bad.Text)"
        Assert-Condition (-not (Test-Path $wrongOutput)) 'decrypt wrote plaintext despite failing'
    }

    Step 'native bridge answers framed requests and exits cleanly' {
        $bridgeHome = New-HomeDir 'bridge'
        $frames = @(Invoke-Bridge -HomeDir $bridgeHome -Requests @(
            '{"id":"1","method":"ping","params":{}}',
            '{"id":"2","method":"generateKey","params":{"name":"Bridge User","email":"bridge@example.com","passphrase":""}}'
        ))
        Assert-Condition ($frames.Count -eq 2) "expected 2 bridge responses, got $($frames.Count)"
        Assert-Condition ($frames[0].id -eq '1' -and $frames[0].ok -eq $true) "ping failed: $($frames[0] | ConvertTo-Json -Compress)"
        Assert-Condition ($frames[0].result.ok -eq $true) 'ping result was not ok'
        Assert-Condition ($frames[1].id -eq '2' -and $frames[1].ok -eq $true) "generateKey failed: $($frames[1] | ConvertTo-Json -Compress)"
        $bridgeFp = ($frames[1].result.fingerprint -replace '\s', '').ToUpperInvariant()
        Assert-Condition ($bridgeFp.Length -eq 40) "bad bridge fingerprint: $bridgeFp"
        Assert-Condition ($frames[1].result.publicKey.Contains('-----BEGIN PGP PUBLIC KEY BLOCK-----')) 'no public key in generateKey response'

        $bridgeShow = Invoke-Newpgp -HomeDir $bridgeHome -Arguments @('key', 'show')
        Assert-Condition (($bridgeShow.Text -replace '\s', '').Contains($bridgeFp)) 'bridge-generated key was not saved'

        $bridgeKey = Join-Path $bridgeHome 'bridge-generated.asc'
        $armor = [regex]::Match($bridgeShow.Text, '(?s)-----BEGIN PGP PUBLIC KEY BLOCK-----.*?-----END PGP PUBLIC KEY BLOCK-----')
        Assert-Condition $armor.Success 'could not export bridge-generated key'
        [System.IO.File]::WriteAllText($bridgeKey, $armor.Value)
        Invoke-Newpgp -HomeDir $bridgeHome -Arguments @('key', 'import', 'bridge@example.com', $bridgeKey) | Out-Null

        $getKeyFrames = @(Invoke-Bridge -HomeDir $bridgeHome -Requests @(
            '{"id":"3","method":"getKey","params":{"email":"bridge@example.com"}}'
        ))
        Assert-Condition ($getKeyFrames.Count -eq 1) "expected 1 getKey response, got $($getKeyFrames.Count)"
        Assert-Condition ($getKeyFrames[0].ok -eq $true) "getKey failed: $($getKeyFrames[0] | ConvertTo-Json -Compress)"
        Assert-Condition ($getKeyFrames[0].result.email -eq 'bridge@example.com') 'getKey returned the wrong contact'
        Assert-Condition ((($getKeyFrames[0].result.fingerprint -replace '\s', '').ToUpperInvariant()) -eq $bridgeFp) 'getKey returned the wrong fingerprint'
    }
}
finally {
    foreach ($hDir in $script:homes) {
        Remove-Item -LiteralPath $hDir -Recurse -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $tmpBase -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host "$($script:passes) passed, $($script:failures) failed"
if ($script:failures -gt 0) { exit 1 }
exit 0
