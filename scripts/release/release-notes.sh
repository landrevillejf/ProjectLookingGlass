#!/usr/bin/env bash
# scripts/release/release-notes.sh — assemble a GitHub Release body.
#
# The body is the curated CHANGELOG section for the version PLUS an auto
# appendix of the merged pull requests (and, optionally, the closed milestone
# issues) that went into it, each attributed to its author. The appendix is
# best-effort: when `gh` is unavailable or the API call fails (offline, no
# token), the notes degrade to the CHANGELOG section and the raw merge-commit
# subjects, so release publishing never hard-fails on notes generation.
#
# Usage:
#   release-notes.sh --version <X.Y.Z> [--prev-tag <vA.B.C>] [--head <ref>]
#                    [--repo <owner/repo>] [--milestone <title>]
#
# Prints markdown on stdout.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/release/lib.sh
. "$HERE/lib.sh"

VERSION="" PREV="" HEAD_REF="HEAD" REPO="" MILESTONE=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --version)   VERSION="$2"; shift 2 ;;
        --prev-tag)  PREV="$2"; shift 2 ;;
        --head)      HEAD_REF="$2"; shift 2 ;;
        --repo)      REPO="$2"; shift 2 ;;
        --milestone) MILESTONE="$2"; shift 2 ;;
        -h|--help)   sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)           log_die "release-notes.sh: unknown argument '$1'" ;;
    esac
done
[ -n "$VERSION" ] || log_die "--version is required."
cd "$(repo_root)"

# A pre-release tag (vX.Y.Z-rc.N) carries a suffix; the CHANGELOG section and the
# previous-tag comparison are keyed on the bare X.Y.Z base.
BASE="${VERSION%%-*}"
is_semver "$BASE" || log_die "--version '$VERSION' does not start with a bare X.Y.Z."

if [ -z "$PREV" ]; then
    # The most recent stable tag strictly older than BASE.
    local_prev=""
    for t in $(git tag -l 'v*' --sort=-v:refname); do
        [[ "$t" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || continue
        tv="$(tag_to_version "$t")"
        if [ "$(version_cmp "$tv" "$BASE")" = "-1" ]; then local_prev="$t"; break; fi
    done
    PREV="$local_prev"
fi
if [ -z "$REPO" ]; then
    REPO="$(git remote get-url origin 2>/dev/null | sed -E 's#(git@github.com:|https://github.com/)##; s#\.git$##' || true)"
fi

# --- 1. Curated CHANGELOG section -------------------------------------------
SECTION="$(changelog_section "$BASE")"
if [ -z "$SECTION" ]; then
    # Not cut yet (e.g. an RC built from a branch still showing [Unreleased]).
    SECTION="$(changelog_section "Unreleased")"
fi
if [ -n "$SECTION" ]; then
    printf '%s\n' "$SECTION"
else
    echo "## ${VERSION}"
    echo
    echo "_No CHANGELOG section found for ${VERSION}._"
fi

# --- 2. Merged pull requests in PREV..HEAD ----------------------------------
RANGE="${PREV:+${PREV}..}${HEAD_REF}"
# shellcheck disable=SC2086  # RANGE is intentionally word-split (may be a single ref)
PR_NUMS="$(git log --merges --pretty=%s ${RANGE} 2>/dev/null \
    | grep -oE 'Merge pull request #[0-9]+' | grep -oE '[0-9]+' | sort -rn | uniq || true)"

echo
echo "## Merged pull requests"
echo
if [ -z "$PR_NUMS" ]; then
    echo "_No merged pull requests detected in ${RANGE}._"
else
    FEAT="" FIX="" OTHER=""
    HAVE_GH=false
    command -v gh >/dev/null 2>&1 && HAVE_GH=true
    while IFS= read -r n; do
        [ -n "$n" ] || continue
        title=""; author=""
        if [ "$HAVE_GH" = true ]; then
            meta="$(gh pr view "$n" --repo "$REPO" --json number,title,author 2>/dev/null || true)"
            if [ -n "$meta" ]; then
                title="$(printf '%s' "$meta" | jq -r '.title // empty' 2>/dev/null || true)"
                author="$(printf '%s' "$meta" | jq -r '.author.login // empty' 2>/dev/null || true)"
            fi
        fi
        if [ -z "$title" ]; then
            # Fallback: the merge-commit subject, minus the "Merge pull request" prefix.
            # shellcheck disable=SC2086  # RANGE is intentionally word-split (may be empty)
            title="$(git log --merges --pretty=%s ${RANGE} 2>/dev/null \
                | grep -m1 -E "Merge pull request #${n}\b" || true)"
            [ -n "$title" ] || title="(pull request #${n})"
        fi
        if [ -n "$REPO" ]; then link="[#${n}](https://github.com/${REPO}/pull/${n})"; else link="#${n}"; fi
        line="- ${link} ${title}${author:+ (=@${author})}"
        # Group by the conventional type embedded in the title, if present.
        if [[ "$title" =~ ^feat ]] || [[ "$title" == *"feat("* ]]; then FEAT+="${line}"$'\n'
        elif [[ "$title" =~ ^fix ]] || [[ "$title" == *"fix("* ]]; then FIX+="${line}"$'\n'
        else OTHER+="${line}"$'\n'; fi
    done <<< "$PR_NUMS"

    [ -n "$FEAT" ]  && { echo "### Features"; echo; printf '%s' "$FEAT"; echo; }
    [ -n "$FIX" ]   && { echo "### Fixes"; echo; printf '%s' "$FIX"; echo; }
    [ -n "$OTHER" ] && { echo "### Other changes"; echo; printf '%s' "$OTHER"; echo; }
fi

# --- 3. Closed issues in the milestone (optional) ---------------------------
# The GitHub issues API filters by milestone NUMBER, so resolve a title to its
# number first. Best-effort: any failure simply omits the section.
if [ -n "$MILESTONE" ] && command -v gh >/dev/null 2>&1; then
    MS_NUM=""
    if [[ "$MILESTONE" =~ ^[0-9]+$ ]]; then
        MS_NUM="$MILESTONE"
    else
        MS_NUM="$(gh api "repos/${REPO}/milestones" --jq ".[] | select(.title==\"${MILESTONE}\") | .number" 2>/dev/null | head -1 || true)"
    fi
    if [ -n "$MS_NUM" ]; then
        ISSUES="$(gh api -X GET "repos/${REPO}/issues" -f state=closed -f milestone="$MS_NUM" \
            --jq '.[] | select(has("pull_request") | not) | "- #\(.number) \(.title) (@\(.user.login))"' 2>/dev/null || true)"
        if [ -n "$ISSUES" ]; then
            echo "## Closed issues"
            echo
            printf '%s\n' "$ISSUES"
            echo
        fi
    fi
fi
