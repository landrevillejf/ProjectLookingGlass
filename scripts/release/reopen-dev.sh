#!/usr/bin/env bash
# scripts/release/reopen-dev.sh — re-open the dev cycle after a release ships.
#
# The second half of the project's two-step convention: once vX.Y.Z is published,
# main must go back to a -dev state so subsequent PRs accumulate under
# [Unreleased]. This re-adds a fresh "## [Unreleased] — <next>-dev" CHANGELOG
# section above the just-released [X.Y.Z] section and sets the three other
# canonical refs (build.gradle, README.md, AGENTS.md) to <next>-dev. Stages
# nothing; never runs `git add -A`.
#
# Usage:
#   reopen-dev.sh --released <X.Y.Z> [--next <A.B.C-dev>] [--level major|minor|patch]
#                 [--dry-run]
#
#   --released  the version just published (used to derive --next when omitted)
#   --next      explicit next dev version (default: <released> patch+1, -dev)
#   --level     bump level used to derive --next from --released (default: patch)
#   --dry-run   show what would change without writing
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/release/lib.sh
. "$HERE/lib.sh"

RELEASED="" NEXT="" LEVEL="patch" DRY=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --released) RELEASED="$2"; shift 2 ;;
        --next)     NEXT="$2"; shift 2 ;;
        --level)    LEVEL="$2"; shift 2 ;;
        --dry-run)  DRY=true; shift ;;
        -h|--help)  sed -n '2,19p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)          log_die "reopen-dev.sh: unknown argument '$1'" ;;
    esac
done

if [ -z "$NEXT" ]; then
    [ -n "$RELEASED" ] || log_die "either --released or --next is required."
    is_semver "$RELEASED" || log_die "--released '$RELEASED' is not X.Y.Z."
    case "$LEVEL" in major|minor|patch) ;; *) log_die "invalid --level '$LEVEL'" ;; esac
    NEXT="$(bump_version "$RELEASED" "$LEVEL")-dev"
fi
is_version "$NEXT" || log_die "next '$NEXT' is not a valid version."
[[ "$NEXT" == *-dev ]] || log_warn "next dev version '$NEXT' has no -dev suffix."

cd "$(repo_root)"

if [ "$DRY" = true ]; then
    log_info "[dry-run] would re-open CHANGELOG [Unreleased] — ${NEXT}"
    log_info "[dry-run] would set build.gradle / README.md / AGENTS.md -> ${NEXT}"
    exit 0
fi

if ! git diff --quiet || ! git diff --cached --quiet; then
    git status --short | grep -vE '^\?\?' || true
    log_die "uncommitted changes to tracked files (above); commit or stash them first."
fi

reopen_changelog_unreleased "$NEXT"
set_version_refs "$NEXT"
log_ok "re-opened the dev cycle at ${NEXT}."
