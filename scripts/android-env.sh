#!/usr/bin/env bash
# Sourced by phone.sh; all downloaded tools and caches stay in the project.

die() { echo "[rythmo] Erreur : $*" >&2; exit 1; }

download() {
    command -v curl >/dev/null || die "curl est requis pour installer les outils manquants."
    curl --fail --location --retry 3 --connect-timeout 30 --output "$2.part" "$1" \
        || die "échec du téléchargement : $1"
    mv "$2.part" "$2"
}

ensure_java() {
    local candidate version system_jdk=""
    if command -v javac >/dev/null; then
        system_jdk="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
    fi
    for candidate in "${JAVA_HOME:-}" "$system_jdk" "$ROOT_DIR/.tools/jdk"; do
        [[ -n "$candidate" && -x "$candidate/bin/javac" ]] || continue
        version="$("$candidate/bin/javac" -version 2>&1)"
        if [[ "$version" == 'javac 17.'* ]]; then
            export JAVA_HOME="$candidate"
            export PATH="$JAVA_HOME/bin:$PATH"
            return
        fi
    done

    [[ "$(uname -m)" == x86_64 ]] || die "Installez un JDK 17 Linux adapté à votre architecture, puis définissez JAVA_HOME."
    echo "[phone] Installation locale de Java 17..."
    local archive="$ROOT_DIR/.tools/downloads/jdk17.tar.gz" staging
    download 'https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse' "$archive"
    staging="$(mktemp -d "$ROOT_DIR/.tools/jdk-install.XXXXXX")"
    tar -xzf "$archive" -C "$staging" --strip-components=1 || die "extraction du JDK impossible"
    "$staging/bin/javac" -version || die "le JDK téléchargé ne fonctionne pas"
    # Keep an incomplete/older installation recoverable instead of deleting it.
    if [[ -e "$ROOT_DIR/.tools/jdk" ]]; then
        mv "$ROOT_DIR/.tools/jdk" "${staging}.previous"
    fi
    mv "$staging" "$ROOT_DIR/.tools/jdk"
    export JAVA_HOME="$ROOT_DIR/.tools/jdk"
    export PATH="$JAVA_HOME/bin:$PATH"
}

linux_sdk_ready() {
    [[ -f "$1/platforms/android-35/android.jar" && -x "$1/build-tools/36.0.0/aapt" && ! -f "$1/build-tools/36.0.0/aapt.exe" ]]
}

ensure_linux_sdk() {
    local candidate sdk=""
    for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$ROOT_DIR/.tools/android-sdk" "$HOME/Android/Sdk"; do
        if [[ -n "$candidate" ]] && linux_sdk_ready "$candidate"; then
            sdk="$candidate"
            break
        fi
    done
    if [[ -z "$sdk" ]]; then
        sdk="$ROOT_DIR/.tools/android-sdk"
        command -v unzip >/dev/null || die "unzip est requis pour installer le SDK Linux."
        if [[ ! -x "$sdk/cmdline-tools/latest/bin/sdkmanager" ]]; then
            echo "[phone] Installation du SDK Android Linux pour compiler dans WSL..."
            local archive="$ROOT_DIR/.tools/downloads/commandline-tools.zip" staging
            download 'https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip' "$archive"
            staging="$(mktemp -d "$ROOT_DIR/.tools/sdk-install.XXXXXX")"
            unzip -q "$archive" -d "$staging" || die "extraction du SDK impossible"
            mkdir -p "$sdk/cmdline-tools"
            if [[ -e "$sdk/cmdline-tools/latest" ]]; then
                mv "$sdk/cmdline-tools/latest" "$staging/previous"
            fi
            mv "$staging/cmdline-tools" "$sdk/cmdline-tools/latest"
        fi
        echo "[phone] Installation des composants Android 35 et acceptation des licences SDK..."
        # Ignore SIGPIPE from yes after sdkmanager has consumed the licence answers.
        if ! (set +o pipefail; yes 2>/dev/null | "$sdk/cmdline-tools/latest/bin/sdkmanager" \
            --sdk_root="$sdk" 'platforms;android-35' 'build-tools;36.0.0' 'platform-tools'); then
            die "installation du SDK Linux impossible"
        fi
        linux_sdk_ready "$sdk" || die "le SDK Linux est incomplet après installation"
    fi
    export ANDROID_HOME="$sdk"
    export ANDROID_SDK_ROOT="$sdk"
    # local.properties takes precedence over the environment; retain other settings.
    local properties_tmp
    properties_tmp="$(mktemp "$ROOT_DIR/local.properties.XXXXXX")"
    if [[ -f "$ROOT_DIR/local.properties" ]]; then
        sed '/^[[:space:]]*sdk\.dir[[:space:]]*=/d' "$ROOT_DIR/local.properties" > "$properties_tmp"
    fi
    printf 'sdk.dir=%s\n' "$sdk" >> "$properties_tmp"
    mv "$properties_tmp" "$ROOT_DIR/local.properties"
    echo "[phone] Java : $JAVA_HOME · SDK Linux : $sdk"
}

ensure_build_environment() {
    mkdir -p "$ROOT_DIR/.tools/downloads"
    export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT_DIR/.gradle-user}"
    export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$ROOT_DIR/.android-user}"
    mkdir -p "$GRADLE_USER_HOME" "$ANDROID_USER_HOME"
    ensure_java
    ensure_linux_sdk
}
