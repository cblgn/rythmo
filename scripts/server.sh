#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/android-env.sh"
cd "$ROOT_DIR"
ensure_build_environment
./gradlew :server:installDist --console=plain
exec "$ROOT_DIR/server/build/install/server/bin/server" "$ROOT_DIR/server-data" "${RYTHMO_PORT:-8765}"
