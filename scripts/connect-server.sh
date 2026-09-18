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
    if ! "$POWERSHELL" -NoProfile -Command 'try { $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 http://127.0.0.1:8765/health; if (($r.Content | ConvertFrom-Json).name -ne "Rythmo") { exit 1 } } catch { exit 1 }' >/dev/null 2>&1; then
        "$POWERSHELL" -NoProfile -Command 'try { $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 http://[::1]:8765/health; if (($r.Content | ConvertFrom-Json).name -ne "Rythmo") { exit 1 } } catch { exit 1 }' >/dev/null 2>&1 ||
            die "Serveur Rythmo inaccessible depuis Windows. Lancez ./scripts/server.sh dans un autre terminal."
        echo "[phone] WSL est accessible en IPv6 : activation du relais local Windows pour ADB."
        exec "$POWERSHELL" -NoProfile -ExecutionPolicy Bypass -File "$(wslpath -w "$ROOT_DIR/scripts/usb-relay.ps1")" -Adb "$(wslpath -w "$ADB")" -Serial "$DEVICE"
    fi
fi
"$ADB" -s "$DEVICE" reverse tcp:8765 tcp:8765
echo "[phone] Tunnel USB actif. Dans Rythmo : http://127.0.0.1:8765 et le code affiché par ./scripts/server.sh"
