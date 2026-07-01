#!/bin/bash

set -e

GIT_ROOT="$(git rev-parse --show-toplevel)"
SDK_DIR="$GIT_ROOT/../matrix-rust-sdk"
COMPONENTS_DIR="$GIT_ROOT/../matrix-rust-components-kotlin"

if [ ! -d "$SDK_DIR" ]; then
    echo "SDK not found at $SDK_DIR"
    exit 1
fi
if [ ! -d "$COMPONENTS_DIR" ]; then
    echo "SDK components not found at $COMPONENTS_DIR"
    exit 1
fi

if [ -z "$JAVA_HOME" ]; then
    # Gradle/AGP need a JDK in their supported range; the system default JDK
    # (e.g. via `java`/`archlinux-java`) may be too new. Prefer Android
    # Studio's bundled JBR if present, else fall back to a known-good JDK.
    for candidate in \
        /opt/android-studio/jbr \
        "$HOME/android-studio/jbr" \
        /usr/share/android-studio/jbr \
        /usr/lib/jvm/java-21-openjdk \
        /usr/lib/jvm/java-17-openjdk \
        ; do
        if [ -d "$candidate" ]; then
            JAVA_HOME="$candidate"
            break
        fi
    done
    if [ -n "${JAVA_HOME:-}" ]; then
        export JAVA_HOME
    else
        unset JAVA_HOME
        echo "Warn: JAVA_HOME not set"
    fi
fi
if [ -z "$ANDROID_NDK_HOME" ]; then
    SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
    # Pick the newest installed NDK that actually has a valid source.properties,
    # skipping incomplete/broken installs (e.g. an interrupted SDK Manager download).
    for candidate in $(ls -d "$SDK_ROOT"/ndk/*/ 2>/dev/null | sort -rV); do
        candidate="${candidate%/}"
        if [ -f "$candidate/source.properties" ]; then
            ANDROID_NDK_HOME="$candidate"
            break
        fi
    done
    if [ -n "${ANDROID_NDK_HOME:-}" ]; then
        export ANDROID_NDK_HOME
    else
        unset ANDROID_NDK_HOME
        echo "Warn: ANDROID_NDK_HOME not set"
    fi
fi
echo "JAVA_HOME=$JAVA_HOME"
echo "ANDROID_NDK_HOME=$ANDROID_NDK_HOME"

RUSTFLAGS="$RUSTFLAGS --remap-path-prefix=$HOME/.cargo/=.cargo/"
RUSTFLAGS="$RUSTFLAGS --remap-path-prefix=$(realpath "$SDK_DIR")/=."
RUSTFLAGS="$RUSTFLAGS --remap-path-prefix=$HOME/.rustup/=.rustup/"
export RUSTUP_TOOLCHAIN=1.96.0
export RUSTFLAGS
echo "RUSTFLAGS=$RUSTFLAGS"

cd "$COMPONENTS_DIR"

./scripts/build.sh -p "$SDK_DIR" -m sdk -o "$GIT_ROOT"/libraries/rustsdk/matrix-rust-sdk.aar "$@"
