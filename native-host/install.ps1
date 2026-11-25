# Registers the NewPGP native messaging host for Chrome/Edge and Firefox on Windows.
# Usage: .\install.ps1 -ChromeExtensionId "abcdef..." -FirefoxExtensionId "{...}" -Executable "C:\path\newpgp.exe"

param(
  [string]$ChromeExtensionId = "",
  [string]$FirefoxExtensionId = "",
  [string]$Executable = ""
)

$ErrorActionPreference = "Stop"

if (-not $Executable) {
  $local = Join-Path $env:LOCALAPPDATA "Programs\NewPGP\newpgp.exe"
  if (Test-Path $local) { $Executable = $local }
  else {
    $candidate = Get-Command newpgp -ErrorAction SilentlyContinue
    if ($candidate) { $Executable = $candidate.Source }
    else { throw "Pass -Executable with the full path to newpgp.exe" }
  }
}
if (-not (Test-Path $Executable)) { throw "Executable not found: $Executable" }

$manifest = @{
  name = "com.newpgp.bridge"
  description = "NewPGP native messaging bridge (Rust)"
  path = $Executable
  type = "stdio"
  allowed_origins = @()
  allowed_extensions = @()
}
if ($ChromeExtensionId) { $manifest.allowed_origins += "chrome-extension://$ChromeExtensionId/" }
if ($FirefoxExtensionId) { $manifest.allowed_extensions += $FirefoxExtensionId }

$chromeDir = Join-Path $env:LOCALAPPDATA "Google\Chrome\NativeMessagingHosts"
$edgeDir = Join-Path $env:LOCALAPPDATA "Microsoft\Edge\NativeMessagingHosts"
$firefoxDir = Join-Path $env:APPDATA "Mozilla\NativeMessagingHosts"

foreach ($dir in @($chromeDir, $edgeDir, $firefoxDir)) {
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
}
$json = $manifest | ConvertTo-Json -Depth 5
Set-Content -Path (Join-Path $chromeDir "com.newpgp.bridge.json") -Value $json
Set-Content -Path (Join-Path $edgeDir "com.newpgp.bridge.json") -Value $json
if ($FirefoxExtensionId) {
  Set-Content -Path (Join-Path $firefoxDir "com.newpgp.bridge.json") -Value $json
}

Write-Host "Registered NewPGP native host using $Executable"
Write-Host "Restart the browser and reload the extension."
