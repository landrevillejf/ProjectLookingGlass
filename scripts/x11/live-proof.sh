#!/usr/bin/env bash
#
# live-proof.sh - Stage-4 live end-to-end proof harness for the lg3d native-X11
#                 compositor, mapped to docs/lfs-x11-contract.md section 5.
#
# WHAT THIS DOES
#   Automates the read-only acceptance criteria (extension probe, xdpyinfo
#   cross-check, GLX direct-rendering), then orchestrates a live lg3d compositor
#   session and walks the operator through the interactive criteria (sole-WM /
#   no-BadAccess, an external X client composited as a NativeWindow3D quad and
#   driven by PHYSICAL input, and real Alt+Tab ownership). It corroborates the
#   human answers against the INFO log markers emitted by X11WindowManager,
#   X11Compositor and X11InputForwarder, and writes a section-5 evidence file.
#
# HARD GUARANTEES (project policy + docs/lfs-x11-contract.md section 4)
#   * This script NEVER starts an X server (no Xephyr, no Xvfb, no Xorg). It only
#     CONNECTS to an already-running display and aborts if that display's X
#     socket is absent.
#   * This script NEVER injects synthetic input (no xdotool / xte / AWT Robot).
#     The input-forwarding criterion is proven with the operator's PHYSICAL
#     pointer and keyboard only.
#   * This script NEVER takes or depends on screenshots. Compositing is proven by
#     human observation plus structured logs.
#
# HOW TO OBTAIN A TARGET DISPLAY (operator, before running this)
#   Production (LFS): lg3d IS the session on Xorg :0  ->  live-proof.sh -d :0
#   Dev box (Fedora Wayland): start a BARE Xorg on a spare VT, e.g.
#       Xorg :1 vt2 -nolisten tcp
#   then run this from any terminal and observe/interact on that VT (Ctrl-Alt-F2):
#       scripts/x11/live-proof.sh -d :1
#
# USAGE
#   scripts/x11/live-proof.sh [-d <display>] [-c <client-cmd>] [-t <boot-secs>]
#                             [--probes-only] [--keep] [-h]
#
set -uo pipefail

PROG="$(basename "$0")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPO_ROOT}"

# --- defaults ----------------------------------------------------------------
DISPLAY_TARGET="${LG_DISPLAY:-:0}"
CLIENT="${LG_PROOF_CLIENT:-xeyes}"
CLIENT_ARGS=""
BOOT_TIMEOUT=180          # seconds to wait for lg3d to claim the WM/compositor
PROBES_ONLY=false
KEEP=false                # keep lg3d + client running after the summary

usage() {
    cat <<EOF
$PROG - Stage-4 live end-to-end proof for the lg3d X11 compositor (contract section 5)

  -d, --display <dpy>   Target an ALREADY-RUNNING X display (default :0). This
                        script never starts one. On a Wayland dev box use a bare
                        Xorg on a spare VT, e.g. 'Xorg :1 vt2 -nolisten tcp',
                        then '-d :1'.
  -c, --client <cmd>    External X client to composite (default: xeyes). Use
                        'xterm' to also exercise keyboard forwarding.
  -t, --timeout <secs>  Seconds to wait for lg3d to boot (default: $BOOT_TIMEOUT).
      --probes-only     Run only the read-only criteria (1,2,4); do not launch
                        lg3d. Useful on a headless/orchestration terminal.
      --keep            Leave lg3d and the client running after the summary.
  -h, --help            This help.

Environment:
  JAVA_HOME             JDK 21 toolchain (auto-detected if unset; Gradle 8.14
                        cannot run on Java 25+).
  LG3D_EVIDENCE_DIR     Where to write logs + the evidence file
                        (default: /tmp/lg3d-x11-live-proof).
EOF
    exit "${1:-0}"
}

while [ $# -gt 0 ]; do
    case "$1" in
        -d|--display)  DISPLAY_TARGET="${2:-}"; shift ;;
        -c|--client)   CLIENT="${2:-}"; shift ;;
        -t|--timeout)  BOOT_TIMEOUT="${2:-$BOOT_TIMEOUT}"; shift ;;
        --probes-only) PROBES_ONLY=true ;;
        --keep)        KEEP=true ;;
        -h|--help)     usage 0 ;;
        *)             echo "$PROG: unknown option: $1" >&2; usage 1 ;;
    esac
    shift
