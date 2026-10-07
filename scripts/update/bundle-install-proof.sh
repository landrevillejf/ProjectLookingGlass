#!/usr/bin/env bash
#
# bundle-install-proof.sh - end-to-end proof for the lg3d release-bundle update
#                           path (update-manager BundleUpdateInstaller).
#
# WHAT THIS DOES
#   Proves, against the REAL :lg3d-core:releaseBundle archive, the whole
#   "apply a downloaded update to the installed desktop" chain that the unit
#   tests deliberately stub out:
#
#     1. stage the verified lg3d-<version>.zip (extract + layout validation),
#     2. snapshot the current managed entries into a timestamped rollback dir
#        that carries a standalone restore.sh,
#     3. hand a deferred bash apply-script the destructive tree replacement
#        AFTER the desktop JVM has exited (the production "Install Now" path,
#        BundleUpdateInstaller.installBundle -> System.exit(0)),
#     4. verify the install root now holds the NEW bundle, and
#     5. verify restore.sh rolls the root back to the OLD snapshot.
#
#   It runs the real installer classes taken from the bundle's own lib/
#   (update-manager + slf4j), a tiny compiled driver, and the real generated
#   apply/restore scripts through bash. No pkexec (the sandbox root is writable
#   and escalation is disabled), no relaunch, and nothing outside a temp sandbox
#   is ever touched.
#
# HARD GUARANTEES
#   * Never touches a real lg3d installation: the install root, work dir and
#     backup dir all live under a fresh temp sandbox (or --sandbox).
#   * Never escalates: BundleUpdateInstaller is built with escalationEnabled=
#     false, so launchCommand() is always plain `bash`, never `pkexec`.
#   * Never relaunches a desktop: relaunchEnabled=false, so the apply script
#     carries the "relaunch disabled" comment, not `setsid bash lg3d.sh`.
#
# USAGE
#   scripts/update/bundle-install-proof.sh [-z <bundle.zip>] [-s <sandbox>]
#                                          [-m installBundle|installBundleOnExit]
#                                          [--keep] [--no-build] [-h]
#
#   -z <zip>      use this release bundle instead of locating/building one
#   -s <dir>      sandbox root (default: a fresh mktemp -d)
#   -m <method>   installer entry point to exercise (default installBundle)
#   --keep        do not delete the sandbox on exit (inspect the evidence)
#   --no-build    do not run :lg3d-core:releaseBundle when no zip is found
#
set -uo pipefail

PROG="$(basename "$0")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

ZIP=""
SANDBOX=""
METHOD="installBundle"
KEEP=false
DO_BUILD=true

usage() { sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

while [ $# -gt 0 ]; do
    case "$1" in
        -z|--zip)      ZIP="${2:?}"; shift 2 ;;
        -s|--sandbox)  SANDBOX="${2:?}"; shift 2 ;;
        -m|--method)   METHOD="${2:?}"; shift 2 ;;
        --keep)        KEEP=true; shift ;;
        --no-build)    DO_BUILD=false; shift ;;
        -h|--help)     usage 0 ;;
        *) echo "$PROG: unknown option: $1" >&2; usage 1 ;;
    esac
done

fail() { echo "FAIL: $*" >&2; exit 1; }
info() { echo "== $*"; }
ok()   { echo "   ok: $*"; }

# --- JDK 21 (needed to compile + run the driver) ---------------------------
DEFAULT_JDK21="/home/fedora/.jdks/jdk-21.0.12.1+1"
jdk_is_21() { local h="$1"; [ -x "${h}/bin/java" ] && "${h}/bin/java" -version 2>&1 | grep -q 'version "21'; }
if [ -n "${JAVA_HOME:-}" ] && jdk_is_21 "${JAVA_HOME}"; then :
elif jdk_is_21 "${DEFAULT_JDK21}"; then export JAVA_HOME="${DEFAULT_JDK21}"
elif [ -x /usr/libexec/java_home ] && jdk_is_21 "$(/usr/libexec/java_home -v 21 2>/dev/null)"; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
else fail "no JDK 21 found (set JAVA_HOME to a JDK 21)"; fi
JAVAC="${JAVA_HOME}/bin/javac"; JAVA="${JAVA_HOME}/bin/java"
[ -x "$JAVAC" ] || fail "javac not found under ${JAVA_HOME} (a JDK, not a JRE, is required)"

