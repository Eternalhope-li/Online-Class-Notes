#!/usr/bin/env bash
# 同 fetch-model.ps1：下载 SenseVoice int8 语音识别模型（228 MB，没入库）。
# 用法：bash tools/fetch-model.sh
set -euo pipefail

repo="csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"
base="https://huggingface.co/${repo}/resolve/main"
dest="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/sense-voice"

mkdir -p "$dest"

for f in model.int8.onnx tokens.txt; do
    if [ -s "$dest/$f" ]; then
        echo "[跳过] $f 已就位"
        continue
    fi
    echo "[下载] $f ..."
    curl -L --fail --progress-bar -o "$dest/$f.part" "$base/$f"
    mv "$dest/$f.part" "$dest/$f"
    echo "[完成] $f"
done

echo "模型已就位：$dest"