done

[ -n "$DISPLAY_TARGET" ] || { echo "$PROG: empty --display" >&2; exit 1; }

# --- evidence dir ------------------------------------------------------------
EVIDENCE_DIR="${LG3D_EVIDENCE_DIR:-/tmp/lg3d-x11-live-proof}"
RUN_DIR="${EVIDENCE_DIR}/$(date +%Y%m%d-%H%M%S)-${DISPLAY_TARGET#:}"
mkdir -p "$RUN_DIR"
RESULT_FILE="${RUN_DIR}/RESULT.tsv"
EVIDENCE_FILE="${RUN_DIR}/section5-evidence.md"
: > "$RESULT_FILE"

hr()  { printf '%s\n' "------------------------------------------------------------"; }
say() { printf '%s\n' "$*"; }

# record <id> <status> <detail>
record() { printf '%s\t%s\t%s\n' "$1" "$2" "$3" >> "$RESULT_FILE"; }

# --- JDK 21 detection (Gradle 8.14 cannot run on Java 25+) -------------------
jdk_is_21() {
    local home="$1"
    [ -x "${home}/bin/java" ] || return 1
    "${home}/bin/java" -version 2>&1 | grep -q 'version "21'
}
detect_jdk21() {
    if [ -n "${JAVA_HOME:-}" ] && jdk_is_21 "${JAVA_HOME}"; then return 0; fi
    local cand
    for cand in /home/fedora/.jdks/jdk-21.0.12.1+1 \
                "$(/usr/libexec/java_home -v 21 2>/dev/null || true)"; do
        if [ -n "$cand" ] && jdk_is_21 "$cand"; then export JAVA_HOME="$cand"; return 0; fi
    done
    return 1
}

# --- prohibition guard: the target display must already exist ----------------
x_socket_for() {  # ":1" -> /tmp/.X11-unix/X1
    local d="${1#:}"; d="${d%%.*}"; printf '/tmp/.X11-unix/X%s' "$d"
}

have() { command -v "$1" >/dev/null 2>&1; }

# --- tty availability for the interactive criteria ---------------------------
TTY_IN=""
if [ -r /dev/tty ] && [ -w /dev/tty ]; then TTY_IN="/dev/tty"; fi

ask() {  # ask "<prompt>" -> echoes y/n ; returns 1 if no tty
    [ -n "$TTY_IN" ] || { echo ""; return 1; }
    local ans
    printf '%s [y/N] ' "$1" > /dev/tty
    IFS= read -r ans < /dev/tty || { echo ""; return 1; }
    case "${ans,,}" in y|yes) echo "y" ;; *) echo "n" ;; esac
}

# --- live session (criteria 3, 5, 6) -----------------------------------------
finish_session() {  # finish_session <lg3d_pid> <client_pid>
    local lg3d_pid="$1" client_pid="${2:-}"
    say ""
    say "Session processes: run-lg3d.sh PID ${lg3d_pid}; client PID ${client_pid:-n/a}."
    say "The lg3d JVM is forked by Gradle, so to stop everything cleanly:"
    say "    kill ${client_pid:-<client-pid>} ${lg3d_pid} 2>/dev/null; pkill -P ${lg3d_pid} 2>/dev/null"
    say "    pgrep -af ':lg3d-core:run'    # then kill the lg3d Main JVM if still present"
    if [ "$KEEP" != true ] && [ -n "$TTY_IN" ]; then
        local s; s="$(ask "Stop the proof session now (lg3d + ${CLIENT})?")"
        if [ "$s" = y ]; then
            [ -n "$client_pid" ] && kill "$client_pid" 2>/dev/null
            pkill -P "$lg3d_pid" 2>/dev/null
            kill "$lg3d_pid" 2>/dev/null
            say "    sent stop signals (the forked lg3d JVM may still need the pgrep hint above)."
        else
            say "    leaving the session running."
        fi
    else
        say "    (leaving the session running)"
    fi
}

