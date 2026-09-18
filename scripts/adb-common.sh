#!/usr/bin/env bash
# Shared Windows ADB discovery and device selection for phone.sh and logs.sh.
APP_ID="fr.rythmo"

windows_local_appdata() {
    local value="${LOCALAPPDATA:-}"
    if [[ -z "$value" ]] && command -v cmd.exe >/dev/null; then
        # cmd emits an UNC warning when called from the WSL project directory.
        value="$(cd /mnt/c && cmd.exe /d /c 'echo %LOCALAPPDATA%' </dev/null 2>/dev/null | tr -d '\r' | tail -n 1)" || return 1
    fi
    [[ -n "$value" && "$value" != '%LOCALAPPDATA%' ]] || return 1
    if [[ "$value" == /* ]]; then printf '%s\n' "$value"
    else wslpath -u "$value"; fi
}

ensure_adb() {
    if [[ -n "${ADB_PATH:-}" ]]; then
        ADB="$ADB_PATH"
    else
        local appdata
        appdata="$(windows_local_appdata || true)"
        ADB="$appdata/Android/Sdk/platform-tools/adb.exe"
        if [[ -z "$appdata" || ! -f "$ADB" ]]; then
            ADB="$ROOT_DIR/.tools/adb-windows/platform-tools/adb.exe"
            if [[ ! -f "$ADB" ]]; then
                command -v unzip >/dev/null || die "unzip est requis pour installer ADB Windows."
                mkdir -p "$ROOT_DIR/.tools/downloads"
                echo "[phone] ADB absent du SDK Windows ; installation locale des platform-tools Windows..."
                local archive="$ROOT_DIR/.tools/downloads/platform-tools-windows.zip"
                download 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' "$archive"
                unzip -qo "$archive" -d "$ROOT_DIR/.tools/adb-windows" || die "extraction d'ADB Windows impossible"
            fi
            chmod +x "$ADB"
        fi
    fi
    [[ -f "$ADB" ]] || die "adb.exe introuvable : $ADB"
    "$ADB" version || die "adb.exe ne démarre pas. Vérifiez que l'interopérabilité Windows est activée dans WSL."
}

select_device() {
    local devices
    devices="$("$ADB" devices -l 2>&1)" || die "ADB ne peut pas lister les appareils : $devices"
    devices="$(printf '%s\n' "$devices" | tr -d '\r')"
    if [[ -n "${ANDROID_SERIAL:-}" ]]; then
        DEVICE="$(printf '%s\n' "$devices" | awk -v serial="$ANDROID_SERIAL" '$1 == serial && $2 == "device" {print $1}')"
        [[ -n "$DEVICE" ]] || die "l'appareil $ANDROID_SERIAL est absent ou non autorisé. Déverrouillez-le et autorisez le débogage USB."
    else
        DEVICE="$(printf '%s\n' "$devices" | awk '$2 == "device" && $1 !~ /^emulator-/ && !found {print $1; found=1}')"
        if [[ -z "$DEVICE" ]]; then
            if [[ "$devices" == *unauthorized* ]]; then
                die "téléphone détecté mais non autorisé. Déverrouillez-le et acceptez « Autoriser le débogage USB ». Si la fenêtre manque, débranchez puis rebranchez le câble USB."
            fi
            die "aucun téléphone autorisé. Vérifiez le câble et le débogage USB. Sortie ADB : $devices"
        fi
    fi
    echo "[phone] Téléphone sélectionné : $DEVICE"
}
