#!/usr/bin/env bash
# Rebuild org.jogamp.java3d:java3d-core:1.7.2 with the Canvas3D.preSwap() GPU
# post-process hook and install the jar+pom into a local Maven-layout repository.
# The root build.gradle declares that repo BEFORE mavenCentral(), so the patched
# jar shadows the stock artifact on every compile/test/run classpath and in the
# release bundle's detached configuration -- with no coordinate changes.
#
# The patched binary is NEVER committed; it is rebuilt from upstream source plus
# the committed patch each time the patch changes. See
# lg3d-core/src/patches/java3d-core-preswap/PROVENANCE.txt for provenance and the
# GPLv2-with-Classpath-exception license. Invoked by :buildPatchedJava3dCore.
#
# Usage: build-patched-java3d.sh PATCH SRC_DIR REPO_DIR UPSTREAM_SHA
#   PATCH        path to preswap-hook.patch
#   SRC_DIR      working dir for the upstream clone (recreated each run)
#   REPO_DIR     local Maven-layout repository output dir
#   UPSTREAM_SHA pinned commit of https://github.com/Jogamp/java3d-core.git
# JAVA_HOME must point at the pinned JDK 21 toolchain (Gradle passes it).
set -euo pipefail

PATCH="${1:?patch path required}"
SRC_DIR="${2:?src dir required}"
REPO_DIR="${3:?repo dir required}"
SHA="${4:?upstream sha required}"
UPSTREAM_URL="https://github.com/Jogamp/java3d-core.git"
VERSION="1.7.2"
GROUP_PATH="org/jogamp/java3d/java3d-core/${VERSION}"
OUT_JAR="${REPO_DIR}/${GROUP_PATH}/java3d-core-${VERSION}.jar"
OUT_POM="${REPO_DIR}/${GROUP_PATH}/java3d-core-${VERSION}.pom"

command -v git >/dev/null 2>&1 || { echo "ERROR: git not found on PATH" >&2; exit 3; }
command -v mvn >/dev/null 2>&1 || { echo "ERROR: mvn (Maven) not found on PATH" >&2; exit 3; }
[ -n "${JAVA_HOME:-}" ] || { echo "ERROR: JAVA_HOME must be the JDK 21 toolchain" >&2; exit 3; }
[ -x "${JAVA_HOME}/bin/java" ] || { echo "ERROR: JAVA_HOME/bin/java not executable: ${JAVA_HOME}" >&2; exit 3; }
[ -f "$PATCH" ] || { echo "ERROR: patch not found: $PATCH" >&2; exit 3; }
PATCH_ABS="$(cd "$(dirname "$PATCH")" && pwd)/$(basename "$PATCH")"

echo "[patched-java3d] upstream=${UPSTREAM_URL}"
echo "[patched-java3d] sha=${SHA}"
echo "[patched-java3d] JAVA_HOME=${JAVA_HOME}"
echo "[patched-java3d] java=$("${JAVA_HOME}/bin/java" -version 2>&1 | head -1)"

# Clone into a fresh unique working dir rather than rm -rf-ing a large
# pre-existing tree: btrfs can transiently report ENOTEMPTY while removing a
# Maven target/ tree, which `set -e` would turn into a hard build failure. SRC_DIR
# is used only as the naming prefix; the temp clone is removed best-effort on exit.
mkdir -p "$(dirname "$SRC_DIR")"
WORK="$(mktemp -d "${SRC_DIR}.XXXXXX")"
# Best-effort cleanup, retried: btrfs can transiently report ENOTEMPTY while
# removing the Maven target/ tree, and a single rm -rf would leave the clone behind.
cleanup() {
    local i
    for i in 1 2 3 4 5; do
        rm -rf "$WORK" 2>/dev/null && return 0
        sleep 1
    done
    rm -rf "$WORK" 2>/dev/null || echo "WARN: leftover temp clone: $WORK" >&2
}
trap cleanup EXIT

# Full (but quiet) clone so the pinned commit is guaranteed present even if
# upstream master has moved past it, then check out exactly that commit.
echo "[patched-java3d] cloning into ${WORK}"
git clone --quiet "$UPSTREAM_URL" "$WORK"
git -C "$WORK" checkout --quiet "$SHA"
ACTUAL="$(git -C "$WORK" rev-parse HEAD)"
[ "$ACTUAL" = "$SHA" ] || { echo "ERROR: checked out $ACTUAL, expected $SHA" >&2; exit 4; }

echo "[patched-java3d] applying $(basename "$PATCH_ABS")"
git -C "$WORK" apply --verbose "$PATCH_ABS"

echo "[patched-java3d] mvn -B -DskipTests package"
( cd "$WORK" && mvn -q -B -DskipTests package )

BUILT_JAR="${WORK}/target/java3d-core-${VERSION}.jar"
[ -f "$BUILT_JAR" ] || { echo "ERROR: expected built jar missing: $BUILT_JAR" >&2; exit 4; }
[ -f "${WORK}/pom.xml" ] || { echo "ERROR: source pom missing: ${WORK}/pom.xml" >&2; exit 4; }

mkdir -p "${REPO_DIR}/${GROUP_PATH}"
cp "$BUILT_JAR" "$OUT_JAR"
cp "${WORK}/pom.xml" "$OUT_POM"
echo "[patched-java3d] installed ${OUT_JAR} ($(wc -c <"$OUT_JAR") bytes)"
echo "[patched-java3d] OK"
