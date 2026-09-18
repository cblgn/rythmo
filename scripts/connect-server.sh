#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/adb-common.sh"
ensure_adb
select_device
if [[ "${1:-}" == --disconnect ]]; then
    "$ADB" -s "$DEVICE" reverse --remove tcp:8765
    echo "[phone] Tunnel USB Rythmo supprimé."
    exit 0
fi
[[ $# -eq 0 ]] || die "Usage : ./scripts/connect-server.sh [--disconnect]"
POWERSHELL="/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe"
if [[ -x "$POWERSHELL" ]]; then
    fingerprint_file="$ROOT_DIR/server-data/tls/fingerprint.sha256"
    [[ -f "$fingerprint_file" ]] || die "Identité TLS absente. Lancez ./scripts/server.sh."
    fingerprint="$(cat "$fingerprint_file")"
    [[ "$fingerprint" =~ ^[0-9a-f]{64}$ ]] || die "Empreinte TLS invalide."
    probe="$(wslpath -w "$ROOT_DIR/scripts/probe-tls.ps1")"
    if ! "$POWERSHELL" -NoProfile -ExecutionPolicy Bypass -File "$probe" -Address 127.0.0.1 -Fingerprint "$fingerprint"; then
        "$POWERSHELL" -NoProfile -ExecutionPolicy Bypass -File "$probe" -Address ::1 -Fingerprint "$fingerprint" ||
            die "Serveur HTTPS Rythmo inaccessible ou identité TLS différente. Lancez ./scripts/server.sh."
        echo "[phone] WSL est accessible en IPv6 : activation du relais local Windows pour ADB."
        exec "$POWERSHELL" -NoProfile -ExecutionPolicy Bypass -File "$(wslpath -w "$ROOT_DIR/scripts/usb-relay.ps1")" -Adb "$(wslpath -w "$ADB")" -Serial "$DEVICE"
    fi
fi
"$ADB" -s "$DEVICE" reverse tcp:8765 tcp:8765
echo "[phone] Tunnel USB actif. Dans Rythmo : https://127.0.0.1:8765 et le code affiché par ./scripts/server.sh"