run_live_session() {
    say ""; hr
    say "[3/6] Sole-WM  +  [5/6] End-to-end  +  [6/6] Key ownership (live lg3d session)"
    hr
    say "NOTE: lg3d will claim ${DISPLAY_TARGET} as its window manager + compositor."
    say "      Be PHYSICALLY at the VT running Xorg ${DISPLAY_TARGET} to observe the scene"
    say "      and to drive criteria 5 and 6 with the real mouse/keyboard."
    [ -n "$TTY_IN" ] || say "      (no interactive tty: criteria 5 and 6 will be recorded as MANUAL.)"
    say ""

    local LG3D_LOG="${RUN_DIR}/03-lg3d-compositor.log"
    local CLIENT_LOG="${RUN_DIR}/05-client-${CLIENT//[^A-Za-z0-9]/_}.log"

    # Launch lg3d as WM/compositor on the target display. run-lg3d.sh --nested
    # exports DISPLAY, implies -Pcompositor and passes -Plgserverdisplay. This
    # NEVER starts an X server; it connects to the one already on ${DISPLAY_TARGET}.
    DISPLAY="$DISPLAY_TARGET" ./run-lg3d.sh --nested "$DISPLAY_TARGET" > "$LG3D_LOG" 2>&1 &
    local LG3D_PID=$!
    say "lg3d launched (run-lg3d.sh PID ${LG3D_PID}); log: ${LG3D_LOG}"
    say "Waiting up to ${BOOT_TIMEOUT}s for the SubstructureRedirect claim + compositor..."

    local deadline=$(( $(date +%s) + BOOT_TIMEOUT ))
    local badaccess=0 comp_up=0 wm_up=0 exited=0
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if grep -q "Failed to access root window" "$LG3D_LOG" 2>/dev/null \
           || grep -q "Another WM is running" "$LG3D_LOG" 2>/dev/null; then
            badaccess=1; break
        fi
        grep -q "X Window Manager initialization completed against display" "$LG3D_LOG" 2>/dev/null && wm_up=1
        if grep -q "X11 compositor active on display" "$LG3D_LOG" 2>/dev/null; then comp_up=1; break; fi
        kill -0 "$LG3D_PID" 2>/dev/null || { exited=1; break; }
        sleep 2
    done

    # --- criterion 3: sole WM, no BadAccess ---
    if [ "$badaccess" -eq 1 ]; then
        record 3 FAIL "BadAccess on the SubstructureRedirect claim ('Another WM is running?'): a competing WM/compositor owns ${DISPLAY_TARGET}, or it is XWayland. See $(basename "$LG3D_LOG")."
        say "    [3] => FAIL: BadAccess - another WM owns ${DISPLAY_TARGET} (or it is XWayland)."
    elif [ "$wm_up" -eq 1 ]; then
        record 3 PASS "lg3d claimed SubstructureRedirect with no BadAccess ('X Window Manager initialization completed'); compositor-active marker=${comp_up}. Log: $(basename "$LG3D_LOG")."
        say "    [3] => PASS: sole WM, no BadAccess (compositor active=${comp_up})."
    elif [ "$exited" -eq 1 ]; then
        record 3 FAIL "lg3d exited before claiming the WM; see tail of $(basename "$LG3D_LOG")."
        say "    [3] => FAIL: lg3d exited early. Last log lines:"
        tail -n 20 "$LG3D_LOG" | sed 's/^/        /'
    else
        record 3 FAIL "timed out after ${BOOT_TIMEOUT}s waiting for the WM claim; see $(basename "$LG3D_LOG")."
        say "    [3] => FAIL: timed out waiting for lg3d to claim the WM. Last log lines:"
        tail -n 20 "$LG3D_LOG" | sed 's/^/        /'
    fi

    if [ "$wm_up" -ne 1 ]; then
        record 5 MANUAL "not attempted: lg3d did not come up as WM (criterion 3 failed)"
        record 6 MANUAL "not attempted: lg3d did not come up as WM (criterion 3 failed)"
        finish_session "$LG3D_PID" ""
        return
    fi

    # --- launch a REAL external X client (never synthetic input) ---
    say ""
    say "Launching external X client '${CLIENT}' on ${DISPLAY_TARGET} (a real X client;"
    say "this harness injects no synthetic input). Log: ${CLIENT_LOG}"
    DISPLAY="$DISPLAY_TARGET" ${CLIENT} ${CLIENT_ARGS} > "$CLIENT_LOG" 2>&1 &
    local CLIENT_PID=$!
    sleep 3
    if ! kill -0 "$CLIENT_PID" 2>/dev/null; then
        say "    WARNING: '${CLIENT}' exited immediately - is it installed on the host?"
        tail -n 10 "$CLIENT_LOG" | sed 's/^/        /'
    fi

    # --- criterion 5: composited in-scene + physical input forwarding ---
    say ""; hr
    say "[5/6] End-to-end: is '${CLIENT}' composited in-scene and driven by PHYSICAL input?"
    hr
    local a5 b5 fwd=0
    a5="$(ask "On ${DISPLAY_TARGET}'s VT, is '${CLIENT}' shown as a textured quad INSIDE the 3D scene (not a plain overlapping X window)?")"
    b5="$(ask "Using ONLY the physical mouse/keyboard, does '${CLIENT}' respond when you interact with its quad (xeyes tracks the cursor / xterm receives keystrokes)?")"
    grep -q "X11 input forwarder attached to window" "$LG3D_LOG" 2>/dev/null && fwd=1
    if [ -z "$a5" ]; then
        record 5 MANUAL "no interactive tty; verify by hand that '${CLIENT}' is composited as a NativeWindow3D quad and PHYSICAL input is forwarded. forwarder-attached marker=${fwd}. Logs: $(basename "$LG3D_LOG"), $(basename "$CLIENT_LOG")."
        say "    [5] => MANUAL (no tty). Forwarder-attached marker present: ${fwd}."
    elif [ "$a5" = y ] && [ "$b5" = y ]; then
        record 5 PASS "operator confirms '${CLIENT}' composited in-scene AND driven by physical input; forwarder-attached marker=${fwd}. Logs: $(basename "$LG3D_LOG"), $(basename "$CLIENT_LOG")."
        say "    [5] => PASS (composited + physical input; forwarder marker=${fwd})."
    else
        record 5 FAIL "operator: composited=${a5:-n}, physical-input=${b5:-n} (forwarder marker=${fwd}). Logs: $(basename "$LG3D_LOG"), $(basename "$CLIENT_LOG")."
        say "    [5] => FAIL (composited=${a5:-n}, input=${b5:-n}, forwarder marker=${fwd})."
    fi

    # --- criterion 6: real Alt+Tab ownership ---
    say ""; hr; say "[6/6] Key ownership: does lg3d receive the REAL Alt+Tab?"; hr
    local a6
    a6="$(ask "Press the physical Alt+Tab. Does lg3d's switcher receive it (cycle apps) rather than a host shell swallowing it?")"
    if [ -z "$a6" ]; then
        record 6 MANUAL "no interactive tty; verify by hand that a real Alt+Tab reaches lg3d's switcher."
        say "    [6] => MANUAL (no tty)."
    elif [ "$a6" = y ]; then
        record 6 PASS "operator confirms a real Alt+Tab reaches lg3d's switcher."
        say "    [6] => PASS."
    else
        record 6 FAIL "operator reports Alt+Tab not received by lg3d (swallowed by a host shell / not grabbed)."
        say "    [6] => FAIL."
    fi

    finish_session "$LG3D_PID" "$CLIENT_PID"
}

