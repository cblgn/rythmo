#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/android-env.sh"
source "$ROOT_DIR/scripts/adb-common.sh"

case "$*" in
    '') fast=false ;;
    --fast) fast=true ;;
    *) die "Usage : ./scripts/phone.sh [--fast]" ;;
esac

cd "$ROOT_DIR"
ensure_build_environment
if [[ "$fast" == false ]]; then
    echo "[phone] Tests unitaires..."
    ./gradlew test --console=plain || die "les tests unitaires ont échoué"
fi
echo "[phone] Compilation de l'APK..."
./gradlew assembleDebug --console=plain || die "assembleDebug a échoué"

apk="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$apk" ]] || die "APK introuvable : $apk"
ensure_adb
select_device
command -v wslpath >/dev/null || die "wslpath est requis pour transmettre l'APK à adb.exe."
apk_windows="$(wslpath -w "$apk")" || die "conversion du chemin APK impossible"

echo "[phone] Installation sur $DEVICE..."
echo "[phone] Gardez le téléphone déverrouillé et acceptez l'installation si une demande apparaît."
if ! install_output="$("$ADB" -s "$DEVICE" install -r "$apk_windows" 2>&1)"; then
    printf '%s\n' "$install_output" >&2
    if [[ "$install_output" == *INSTALL_FAILED_USER_RESTRICTED* ]]; then
        die "installation bloquée par le téléphone. Sur Xiaomi/MIUI : activez « Installer via USB » dans les Options pour les développeurs, puis acceptez la demande d'installation."
    fi
    die "installation impossible (voir le message ADB ci-dessus)"
fi
printf '%s\n' "$install_output"
echo "[phone] Lancement de $APP_ID..."
launch_output="$("$ADB" -s "$DEVICE" shell am start -W -n "$APP_ID/.MainActivity" 2>&1)" \
    || die "lancement impossible : $launch_output"
printf '%s\n' "$launch_output"
# Some Android versions report Activity Manager errors with exit code zero.
if [[ "$launch_output" == *"Error"* || "$launch_output" == *"Exception"* ]]; then
    die "Android n'a pas pu lancer Rythmo (voir le message ci-dessus)."
fi
echo "[phone] Rythmo est lancé sur $DEVICE."
