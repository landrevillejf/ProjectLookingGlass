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
    -2, --2d             Run the conventional Swing (2D) desktop instead of the
                         3D one: an MDI JDesktopPane with a Swing taskbar and
                         start menu, hosting each application in an internal
                         frame (JInternalFrame) inside the desktop window. This
                         is also what a machine without Java 3D gets
                         automatically (after a prompt).
                         Passes -Pdesktop2d to Gradle.
    -w, --swing          Run the conventional Swing desktop: the same MDI shell
                         as --2d, each application in a JInternalFrame inside
                         the desktop's JDesktopPane (so windows stay integrated
                         and minimise into the desktop), under the Metal look
                         and feel instead of the host system look.
                         Passes -PdesktopSwing to Gradle.
    -c, --clean          Run ':lg3d-core:clean' before launching.
    -r, --rebuild        Force the runtime resources/ tree to be reassembled
                         (reruns only the :lg3d-core:runtimeResources task).
    -x, --compositor     Run lg3d as its own X11 window manager + compositor
                         (Composite/Damage/XTest). Claims SubstructureRedirect
                         on DISPLAY; start it with no other window manager
                         running on that display. Passes -Pcompositor to Gradle.
        --nested [DISP]  Run the compositor inside a nested Xephyr X server so
                         real external X11 apps (e.g. firefox) launched from the
                         desktop are composited into the 3D scene WITHOUT
                         leaving your Wayland session. Starts Xephyr (default
                         :1), then runs lg3d as its WM/compositor there.
                         Implies -x. Needs Xephyr installed:
                           Fedora/Red Hat: sudo dnf install xorg-x11-server-Xephyr
                           Debian/Ubuntu:  sudo apt install xserver-xephyr
                         Passes -Plgserverdisplay=<DISP> to Gradle.
        --display <DISP> X display the compositor claims and external apps are
                         launched on (sets lg.lgserverdisplay + the JVM DISPLAY).
                         Defaults to :0 for -x, :1 for --nested.
    -s, --swing-app <fqcn> [args...]
                         Run a conventional Swing application's main() inside
                         the desktop JVM so its windows are captured into the
                         3D scene (JFrames become desktop windows; JOptionPane /
                         JFileChooser / popups render in-scene). TERMINAL: every
                         token after <fqcn> is passed to the app's main(). Put
                         --swing-app-cp BEFORE this option. Passes -PswingApp.
        --swing-app-cp <path[:path...]>
                         Add the Swing app's classes/jar to the desktop run
                         classpath (File.pathSeparator-separated). Passes
                         -PswingAppCp. Must precede --swing-app.
    -h, --help           Print this help.

Anything after '--' is passed straight to the Gradle invocation, e.g.:
    ./run-lg3d.sh -- --info

Environment:
    JAVA_HOME      JDK 21 toolchain (auto-detected if unset).
    DISPLAY        X display to render into (defaults to :0).
    XEPHYR_SCREEN  Nested screen size for --nested (default 1280x800).
    XEPHYR_ARGS    Full Xephyr arg string for --nested (overrides the screen
                   default; e.g. "-screen 1600x900 -gl" if Java 3D needs GLX).

EOF
    exit "${1:-0}"
}

# --- Option parsing ----------------------------------------------------------
BACKGROUND3D=false
COMPOSITOR=false
NESTED=false
LG_DISPLAY=""
DESKTOP2D=false
DESKTOP_SWING=false
DO_CLEAN=false
DO_REBUILD=false
SWING_APP=""
SWING_APP_ARGS=""
SWING_APP_CP=""
EXTRA_ARGS=()

while [ $# -gt 0 ]; do
    case "$1" in
        -b|--background3d) BACKGROUND3D=true ;;
        -2|--2d)           DESKTOP2D=true ;;
        -w|--swing)        DESKTOP_SWING=true ;;
        -x|--compositor)   COMPOSITOR=true ;;
        --nested)
                           NESTED=true
                           # optional explicit display, e.g. --nested :2
                           case "${2:-}" in
                               :*) LG_DISPLAY="$2"; shift ;;
                           esac
                           ;;
        --display)         LG_DISPLAY="${2:-}"; shift ;;
        -c|--clean)        DO_CLEAN=true ;;
        -r|--rebuild)      DO_REBUILD=true ;;
        --swing-app-cp)    SWING_APP_CP="${2:-}"; shift ;;
        -s|--swing-app)
                           SWING_APP="${2:-}"
                           if [ $# -ge 2 ]; then shift 2; else shift; fi
                           # Everything after the main class is the app's args.
                           SWING_APP_ARGS="$*"
                           break
                           ;;
        -h|--help)         usage 0 ;;
        --)                shift; EXTRA_ARGS+=("$@"); break ;;
        -*)                echo "Unknown option: $1" >&2; usage 1 ;;
        *)                 EXTRA_ARGS+=("$1") ;;
    esac
    shift
done

# --nested implies the compositor plus a nested X display for lg3d to claim.
if [ "${NESTED}" = true ]; then
    COMPOSITOR=true
    [ -n "${LG_DISPLAY}" ] || LG_DISPLAY=":1"