# --- locate or build the release bundle ------------------------------------
if [ -z "$ZIP" ]; then
    ZIP="$(ls -1t "${REPO_ROOT}"/lg3d-core/build-gradle/distributions/lg3d-*.zip 2>/dev/null | head -1 || true)"
fi
if [ -z "$ZIP" ] && [ "$DO_BUILD" = true ]; then
    info "no bundle found; building :lg3d-core:releaseBundle ..."
    ( cd "$REPO_ROOT" && ./gradlew :lg3d-core:releaseBundle --console=plain ) || fail "releaseBundle build failed"
    ZIP="$(ls -1t "${REPO_ROOT}"/lg3d-core/build-gradle/distributions/lg3d-*.zip 2>/dev/null | head -1 || true)"
fi
[ -n "$ZIP" ] && [ -f "$ZIP" ] || fail "no release bundle zip found (build one or pass -z)"
info "bundle : $ZIP"

command -v unzip >/dev/null 2>&1 || fail "unzip is required"

# --- sandbox ----------------------------------------------------------------
if [ -z "$SANDBOX" ]; then SANDBOX="$(mktemp -d "${TMPDIR:-/tmp}/lg3d-bundle-proof.XXXXXX")"; fi
mkdir -p "$SANDBOX" || fail "cannot create sandbox $SANDBOX"
SANDBOX="$(cd "$SANDBOX" && pwd)"
ROOT="$SANDBOX/install"; WORK="$SANDBOX/work"; BACKUP="$SANDBOX/backups"; CP="$SANDBOX/cp"; DRV="$SANDBOX/driver"
mkdir -p "$ROOT" "$WORK" "$BACKUP" "$CP" "$DRV"
info "sandbox: $SANDBOX"
cleanup() { if [ "$KEEP" = true ]; then info "sandbox kept at $SANDBOX"; else rm -rf "$SANDBOX"; fi; }
trap cleanup EXIT

# --- classpath: the real installer, straight from the bundle's own lib/ -----
info "extracting update-manager + slf4j from the bundle lib/ ..."
unzip -o -j -q "$ZIP" 'lib/update-manager-*.jar' 'lib/slf4j-api-*.jar' 'lib/slf4j-nop-*.jar' -d "$CP" \
    || fail "could not extract the installer jars from the bundle"
UM_JAR="$(ls -1 "$CP"/update-manager-*.jar | head -1)"
[ -f "$UM_JAR" ] || fail "update-manager jar not found in the bundle lib/"
ok "$(basename "$UM_JAR")"

# --- driver: invoke the production installer entry point --------------------
cat > "$DRV/BundleProofDriver.java" <<'JAVA'
import com.protonmail.landrevillejf.swingide.update.BundleUpdateInstaller;
import java.nio.file.Path;

public class BundleProofDriver {
    public static void main(String[] args) throws Exception {
        if (args.length < 5) { System.err.println("usage: <root> <work> <backup> <zip> <method>"); System.exit(2); }
        Path root = Path.of(args[0]), work = Path.of(args[1]), backup = Path.of(args[2]), zip = Path.of(args[3]);
        String method = args[4];
        // relaunch=false (never respawn a desktop during a proof),
        // escalation=false (writable sandbox root -> plain bash, never pkexec).
        BundleUpdateInstaller installer = new BundleUpdateInstaller(root, work, backup, false, false);
        if ("installBundleOnExit".equals(method)) {
            installer.installBundleOnExit(zip);
            System.out.println("DRIVER_OK deferred apply script launched");
        } else {
            System.out.println("DRIVER_OK launching immediate apply script; JVM will exit(0)");
            System.out.flush();
            installer.installBundle(zip); // spawns the real apply script, then System.exit(0)
        }
    }
}
JAVA
info "compiling the proof driver ..."
"$JAVAC" -cp "$CP/*" -d "$DRV" "$DRV/BundleProofDriver.java" || fail "driver compilation failed"
ok "driver compiled"

# --- fabricate an OLD install root -----------------------------------------
info "seeding a mock OLD install root ..."
mkdir -p "$ROOT/lib" "$ROOT/resources" "$ROOT/etc" "$ROOT/ext/app"
echo "0.0.1-OLD"        > "$ROOT/VERSION"
echo "old launcher"     > "$ROOT/lg3d.sh"
echo "old readme"       > "$ROOT/README.txt"
echo "stale"            > "$ROOT/lib/old-marker.jar"
echo "old icon"         > "$ROOT/resources/icon.png"
echo "old cfg"          > "$ROOT/etc/old.cfg"
ok "OLD root seeded (VERSION=0.0.1-OLD, lib/old-marker.jar present)"

