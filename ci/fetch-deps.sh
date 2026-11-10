#!/usr/bin/env bash
# Fetches the offline speech-recognition library (sherpa-onnx) the app is built against.
set -euo pipefail
mkdir -p app/libs
[ -f app/libs/sherpa-onnx-1.13.8.aar ] || curl -sfL -o app/libs/sherpa-onnx-1.13.8.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar
ls -la app/libs