# --- summary + evidence ------------------------------------------------------
write_evidence() {  # write_evidence <passes> <fails> <manuals>
    local passes="$1" fails="$2" manuals="$3" id status detail
    {
        echo "# lg3d X11 compositor - contract section 5 compliance evidence"
        echo ""
        echo "- Date: $(date -Is)"
        echo "- Host: $(uname -srm)"
        echo "- Target display: \`${DISPLAY_TARGET}\`"
        echo "- External client: \`${CLIENT} ${CLIENT_ARGS}\`"
        echo "- JAVA_HOME: \`${JAVA_HOME:-<unset>}\`"
        echo "- Harness: \`scripts/x11/live-proof.sh\`"
        echo ""
        echo "| section 5 criterion | Result | Detail |"
        echo "|---|---|---|"
        while IFS=$'\t' read -r id status detail; do
            [ -n "$id" ] || continue
            echo "| ${id} | ${status} | ${detail} |"
        done < "$RESULT_FILE"
        echo ""
        echo "Totals: PASS=${passes}, FAIL=${fails}, MANUAL/SKIP=${manuals}."
        echo ""
        echo "Criterion map (docs/lfs-x11-contract.md section 5):"
        echo "1. Extension probe (verifyX11Extensions) - authoritative."
        echo "2. xdpyinfo cross-check (six extensions)."
        echo "3. Sole-WM: no BadAccess on the SubstructureRedirect claim."
        echo "4. GLX direct rendering (glxinfo -B)."
        echo "5. End-to-end: external client composited as a NativeWindow3D quad + physical input forwarded."
        echo "6. Key ownership: real Alt+Tab reaches lg3d."
        echo ""
        echo "Logs: \`${RUN_DIR}\` (verifyX11Extensions, xdpyinfo, glxinfo, lg3d compositor, client)."
        echo ""
        echo "Prohibitions honoured (contract section 4): this harness started no X server;"
        echo "used no synthetic input (physical pointer/keyboard only); took no screenshots."
    } > "$EVIDENCE_FILE"
}

