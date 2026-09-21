#!/bin/bash
#
# run-lg3d.sh [<options>] [-- <extra-gradle-args>]
#
# Launch the Project Looking Glass 3D desktop in "development mode" (a normal
# window under the host window system) via the Gradle :lg3d-core:run task.
#
# This is the Gradle-port equivalent of the legacy lg3d-core/src/devscripts/lg3d-dev
# launcher. It pins the JDK 21 toolchain the project is built with, makes sure a
# DISPLAY is available, and assembles the runtime resources/ tree (icons,
# wallpapers, background manager) before starting the display server.

set -euo pipefail

# Repository root = directory this script lives in.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

# --- JDK selection -----------------------------------------------------------
# Gradle 8.14 cannot run on Java 25+, and Main.java enforces a compile/runtime
# Java version match, so we need the JDK 21 toolchain used to build.
DEFAULT_JDK21="/home/fedora/.jdks/jdk-21.0.12.1+1"

jdk_is_21() {
    local home="$1"
    [ -x "${home}/bin/java" ] || return 1
    "${home}/bin/java" -version 2>&1 | grep -q 'version "21'
}

if [ -n "${JAVA_HOME:-}" ] && jdk_is_21 "${JAVA_HOME}"; then
    : # honour a caller-provided JDK 21
elif jdk_is_21 "${DEFAULT_JDK21}"; then
    export JAVA_HOME="${DEFAULT_JDK21}"
else
    echo "ERROR: no JDK 21 found." >&2
    echo "  Set JAVA_HOME to a JDK 21 install, or install one at:" >&2
    echo "    ${DEFAULT_JDK21}" >&2
    exit 1
fi

# --- Display -----------------------------------------------------------------
# Dev mode renders into an ordinary window on the host X display.
if [ -z "${DISPLAY:-}" ]; then
    export DISPLAY=":0"
fi

usage() {
    cat <<EOF

run-lg3d.sh [<options>] [-- <extra-gradle-args>]

    -b, --background3d   Use the 3D model desktop background (pinguin.j3f,
                         loaded via the in-tree J3fLoader) instead of the
                         default image backgrounds.
    -c, --clean          Run ':lg3d-core:clean' before launching.
    -r, --rebuild        Force the runtime resources/ tree to be reassembled
                         (reruns only the :lg3d-core:runtimeResources task).
    -x, --compositor     Run lg3d as its own X11 window manager + compositor
                         (Composite/Damage/XTest). Claims SubstructureRedirect
                         on DISPLAY; start it with no other window manager
                         running on that display. Passes -Pcompositor to Gradle.
    -h, --help           Print this help.

Anything after '--' is passed straight to the Gradle invocation, e.g.:
    ./run-lg3d.sh -- --info

Environment:
    JAVA_HOME  JDK 21 toolchain (auto-detected if unset).
    DISPLAY    X display to render into (defaults to :0).

EOF
    exit "${1:-0}"
}

# --- Option parsing ----------------------------------------------------------
BACKGROUND3D=false
COMPOSITOR=false
DO_CLEAN=false
DO_REBUILD=false
EXTRA_ARGS=()

while [ $# -gt 0 ]; do
    case "$1" in
        -b|--background3d) BACKGROUND3D=true ;;
        -x|--compositor)   COMPOSITOR=true ;;
        -c|--clean)        DO_CLEAN=true ;;
        -r|--rebuild)      DO_REBUILD=true ;;
        -h|--help)         usage 0 ;;
        --)                shift; EXTRA_ARGS+=("$@"); break ;;
        -*)                echo "Unknown option: $1" >&2; usage 1 ;;
        *)                 EXTRA_ARGS+=("$1") ;;
    esac
    shift
done

GRADLE_ARGS=(":lg3d-core:run" "--console=plain")
if [ "${BACKGROUND3D}" = true ]; then
    GRADLE_ARGS+=("-Pbackground3d")
fi
if [ "${COMPOSITOR}" = true ]; then
    GRADLE_ARGS+=("-Pcompositor")
fi
if [ "${#EXTRA_ARGS[@]}" -gt 0 ]; then
    GRADLE_ARGS+=("${EXTRA_ARGS[@]}")
fi

echo "JAVA_HOME : ${JAVA_HOME}"
echo "DISPLAY   : ${DISPLAY}"
if [ "${COMPOSITOR}" = true ]; then
    echo "MODE      : X11 compositor / window manager (-Pcompositor)"
    echo "WARNING   : lg3d will claim SubstructureRedirect on ${DISPLAY} and act"
    echo "            as the window manager. No other WM may already hold it."
fi

if [ "${DO_CLEAN}" = true ]; then
    echo "Cleaning :lg3d-core ..."
    ./gradlew ":lg3d-core:clean" --console=plain
fi

if [ "${DO_REBUILD}" = true ]; then
    echo "Reassembling runtime resources/ tree ..."
    ./gradlew ":lg3d-core:runtimeResources" --rerun --console=plain
fi

echo "Launching LG3D desktop: ./gradlew ${GRADLE_ARGS[*]}"
exec ./gradlew "${GRADLE_ARGS[@]}"
