<#
.SYNOPSIS
  Installs the Notflix Android TV app on a TV over the network (ADB) and starts it.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\install-tv.ps1 -TvAddress 192.168.1.50
.EXAMPLE
  # Android 11+ "Wireless debugging" shows its own port:
  powershell -ExecutionPolicy Bypass -File scripts\install-tv.ps1 -TvAddress 192.168.1.50:37123
#>
param(
    [Parameter(Mandatory = $true)][string]$TvAddress,
    [string]$Apk,
    [switch]$NoLaunch
)

$ErrorActionPreference = 'Stop'
# (Windows PowerShell 5.1 has no $PSScriptRoot inside parameter defaults.)
if (-not $Apk) { $Apk = Join-Path $PSScriptRoot '..\app\build\outputs\apk\debug\app-debug.apk' }

$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path $adb)) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -eq $cmd) { throw 'adb was not found. Install Android SDK platform-tools (see README.md).' }
    $adb = $cmd.Source
}
if (-not (Test-Path $Apk)) { throw "APK not found at $Apk. Build it first: scripts\build.ps1" }

$target = if ($TvAddress -match ':\d+$') { $TvAddress } else { "$($TvAddress):5555" }

Write-Host "Connecting to $target ... (accept 'Allow debugging?' on the TV the first time)"
& $adb connect $target | Write-Host
$state = (& $adb -s $target get-state 2>$null)
if ($state -ne 'device') {
    throw "The TV at $target is not ready (state: '$state'). Check that debugging is enabled and approved on the TV."
}

Write-Host "Installing $(Split-Path $Apk -Leaf) ..."
& $adb -s $target install -r $Apk | Write-Host
if ($LASTEXITCODE -ne 0) { throw 'Install failed.' }

if (-not $NoLaunch) {
    & $adb -s $target shell am start -n com.notflix.tv.debug/com.notflix.tv.MainActivity | Out-Null
    Write-Host 'Notflix is starting on the TV.'
}