print_summary() {
    say ""; hr
    say "SECTION-5 ACCEPTANCE SUMMARY (${DISPLAY_TARGET})"
    hr
    printf '  %-3s %-7s %s\n' "5." "RESULT" "DETAIL"
    local id status detail
    while IFS=$'\t' read -r id status detail; do
        [ -n "$id" ] || continue
        printf '  %-3s %-7s %s\n' "$id" "$status" "$detail"
    done < "$RESULT_FILE"
    hr
    local passes fails manuals
    passes="$(awk -F'\t' '$2=="PASS"' "$RESULT_FILE" | wc -l | tr -d ' ')"
    fails="$(awk -F'\t' '$2=="FAIL"' "$RESULT_FILE" | wc -l | tr -d ' ')"
    manuals="$(awk -F'\t' '$2=="MANUAL" || $2=="SKIP"' "$RESULT_FILE" | wc -l | tr -d ' ')"
    write_evidence "$passes" "$fails" "$manuals"
    say "PASS=${passes}  FAIL=${fails}  MANUAL/SKIP=${manuals}"
    say "Evidence file: ${EVIDENCE_FILE}"
    say "Logs dir     : ${RUN_DIR}"
    say ""
    if [ "$fails" -gt 0 ]; then
        say "RESULT: NOT COMPLIANT - at least one criterion FAILED. Attach the evidence file"
        say "        and logs to the PR and address the failure(s)."
    elif [ "$manuals" -gt 0 ]; then
        say "RESULT: INCOMPLETE - no failures, but ${manuals} criterion/criteria need a human at"
        say "        the Xorg VT (or a missing tool). Re-run interactively to finish section 5."
    else
        say "RESULT: COMPLIANT - all six section-5 criteria PASS. Attach the evidence file to"
        say "        the PR as the compliance record."
    fi
}

# ===========================================================================
say "lg3d X11 compositor - Stage-4 live proof (contract section 5)"
hr
say "repo       : ${REPO_ROOT}"
say "display    : ${DISPLAY_TARGET}"
say "client     : ${CLIENT} ${CLIENT_ARGS}"
say "evidence   : ${RUN_DIR}"
hr

if ! detect_jdk21; then
    say "WARNING: no JDK 21 detected; Gradle 8.14 will fail on Java 25+."
    say "         Set JAVA_HOME to a JDK 21 install and re-run."
else
    say "JAVA_HOME  : ${JAVA_HOME}"
fi

SOCK="$(x_socket_for "$DISPLAY_TARGET")"
if [ ! -e "$SOCK" ]; then
    say ""
    say "ABORT: no X server is running on ${DISPLAY_TARGET} (missing ${SOCK})."
    say "       This harness NEVER starts an X server. Start a bare Xorg first, e.g.:"
    say "           Xorg :1 vt2 -nolisten tcp"
    say "       (production LFS target: Xorg on :0 as the session). See"
    say "       docs/lfs-x11-contract.md sections 3.1 and 6."
    exit 2
fi
say "X socket   : ${SOCK} (present)"