NEW_VERSION="$(unzip -p "$ZIP" VERSION | tr -d '[:space:]')"
info "bundle VERSION = $NEW_VERSION"

# --- poll helper: wait until a file's content matches (or times out) --------
wait_for_content() { # <file> <expected> <timeout-secs>
    local f="$1" want="$2" t="${3:-60}" i=0 got=""
    while [ "$i" -lt "$t" ]; do
        got="$(tr -d '[:space:]' < "$f" 2>/dev/null || true)"
        [ "$got" = "$want" ] && return 0
        sleep 1; i=$((i+1))
    done
    echo "      (last seen: '${got}')" >&2
    return 1
}

# --- run the installer (the deferred apply script does the destructive work) -
info "running BundleUpdateInstaller.$METHOD against the mock root ..."
set +e
"$JAVA" -cp "$CP/*:$DRV" BundleProofDriver "$ROOT" "$WORK" "$BACKUP" "$ZIP" "$METHOD"
DRV_RC=$?
set -e
# installBundle ends with System.exit(0); installBundleOnExit returns normally.
[ "$DRV_RC" -eq 0 ] || fail "driver exited $DRV_RC"
ok "driver finished (rc=0)"

# --- assert the NEW tree landed --------------------------------------------
info "waiting for the deferred apply script to replace the tree ..."
wait_for_content "$ROOT/VERSION" "$NEW_VERSION" 60 || fail "VERSION never became $NEW_VERSION (apply script did not run)"
ok "VERSION -> $NEW_VERSION"

[ -f "$ROOT/lg3d.sh" ] && ! grep -q "old launcher" "$ROOT/lg3d.sh" || fail "lg3d.sh was not replaced"
ok "lg3d.sh replaced"
[ ! -e "$ROOT/lib/old-marker.jar" ] || fail "stale lib/old-marker.jar survived the update"
NEW_JARS="$(find "$ROOT/lib" -name '*.jar' | wc -l | tr -d ' ')"
[ "$NEW_JARS" -ge 50 ] || fail "expected the full lib/ jar set, found $NEW_JARS"
ok "lib/ replaced ($NEW_JARS jars, stale marker gone)"
for e in resources etc ext README.txt; do [ -e "$ROOT/$e" ] || fail "managed entry missing after update: $e"; done
ok "resources/ etc/ ext/ README.txt present"

# --- assert the rollback snapshot ------------------------------------------
SNAP="$(find "$BACKUP" -maxdepth 1 -type d -name 'lg3d-bundle-backup-*' | head -1)"
[ -n "$SNAP" ] || fail "no rollback snapshot dir under $BACKUP"
[ -f "$SNAP/restore.sh" ] || fail "snapshot has no restore.sh"
ok "rollback snapshot: $(basename "$SNAP") (+ restore.sh)"
[ -f "$SNAP/VERSION" ] && grep -q "0.0.1-OLD" "$SNAP/VERSION" || fail "snapshot did not capture the OLD VERSION"
[ -f "$SNAP/lib/old-marker.jar" ] || fail "snapshot did not capture the OLD lib/ marker"
ok "snapshot holds the OLD VERSION + lib/old-marker.jar"

# --- assert restore.sh rolls back ------------------------------------------
info "running restore.sh (rollback) ..."
bash "$SNAP/restore.sh" >/dev/null 2>&1 || fail "restore.sh exited non-zero"
wait_for_content "$ROOT/VERSION" "0.0.1-OLD" 30 || fail "VERSION did not roll back to 0.0.1-OLD"
ok "VERSION rolled back -> 0.0.1-OLD"
[ -f "$ROOT/lib/old-marker.jar" ] || fail "rollback did not restore lib/old-marker.jar"
grep -q "old launcher" "$ROOT/lg3d.sh" || fail "rollback did not restore the OLD lg3d.sh"
ok "OLD lib/ marker + lg3d.sh restored"

echo
echo "PASS: end-to-end release-bundle update + rollback verified against $ZIP"
echo "      install (staged -> snapshot -> deferred apply) and restore.sh both correct."
exit 0
