# 一键构建网课笔记。产物：app\build\outputs\apk\release\app-release.apk
# 用法：  .\build.ps1            打真机包（arm64-v8a + armeabi-v7a）
#         .\build.ps1 -Emu       打模拟器包（x86_64）
param([switch]$Emu)

$ErrorActionPreference = 'Stop'

# 路径优先用已有的环境变量；没设才用本机默认位置（不存在就交给系统 / PATH）
function Use-PathIfExists([string]$name, [string]$fallback) {
    if ([Environment]::GetEnvironmentVariable($name)) { return }
    if ($fallback -and (Test-Path -LiteralPath $fallback)) {
        [Environment]::SetEnvironmentVariable($name, $fallback)
    }
}
Use-PathIfExists 'JAVA_HOME'        'C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot'
# 构建工具与缓存都放 E 盘，不占 C 盘（按需改成自己的路径）
Use-PathIfExists 'GRADLE_USER_HOME' 'E:\AndroidDev\gradle-home'
Use-PathIfExists 'ANDROID_HOME'     'E:\AndroidDev\sdk'
Use-PathIfExists 'ANDROID_SDK_ROOT' 'E:\AndroidDev\sdk'

Set-Location $PSScriptRoot

$gradleArgs = @('assembleRelease', '--console=plain')
if ($Emu) { $gradleArgs += '-Pemu' }

& .\gradlew.bat @gradleArgs
if ($LASTEXITCODE -ne 0) {
    Write-Host "构建失败：gradle 退出码 $LASTEXITCODE（上面有具体报错）" -ForegroundColor Red
    exit $LASTEXITCODE
}

$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\release\app-release.apk'
if (Test-Path $apk) {
    $mb = [math]::Round((Get-Item $apk).Length / 1MB, 1)
    Write-Host ""
    Write-Host "构建完成: $apk ($mb MB)" -ForegroundColor Green
} else {
    Write-Host "构建失败，没找到产物" -ForegroundColor Red
}
