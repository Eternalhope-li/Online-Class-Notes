# 下载 SenseVoice int8 语音识别模型到 app/src/main/assets/sense-voice/。
#
# 为什么要单独下：模型 228 MB，超过 GitHub 单文件 100 MB 的硬限制，所以没有入库。
# 出处是 sherpa-onnx 的官方模型（k2-fsa/sherpa-onnx 的 asr-models 发布），
# 这里直接从官方单文件镜像取，只下需要的两个文件，不用拖整个 1 GB 的包。
#
# 用法：  .\tools\fetch-model.ps1            # 已存在且大小对就跳过
#         .\tools\fetch-model.ps1 -Force    # 强制重下
param([switch]$Force)

$ErrorActionPreference = 'Stop'

$repo = 'csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17'
$base = "https://huggingface.co/$repo/resolve/main"
$dest = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\app\src\main\assets\sense-voice'))

# 文件 -> 期望字节数（对不上就当下载坏了）
$expect = [ordered]@{
    'model.int8.onnx' = 239233841
    'tokens.txt'      = 315894
}

New-Item -ItemType Directory -Force $dest | Out-Null

foreach ($name in $expect.Keys) {
    $target = Join-Path $dest $name
    $want = $expect[$name]

    if ((Test-Path -LiteralPath $target) -and -not $Force) {
        if ((Get-Item -LiteralPath $target).Length -eq $want) {
            Write-Host "[跳过] $name 已就位" -ForegroundColor DarkGray
            continue
        }
        Write-Host "[重下] $name 大小不对" -ForegroundColor Yellow
    }

    Write-Host "[下载] $name ..." -ForegroundColor Yellow
    $tmp = "$target.part"
    Invoke-WebRequest -Uri "$base/$name" -OutFile $tmp -UseBasicParsing

    $got = (Get-Item -LiteralPath $tmp).Length
    if ($got -ne $want) {
        Remove-Item -LiteralPath $tmp -Force
        throw "$name 大小不对：期望 $want 字节，实际 $got 字节（网络中断？重跑一次）"
    }
    Move-Item -LiteralPath $tmp -Destination $target -Force
    Write-Host ("[完成] {0}（{1:N1} MB）" -f $name, ($got / 1MB)) -ForegroundColor Green
}

Write-Host "模型已就位：$dest" -ForegroundColor Green
