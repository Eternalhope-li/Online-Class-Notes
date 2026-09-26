param([switch]$Reinstall)
$ErrorActionPreference = "Stop"
# adb 用 PATH 里的，或者 $env:ANDROID_HOME\platform-tools\adb.exe
$adb = if ($env:ADB) { $env:ADB }
       elseif ($env:ANDROID_HOME -and (Test-Path "$env:ANDROID_HOME\platform-tools\adb.exe")) { "$env:ANDROID_HOME\platform-tools\adb.exe" }
       else { 'adb' }
$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\release\app-release.apk'
$pkg = "com.lecture.notes"

& $adb wait-for-device
$dev = (& $adb devices | Select-String "\tdevice$") -join ""
if (-not $dev) { Write-Host "[X] 没有已授权的设备" -ForegroundColor Red; exit 1 }
Write-Host "[OK] 设备: $dev" -ForegroundColor Green
Write-Host "型号: $(& $adb shell getprop ro.product.model) / $(& $adb shell getprop ro.build.version.release) (API $(& $adb shell getprop ro.build.version.sdk))"

$installed = & $adb shell pm list packages $pkg
if ($Reinstall -or -not $installed) {
  Write-Host "[..] 安装中（285MB，请稍等）" -ForegroundColor Yellow
  & $adb install -r -d $apk
  if ($LASTEXITCODE -ne 0) { Write-Host "[X] 安装失败" -ForegroundColor Red; exit 1 }
}
Write-Host "[OK] 已安装: $(& $adb shell pm list packages $pkg)" -ForegroundColor Green

& $adb logcat -c
& $adb shell am start -n "$pkg/.ui.MainActivity" | Out-Null

$log = Join-Path $PSScriptRoot 'run.log'
$job = Start-Job -ScriptBlock {
  param($adb,$log)
  & $adb logcat -v time AsrEngine:V Recorder:V CaptureService:V AudioInput:V NoteStore:V AndroidRuntime:E Debug:V "*:S" > $log
} -ArgumentList $adb,$log
Write-Host "[..] 抓日志 60 秒（run.log），期间请操作 App" -ForegroundColor Yellow
Start-Sleep 60
Stop-Job $job; Remove-Job $job -Force

Write-Host "`n===== 崩溃 / 异常 =====" -ForegroundColor Cyan
Select-String -Path $log -Pattern "FATAL|AndroidRuntime|Exception|E/" | Select-Object -First 40
Write-Host "`n===== 识别性能 (RTF) =====" -ForegroundColor Cyan
Select-String -Path $log -Pattern "performance|RTF|识别" | Select-Object -Last 20
Write-Host "`n===== 内存 =====" -ForegroundColor Cyan
& $adb shell dumpsys meminfo $pkg | Select-String "TOTAL|Native Heap|Java Heap|Graphics"
