param([string]$Avd = 'ForgeApi35', [string]$SdkPath)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (!$SdkPath) { $SdkPath = Join-Path $workspace 'toolchain\android-sdk' }
$adb = Join-Path $SdkPath 'platform-tools\adb.exe'
if ((& $adb devices) -match 'emulator-\d+\s+(device|offline)') { throw 'An emulator is already running.' }
$env:ANDROID_HOME = $SdkPath; $env:ANDROID_SDK_ROOT = $SdkPath
$env:ANDROID_USER_HOME = Join-Path $SdkPath 'avd-config'
$env:ANDROID_AVD_HOME = Join-Path $SdkPath 'avd'
$env:TEMP = Join-Path $workspace 'emulator-temp'; $env:TMP = $env:TEMP
New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null
Start-Process -FilePath (Join-Path $SdkPath 'emulator\emulator.exe') `
    -ArgumentList @('-avd', $Avd, '-memory', '8192', '-no-snapshot', '-no-boot-anim', '-gpu', 'auto', '-no-metrics') `
    -WindowStyle Normal
