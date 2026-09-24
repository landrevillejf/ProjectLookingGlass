#!/usr/bin/env bash
# scripts/release/next-version.sh — derive the next release version.
#
# Measures the Conventional Commits merged since the last STABLE release tag and
# applies the project's bump rule (AGENTS.md): a BREAKING change is a major bump,
# any feat is a minor bump, any fix is a patch bump. Chore/docs/refactor-only
# ranges fall back to a patch bump so a release can still be cut.
#
# Usage:
#   next-version.sh [--from <ref>] [--to <ref>] [--level major|minor|patch]
#                   [--base <X.Y.Z>] [--quiet]
#
#   --from   base of the commit range (default: latest stable vX.Y.Z tag, or the
#            empty tree when the repo has no release yet)
#   --to     head of the commit range (default: HEAD)
#   --level  force the bump level, skipping commit classification
#   --base   force the base version (default: derived from --from tag, else the
#            current build.gradle version with any -dev stripped)
#   --quiet  print only the version (default also logs the reasoning to stderr)
#
# Prints the resulting bare X.Y.Z on stdout. Exits non-zero on bad input.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/release/lib.sh
. "$HERE/lib.sh"

FROM="" TO="HEAD" LEVEL="" BASE="" QUIET=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --from)  FROM="$2"; shift 2 ;;
        --to)    TO="$2"; shift 2 ;;
        --level) LEVEL="$2"; shift 2 ;;
        --base)  BASE="$2"; shift 2 ;;
        --quiet) QUIET=true; shift ;;
        -h|--help) sed -n '2,26p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) log_die "next-version.sh: unknown argument '$1'" ;;
    esac
done

# --- resolve the base version ------------------------------------------------
if [ -z "$FROM" ]; then
    FROM="$(latest_stable_tag)"
fi
if [ -z "$BASE" ]; then
    if [ -n "$FROM" ]; then
        BASE="$(tag_to_version "$FROM")"
        is_semver "$BASE" || BASE=""
    fi
    if [ -z "$BASE" ]; then
        # No release yet: start from the current dev version, -dev stripped.
        BASE="$(strip_dev "$(current_version)")"
        is_semver "$BASE" || log_die "could not derive a base version (got '$BASE')."
    fi
fi
is_semver "$BASE" || log_die "base '$BASE' is not X.Y.Z."

# --- resolve the bump level --------------------------------------------------
if [ -z "$LEVEL" ]; then
    if [ -n "$FROM" ]; then
        LEVEL="$(highest_bump "$FROM" "$TO")"
    else
        # Empty tree -> everything: classify all history.
        LEVEL="$(highest_bump "$(git hash-object -t tree /dev/null)" "$TO")"
    fi
    FALLBACK="patch"
    if [ "$LEVEL" = none ]; then
        [ "$QUIET" = true ] || log_warn "no feat/fix/breaking commits in range; falling back to a patch bump."
        LEVEL="$FALLBACK"
    fi
fi
case "$LEVEL" in major|minor|patch|none) ;; *) log_die "invalid level '$LEVEL'" ;; esac

NEXT="$(bump_version "$BASE" "$LEVEL")"
if [ "$QUIET" = true ]; then
    echo "$NEXT"
else
    log_info "base=${BASE} level=${LEVEL} range=${FROM:-<root>}..${TO}"
    echo "$NEXT"
fi
