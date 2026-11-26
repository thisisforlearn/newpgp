# Registers the NewPGP native messaging host for Chrome/Edge and Firefox on Windows.
#
# Windows browsers do not scan NativeMessagingHosts directories. They look up a
# registry key whose (Default) value is the ABSOLUTE path to the JSON manifest.
#   Chrome: https://developer.chrome.com/docs/extensions/develop/concepts/native-messaging
#   Firefox: https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/Native_messaging
#
# Usage:
#   .\install.ps1 -ChromeExtensionId "abcdefghijklmnopabcdefghijklmnop" -FirefoxExtensionId "newpgp@example.org" -Executable "C:\path\newpgp.exe"
# Either ID may be omitted, but at least one must be supplied. Edge uses the
# same chrome-extension:// origin format as Chrome, so a Chrome ID registers both.

param(
  [string]$ChromeExtensionId = "",
  [string]$FirefoxExtensionId = "",
  [string]$Executable = ""
)

$ErrorActionPreference = "Stop"

# Chrome/Edge extension IDs are 32 lowercase letters a-p; Firefox add-on IDs
# are usually id@host (a GUID in braces is also accepted).
$chromeIdPattern = '^[a-p]{32}$'
$firefoxIdPattern = '^[A-Za-z0-9_.@+{}:-]{1,100}$'

if ($ChromeExtensionId -and $ChromeExtensionId -notmatch $chromeIdPattern) {
  throw "Invalid Chrome/Edge extension ID '$ChromeExtensionId'. Expected 32 lowercase letters a-p."
}
if ($FirefoxExtensionId -and $FirefoxExtensionId -notmatch $firefoxIdPattern) {
  throw "Invalid Firefox extension ID '$FirefoxExtensionId'. Expected something like newpgp@example.org."
}
if (-not $ChromeExtensionId -and -not $FirefoxExtensionId) {
  throw "Pass -ChromeExtensionId and/or -FirefoxExtensionId. A manifest with no allowed caller is useless."
}

if (-not $Executable) {
  $local = Join-Path $env:LOCALAPPDATA "Programs\NewPGP\newpgp.exe"
  if (Test-Path $local) { $Executable = $local }
  else {
    $candidate = Get-Command newpgp -ErrorAction SilentlyContinue
    if ($candidate) { $Executable = $candidate.Source }
    else { throw "Pass -Executable with the full path to newpgp.exe" }
  }
}
$Executable = [System.IO.Path]::GetFullPath($Executable)
if (-not (Test-Path -LiteralPath $Executable -PathType Leaf)) { throw "Executable not found: $Executable" }

$manifest = @{
  name = "com.newpgp.bridge"
  description = "NewPGP native messaging bridge (Rust)"
  path = $Executable
  type = "stdio"
}
if ($ChromeExtensionId) { $manifest["allowed_origins"] = @("chrome-extension://$ChromeExtensionId/") }
if ($FirefoxExtensionId) { $manifest["allowed_extensions"] = @($FirefoxExtensionId) }

# ConvertTo-Json escapes backslashes, quotes and control characters in the
# executable path, which is required for a valid JSON manifest on Windows.
$json = $manifest | ConvertTo-Json -Depth 5

$manifestDir = Join-Path $env:LOCALAPPDATA "NewPGP"
New-Item -ItemType Directory -Force -Path $manifestDir | Out-Null
$manifestPath = Join-Path $manifestDir "com.newpgp.bridge.json"
# UTF-8 without BOM: a leading BOM can make the manifest invalid JSON.
[System.IO.File]::WriteAllText($manifestPath, $json, (New-Object System.Text.UTF8Encoding($false)))

function Register-HostKey {
  param([string]$Key)
  New-Item -Path $Key -Force | Out-Null
  # Set-Item on a registry key writes its (Default) REG_SZ value.
  Set-Item -Path $Key -Value $manifestPath
}

if ($ChromeExtensionId) {
  Register-HostKey "HKCU:\Software\Google\Chrome\NativeMessagingHosts\com.newpgp.bridge"
  Register-HostKey "HKCU:\Software\Microsoft\Edge\NativeMessagingHosts\com.newpgp.bridge"
}
if ($FirefoxExtensionId) {
  Register-HostKey "HKCU:\Software\Mozilla\NativeMessagingHosts\com.newpgp.bridge"
}

Write-Host "Registered NewPGP native host using $Executable"
Write-Host "Manifest: $manifestPath"
Write-Host "Restart the browser and reload the extension."
