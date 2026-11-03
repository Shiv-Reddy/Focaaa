param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$SdkRoot = (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
)

$ErrorActionPreference = 'Stop'
$adb = Join-Path $SdkRoot 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw 'ADB is unavailable.' }
& $adb -s $Serial logcat -c | Out-Null
& $adb -s $Serial shell am force-stop com.focaaa.app | Out-Null
& $adb -s $Serial shell monkey -p com.focaaa.app 1 | Out-Null
Start-Sleep -Seconds 3
$pidValue = (@(& $adb -s $Serial shell pidof com.focaaa.app) -join '').Trim()
$crashes = @(& $adb -s $Serial logcat -d -s AndroidRuntime:E | Select-String 'FATAL EXCEPTION|Process: com.focaaa.app')
if (-not $pidValue -or $crashes.Count -gt 0) { throw 'Focaaa release failed its startup smoke check.' }
Write-Output "Focaaa release started on $Serial (PID $pidValue)."
