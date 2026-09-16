#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$ROOT/dist"
VERSION="$(node "$ROOT/scripts/project-version.mjs")"
cd "$ROOT/apps/server"
for arch in amd64 arm64; do
  echo "Building melora linux/$arch"
  CGO_ENABLED=0 GOOS=linux GOARCH="$arch" go build -trimpath -ldflags="-s -w -X melora/internal/version.Value=$VERSION" -o "$ROOT/dist/melora-linux-$arch" ./cmd/melora
done