# ===========================================================================
# CRITERION 1 - authoritative extension probe (verifyX11Extensions)
# ===========================================================================
say ""; hr; say "[1/6] Extension probe: ./gradlew :lg3d-core:verifyX11Extensions --args='${DISPLAY_TARGET}'"; hr
C1_LOG="${RUN_DIR}/01-verifyX11Extensions.log"
DISPLAY="$DISPLAY_TARGET" ./gradlew :lg3d-core:verifyX11Extensions \
    --args="${DISPLAY_TARGET}" --console=plain > "$C1_LOG" 2>&1
C1_RC=$?
tail -n 25 "$C1_LOG" | sed 's/^/    /'
if [ "$C1_RC" -eq 0 ] && grep -q "All required X extensions are available. Stage 0 verified." "$C1_LOG"; then
    record 1 PASS "verifyX11Extensions exit 0 + 'Stage 0 verified' (log: $(basename "$C1_LOG"))"
    say "    => PASS (exit 0, 'Stage 0 verified')"
elif grep -qi "MISSING" "$C1_LOG"; then
    record 1 FAIL "verifyX11Extensions reported a MISSING extension (exit ${C1_RC})"
    say "    => FAIL (a required extension is MISSING)"
else
    record 1 FAIL "verifyX11Extensions did not report success (exit ${C1_RC})"
    say "    => FAIL (exit ${C1_RC}; see log)"
fi

# ===========================================================================
# CRITERION 2 - cross-check with xdpyinfo
# ===========================================================================
say ""; hr; say "[2/6] Cross-check: xdpyinfo -queryExtensions (six required)"; hr
C2_LOG="${RUN_DIR}/02-xdpyinfo.log"
if have xdpyinfo; then
    DISPLAY="$DISPLAY_TARGET" xdpyinfo -queryExtensions > "$C2_LOG" 2>&1
    missing=""
    for ext in Composite DAMAGE XFIXES XTEST SHAPE MIT-SHM; do
        if grep -qiE "(^|[[:space:]])${ext}([[:space:]]|$)" "$C2_LOG"; then
            say "    found: ${ext}"
        else
            say "    MISSING: ${ext}"; missing="${missing} ${ext}"
        fi
    done
    if [ -z "$missing" ]; then
        record 2 PASS "xdpyinfo lists all six extensions (log: $(basename "$C2_LOG"))"
        say "    => PASS (all six present)"
    else
        record 2 FAIL "xdpyinfo missing:${missing}"
        say "    => FAIL (missing:${missing})"
    fi
else
    record 2 SKIP "xdpyinfo not installed (xorg-x11-utils); cross-check not run"
    say "    => SKIP (xdpyinfo not installed; install xorg-x11-utils)"
fi

# ===========================================================================
# CRITERION 4 - GLX direct rendering
# ===========================================================================
say ""; hr; say "[4/6] GL check: glxinfo -B (direct rendering on GPU)"; hr
C4_LOG="${RUN_DIR}/04-glxinfo.log"
if have glxinfo; then
    DISPLAY="$DISPLAY_TARGET" glxinfo -B > "$C4_LOG" 2>&1
    grep -iE "direct rendering|OpenGL renderer|OpenGL version" "$C4_LOG" | sed 's/^/    /'
    if grep -qiE "direct rendering:[[:space:]]*yes" "$C4_LOG"; then
        record 4 PASS "glxinfo reports direct rendering: yes (log: $(basename "$C4_LOG"))"
        say "    => PASS (direct rendering)"
    else
        record 4 FAIL "glxinfo did NOT report direct rendering (software GL) - see $(basename "$C4_LOG")"
        say "    => FAIL (no direct rendering; software-only GL is not a supported target)"
    fi
else
    record 4 SKIP "glxinfo not installed (mesa-demos); GL check not run"
    say "    => SKIP (glxinfo not installed; install mesa-demos)"
fi

if [ "$PROBES_ONLY" = true ]; then
    record 3 MANUAL "not run (--probes-only)"
    record 5 MANUAL "not run (--probes-only)"
    record 6 MANUAL "not run (--probes-only)"
    say ""; say "--probes-only: skipping the live lg3d session (criteria 3,5,6)."
else
    run_live_session
fi

# ===========================================================================
# summary + evidence file
# ===========================================================================
print_summary
