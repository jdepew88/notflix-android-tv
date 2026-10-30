<#
.SYNOPSIS
  Builds the Notflix Android TV debug APK (and runs the unit tests).

.DESCRIPTION
  The APK is generic: it contains no server address. On first launch it searches the network for
  Notflix servers or lets the user enter one (see README.md).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\build.ps1
#>
param(
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$project = Resolve-Path (Join-Path $PSScriptRoot '..')

# JDK 17+ is required (a JRE is not enough). Prefer an explicit JAVA_HOME with javac in it.
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
    $jdk = Get-ChildItem (Join-Path $env:LOCALAPPDATA 'Programs\Temurin') -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($null -eq $jdk) { throw 'No JDK found. Install Temurin JDK 21 and set JAVA_HOME (see README.md).' }
    $env:JAVA_HOME = $jdk.FullName
}

# Point Gradle at the Android SDK if local.properties is missing.
$localProps = Join-Path $project 'local.properties'
if (-not (Test-Path $localProps)) {
    $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (-not (Test-Path $sdk)) { throw "Android SDK not found at $sdk (see README.md)." }
    "sdk.dir=$($sdk.Replace('\', '/').Replace(':', '\:'))" | Set-Content -Encoding ascii $localProps
}

$tasks = @('assembleDebug')
if (-not $SkipTests) { $tasks += 'testDebugUnitTest' }
$gradleArgs = $tasks + @('--console=plain')

Push-Location $project
try {
    & .\gradlew.bat @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
} finally {
    Pop-Location
}

$apk = Join-Path $project 'app\build\outputs\apk\debug\app-debug.apk'
Write-Host ''
Write-Host "APK: $apk"
