#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/android-env.sh"
source "$ROOT_DIR/scripts/adb-common.sh"
ensure_adb
select_device
pid="$("$ADB" -s "$DEVICE" shell pidof -s "$APP_ID" 2>/dev/null | tr -d '\r')" \
    || die "Rythmo n'est pas lancé. Exécutez ./scripts/phone.sh --fast."
[[ "$pid" =~ ^[0-9]+$ ]] || die "PID Rythmo introuvable. Lancez d'abord l'application."
echo "[logs] $APP_ID · PID $pid (Ctrl+C pour arrêter ; relancer cette commande après un redémarrage de l'app)."
"$ADB" -s "$DEVICE" logcat --pid="$pid" || die "lecture des logs interrompue par ADB"