fi

# Start a nested Xephyr X server for --nested so lg3d can be its window manager
# without leaving the host (Wayland) session. Xephyr presents the nested screen
# as an ordinary window on the host; lg3d's 3D desktop renders inside it, and
# external apps launched from the desktop land on the same nested display and are
# composited into the scene.
start_xephyr() {
    local disp="$1"
    local num="${disp#:}"; num="${num%%.*}"
    local sock="/tmp/.X11-unix/X${num}"
    if [ -S "${sock}" ]; then
        echo "Xephyr: an X server already listens on ${disp}; reusing it."
        return 0
    fi
    if ! command -v Xephyr >/dev/null 2>&1; then
        echo "ERROR: --nested needs Xephyr, which is not installed." >&2
        echo "  Fedora/Red Hat: sudo dnf install xorg-x11-server-Xephyr" >&2
        echo "  Debian/Ubuntu:  sudo apt install xserver-xephyr" >&2
        exit 1
    fi
    local args="${XEPHYR_ARGS:--screen ${XEPHYR_SCREEN:-1280x800}}"
    echo "Starting Xephyr on ${disp} (${args}) ..."
    # shellcheck disable=SC2086
    Xephyr "${disp}" ${args} >/tmp/xephyr-lg3d.log 2>&1 &
    XEPHYR_PID=$!
    local i
    for i in $(seq 1 50); do
        [ -S "${sock}" ] && break
        sleep 0.1
    done
    if [ ! -S "${sock}" ]; then
        echo "ERROR: Xephyr did not come up on ${disp}; see /tmp/xephyr-lg3d.log" >&2
        exit 1
    fi
    echo "Xephyr is up on ${disp} (pid ${XEPHYR_PID})."
}

if [ "${NESTED}" = true ]; then
    start_xephyr "${LG_DISPLAY}"
fi

GRADLE_ARGS=(":lg3d-core:run" "--console=plain")
if [ "${BACKGROUND3D}" = true ]; then
    GRADLE_ARGS+=("-Pbackground3d")
fi
if [ "${COMPOSITOR}" = true ]; then
    GRADLE_ARGS+=("-Pcompositor")
fi
if [ -n "${LG_DISPLAY}" ]; then
    GRADLE_ARGS+=("-Plgserverdisplay=${LG_DISPLAY}")
fi
if [ "${DESKTOP2D}" = true ]; then
    GRADLE_ARGS+=("-Pdesktop2d")
fi
if [ "${DESKTOP_SWING}" = true ]; then
    GRADLE_ARGS+=("-PdesktopSwing")
fi
if [ -n "${SWING_APP}" ]; then
    SWING_SPEC="${SWING_APP}"
    if [ -n "${SWING_APP_ARGS}" ]; then
        SWING_SPEC="${SWING_SPEC} ${SWING_APP_ARGS}"
    fi
    GRADLE_ARGS+=("-PswingApp=${SWING_SPEC}")
    if [ -n "${SWING_APP_CP}" ]; then
        GRADLE_ARGS+=("-PswingAppCp=${SWING_APP_CP}")
    fi
fi
if [ "${#EXTRA_ARGS[@]}" -gt 0 ]; then
    GRADLE_ARGS+=("${EXTRA_ARGS[@]}")
fi

echo "JAVA_HOME : ${JAVA_HOME}"
echo "DISPLAY   : ${DISPLAY}"
if [ "${DESKTOP2D}" = true ]; then
    echo "MODE      : conventional Swing 2D desktop (-Pdesktop2d)"
fi
if [ "${DESKTOP_SWING}" = true ]; then
    echo "MODE      : conventional Swing desktop, Metal look and feel (-PdesktopSwing)"
fi
if [ "${COMPOSITOR}" = true ]; then
    if [ "${NESTED}" = true ]; then
        echo "MODE      : X11 compositor / WM in nested Xephyr (-Pcompositor -Plgserverdisplay=${LG_DISPLAY})"
        echo "NOTE      : lg3d owns ${LG_DISPLAY}; external apps launched from the"
        echo "            desktop are composited into the 3D scene."
    else
        echo "MODE      : X11 compositor / window manager (-Pcompositor)"
        echo "WARNING   : lg3d will claim SubstructureRedirect on ${LG_DISPLAY:-${DISPLAY}}"
        echo "            and act as the window manager. No other WM may hold it."
    fi
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
if [ "${NESTED}" = true ]; then
    # Do not exec: the nested Xephyr must be torn down when lg3d exits.
    set +e
    ./gradlew "${GRADLE_ARGS[@]}"
    rc=$?
    set -e
    if [ -n "${XEPHYR_PID:-}" ]; then
        echo "Stopping Xephyr (pid ${XEPHYR_PID}) ..."
        kill "${XEPHYR_PID}" 2>/dev/null || true
    fi
    exit "${rc}"
fi
exec ./gradlew "${GRADLE_ARGS[@]}"
