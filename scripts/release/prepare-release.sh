#!/usr/bin/env bash
# scripts/release/prepare-release.sh — finalize the tree for a release.
#
# Performs the "release cut" the project convention describes: date the CHANGELOG
# [Unreleased] section into a [X.Y.Z] release section and drop the -dev suffix
# from the three other canonical version references (build.gradle, README.md,
# AGENTS.md). Run on the release/X.Y.Z branch BEFORE it is merged to main and
# tagged; it stages nothing and never runs `git add -A`.
#
# Usage:
#   prepare-release.sh <version> [--date YYYY-MM-DD] [--dry-run]
#
#   <version>  target release version X.Y.Z (no leading v, no suffix)
#   --date     release date stamped into the CHANGELOG header (default: today)
#   --dry-run  show what would change without writing
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/release/lib.sh
. "$HERE/lib.sh"

VERSION="" DATE="$(date +%Y-%m-%d)" DRY=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --date)    DATE="$2"; shift 2 ;;
        --dry-run) DRY=true; shift ;;
        -h|--help) sed -n '2,17p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*)        log_die "prepare-release.sh: unknown option '$1'" ;;
        *)         [ -z "$VERSION" ] || log_die "unexpected argument '$1'"; VERSION="$1"; shift ;;
    esac
done
[ -n "$VERSION" ] || log_die "a target version is required, e.g. prepare-release.sh 1.10.0"
is_semver "$VERSION" || log_die "version '$VERSION' is not X.Y.Z (no leading v, no suffix)"
[[ "$DATE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || log_die "date '$DATE' is not YYYY-MM-DD"

cd "$(repo_root)"

if [ "$DRY" = true ]; then
    log_info "[dry-run] would cut CHANGELOG [Unreleased] -> [${VERSION}] — ${DATE}"
    log_info "[dry-run] would set build.gradle / README.md / AGENTS.md -> ${VERSION}"
    exit 0
fi

# Refuse to run on a dirty tracked tree so we never fold unrelated work into the
# release commit. Untracked runtime artefacts are ignored (repo convention).
if ! git diff --quiet || ! git diff --cached --quiet; then
    git status --short | grep -vE '^\?\?' || true
    log_die "uncommitted changes to tracked files (above); commit or stash them first."
fi

cut_changelog_release "$VERSION" "$DATE"
set_version_refs "$VERSION"
log_ok "prepared ${VERSION} release cut (CHANGELOG dated, refs de-dev'd)."
