#!/usr/bin/env bash
# scripts/release/lib.sh — shared helpers for the lg3d automated release train.
#
# This file is SOURCED, never executed. It holds the deterministic, unit-tested
# logic the release workflows depend on so the YAML stays thin:
#
#   * version parsing / comparison and semantic bumps,
#   * conventional-commit classification (feat -> minor, fix -> patch,
#     BREAKING -> major) over a commit range,
#   * reading and rewriting the four canonical version references
#     (build.gradle, README.md, AGENTS.md — the CHANGELOG header is handled by
#     its own functions because it also carries a date/description),
#   * cutting the CHANGELOG `## [Unreleased]` header into a dated
#     `## [X.Y.Z]` release section, and re-opening a fresh `[Unreleased]`.
#
# Conventions encoded here mirror make-release.sh and the project's version-bump
# rule (see AGENTS.md "Git Conventions"): the four refs stay in sync, a feat is a
# minor bump, a fix is a patch bump, a BREAKING change is a major bump, and only
# these four files ever carry the project version.
#
# All functions are pure with respect to the repository unless documented as
# writing files; none of them ever run `git add -A`.

# Guard against double-sourcing.
[ -n "${_LG3D_RELEASE_LIB_SH:-}" ] && return 0
_LG3D_RELEASE_LIB_SH=1

# NOTE: this is a SOURCED library, so it deliberately does NOT run
# `set -euo pipefail` — doing so would hijack the caller's shell options (and
# break test harnesses that rely on non-zero returns from grep/test). Each
# executable script that sources this file sets its own options up front.

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
if [ -t 1 ]; then
    _RED=$'\033[0;31m'; _GREEN=$'\033[0;32m'; _YELLOW=$'\033[1;33m'
    _BLUE=$'\033[0;34m'; _NC=$'\033[0m'
else
    _RED=''; _GREEN=''; _YELLOW=''; _BLUE=''; _NC=''
fi
log_info() { echo "${_BLUE}==>${_NC} $*" >&2; }
log_ok()   { echo "${_GREEN}==>${_NC} $*" >&2; }
log_warn() { echo "${_YELLOW}warning:${_NC} $*" >&2; }
log_die()  { echo "${_RED}error:${_NC} $*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Repository location
# ---------------------------------------------------------------------------
# Absolute path of the four canonical version-bearing files, resolved relative
# to the git top-level so the scripts work from any CWD.
repo_root() { git rev-parse --show-toplevel; }

# Echo the path of a canonical file (arg: build|readme|agents|changelog).
ref_file() {
    local root; root="$(repo_root)"
    case "$1" in
        build)     echo "$root/build.gradle" ;;
        readme)    echo "$root/README.md" ;;
        agents)    echo "$root/AGENTS.md" ;;
        changelog) echo "$root/CHANGELOG.md" ;;
        *) log_die "ref_file: unknown ref '$1'" ;;
    esac
}

# ---------------------------------------------------------------------------
# Version parsing / comparison / bumping
# ---------------------------------------------------------------------------
# Validate a bare X.Y.Z version (no leading v, no suffix).
is_semver() { [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; }

# Validate a possibly-suffixed version X.Y.Z[-suffix] (e.g. 1.10.0-dev,
# 1.10.0-rc.1). Echoes nothing; returns non-zero when malformed.
is_version() { [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; }

# Strip a leading 'v' from a tag: v1.9.0 -> 1.9.0.
tag_to_version() { echo "${1#v}"; }

# Split a bare X.Y.Z into components on stdout as "X Y Z".
split_semver() {
    is_semver "$1" || log_die "split_semver: '$1' is not X.Y.Z"
    local IFS='.'; # shellcheck disable=SC2206
    local parts=($1)
    echo "${parts[0]} ${parts[1]} ${parts[2]}"
}

# bump_version <base X.Y.Z> <major|minor|patch> -> next X.Y.Z on stdout.
bump_version() {
    local base="$1" level="$2"
    is_semver "$base" || log_die "bump_version: base '$base' is not X.Y.Z"
    local x y z; read -r x y z <<<"$(split_semver "$base")"
    case "$level" in
        major) echo "$((x + 1)).0.0" ;;
        minor) echo "${x}.$((y + 1)).0" ;;
        patch) echo "${x}.${y}.$((z + 1))" ;;
        none)  echo "$base" ;;
        *)     log_die "bump_version: unknown level '$level'" ;;
    esac
}

# version_cmp <a> <b>: echo -1 / 0 / 1 (a<b, a==b, a>b). Bare X.Y.Z only.
version_cmp() {
    local ax ay az bx by bz
    read -r ax ay az <<<"$(split_semver "$1")"
    read -r bx by bz <<<"$(split_semver "$2")"
    if   (( ax != bx )); then (( ax < bx )) && echo -1 || echo 1
    elif (( ay != by )); then (( ay < by )) && echo -1 || echo 1
    elif (( az != bz )); then (( az < bz )) && echo -1 || echo 1
    else echo 0
    fi
}

# ---------------------------------------------------------------------------
# Current version + latest stable tag
# ---------------------------------------------------------------------------
# Read the project version from build.gradle (allprojects { version = '<v>' }).
current_version() {
    local f; f="$(ref_file build)"
    [ -f "$f" ] || log_die "current_version: $f not found"
    local v
    v="$(grep -oP "^\s*version\s*=\s*'\K[^']+" "$f" | head -1 || true)"
    [ -n "$v" ] || log_die "current_version: could not read a version from $f"
    echo "$v"
}

# Strip a trailing -dev (1.10.0-dev -> 1.10.0); echo the input unchanged when
# there is no -dev suffix.
strip_dev() { echo "${1%-dev}"; }

# Latest STABLE release tag (vX.Y.Z, no pre-release suffix), or empty when the
# repository has none. Pre-release tags (-rc.N, -alpha, -beta) are excluded so
# version derivation always measures from the last real release.
latest_stable_tag() {
    local t
    for t in $(git tag -l 'v*' --sort=-v:refname); do
        # A stable tag is exactly vX.Y.Z (no second '-').
        if [[ "$t" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            echo "$t"; return 0
        fi
    done
    return 0
}

# ---------------------------------------------------------------------------
# Conventional-commit classification
# ---------------------------------------------------------------------------
# classify_subject <commit-subject> -> echoes major|minor|patch|none.
# Recognises the Conventional Commit grammar this repo mandates (AGENTS.md):
#   feat(scope)!: / BREAKING CHANGE  -> major
#   feat(scope):                     -> minor
#   fix(scope):                      -> patch
#   perf/refactor/chore/docs/... :   -> none (no version bump on their own)
classify_subject() {
    local s="$1"
    # Regexes are held in variables: bash's [[ =~ ]] parser mishandles inline
    # patterns containing capture-group parentheses, but expands a variable
    # holding the same ERE correctly.
    local re_break='^[a-z]+(\([^)]*\))?!:'
    local re_feat='^feat(\([^)]*\))?:'
    local re_fix='^fix(\([^)]*\))?:'
    # Explicit breaking change markers win outright.
    if [[ "$s" =~ $re_break ]] || [[ "$s" == *"BREAKING CHANGE"* ]]; then
        echo major; return 0
    fi
    if [[ "$s" =~ $re_feat ]]; then echo minor; return 0; fi
    if [[ "$s" =~ $re_fix ]]; then echo patch; return 0; fi
    echo none
}

# rank <level> -> numeric precedence so we can take the max (major>minor>patch>none).
_rank() {
    case "$1" in
        major) echo 3 ;; minor) echo 2 ;; patch) echo 1 ;; *) echo 0 ;;
    esac
}
_level_of_rank() {
    case "$1" in
        3) echo major ;; 2) echo minor ;; 1) echo patch ;; *) echo none ;;
    esac
}

# highest_bump <from-ref> <to-ref> -> echoes major|minor|patch|none, the highest
# bump implied by the NON-MERGE commit subjects in the range. Merge commits are
# skipped (their subjects are "Merge pull request ..."); the squashed/feature
# commits carry the conventional type. When the range is empty this echoes none.
highest_bump() {
    local from="$1" to="$2" best=0 s lvl r
    while IFS= read -r s; do
        [ -n "$s" ] || continue
        lvl="$(classify_subject "$s")"
        r="$(_rank "$lvl")"
        (( r > best )) && best="$r"
    done < <(git log --no-merges --pretty=%s "${from}..${to}" 2>/dev/null || true)
    _level_of_rank "$best"
}

# next_version_from_range <base X.Y.Z> <from-ref> <to-ref> [fallback-level]
# -> echoes the next X.Y.Z. Uses the highest conventional bump in the range; if
# the range holds nothing bump-worthy, falls back to <fallback-level> (default
# patch) so a release can still be cut for chore-only cycles.
next_version_from_range() {
    local base="$1" from="$2" to="$3" fallback="${4:-patch}"
    local level; level="$(highest_bump "$from" "$to")"
    [ "$level" = none ] && level="$fallback"
    bump_version "$base" "$level"
}

# ---------------------------------------------------------------------------
# The four canonical version references
# ---------------------------------------------------------------------------
# set_version_refs <target-version>
# Rewrite build.gradle, README.md and AGENTS.md from the CURRENT build.gradle
# version to <target-version>. The CHANGELOG header is NOT touched here (it also
# carries a date/description; see cut_changelog_release / reopen_changelog).
# Verifies each pattern before and after so a stale pattern is a hard failure,
# never a silent no-op. Stages nothing.
set_version_refs() {
    local target="$1"
    is_version "$target" || log_die "set_version_refs: '$target' is not a valid version"
    local old; old="$(current_version)"
    local bf rf af
    bf="$(ref_file build)"; rf="$(ref_file readme)"; af="$(ref_file agents)"

    if [ "$old" = "$target" ]; then
        log_warn "set_version_refs: build.gradle already at '$target'; refs are a no-op."
    fi

    grep -q "version = '${old}'" "$bf" || log_die "$bf does not contain \"version = '${old}'\"."
    grep -q -e "- Version: \`${old}\`" "$rf" || log_die "$rf does not contain \"- Version: \`${old}\`\"."
    grep -q "org.jdesktop.lg3d:${old}" "$af" || log_die "$af does not contain the org.jdesktop.lg3d:${old} coordinate."

    sed -i.bak "s/version = '${old}'/version = '${target}'/" "$bf"
    sed -i.bak "s|- Version: \`${old}\`|- Version: \`${target}\`|" "$rf"
    sed -i.bak "s|org.jdesktop.lg3d:${old}|org.jdesktop.lg3d:${target}|" "$af"
    rm -f "$bf.bak" "$rf.bak" "$af.bak"

    grep -q "version = '${target}'" "$bf" || log_die "set_version_refs failed in $bf."
    grep -q -e "- Version: \`${target}\`" "$rf" || log_die "set_version_refs failed in $rf."
    grep -q "org.jdesktop.lg3d:${target}" "$af" || log_die "set_version_refs failed in $af."
    log_ok "version refs -> ${target}"
}

# ---------------------------------------------------------------------------
# CHANGELOG surgery
# ---------------------------------------------------------------------------
# The [Unreleased] header carries a trailing description, e.g.
#   ## [Unreleased] — 1.10.0-dev — Gradle / JDK 21 modernization
# The em dash is U+2014 (—). Some histories have a bare "## [Unreleased]"; both
# are handled. _changelog_desc echoes the description part (may be empty).
CHANGELOG_DASH=$'\xe2\x80\x94'  # — (em dash), matches the existing headers

# Echo the current [Unreleased] header line exactly, or empty when absent.
changelog_unreleased_header() {
    local cl; cl="$(ref_file changelog)"
    grep -m1 -E '^## \[Unreleased\]' "$cl" || true
}

# Echo the description tail of the [Unreleased] header (text after the second
# em dash), or a sensible default when the header is bare/absent.
changelog_desc() {
    local hdr desc
    hdr="$(changelog_unreleased_header)"
    # Strip "## [Unreleased]" then any leading " — <version> — ".
    desc="${hdr#\#\# \[Unreleased\]}"
    desc="${desc# }"
    if [[ "$desc" == "${CHANGELOG_DASH}"* ]]; then
        # remove "— <something> — " leaving the trailing description
        desc="${desc#"${CHANGELOG_DASH}"}"          # drop first dash
        desc="${desc# }"
        desc="${desc#*"${CHANGELOG_DASH}"}"          # drop up to second dash
        desc="${desc# }"
    else
        desc=""
    fi
    [ -n "$desc" ] && echo "$desc" || echo "Gradle / JDK 21 modernization"
}

# cut_changelog_release <version> <date>
# Turn the top "## [Unreleased][ — <v> — <desc>]" header into a dated release
# section "## [<version>] — <date> — <desc>". Idempotent: if a "## [<version>]"
# section already exists this is a no-op that warns. Writes CHANGELOG.md.
cut_changelog_release() {
    local version="$1" date="$2"
    is_semver "$version" || log_die "cut_changelog_release: '$version' is not X.Y.Z"
    local cl; cl="$(ref_file changelog)"
    local desc; desc="$(changelog_desc)"
    if grep -qE "^## \[${version//./\\.}\]" "$cl"; then
        log_warn "cut_changelog_release: a [${version}] section already exists; leaving CHANGELOG as-is."
        return 0
    fi
    local hdr; hdr="$(changelog_unreleased_header)"
    [ -n "$hdr" ] || log_die "cut_changelog_release: no '## [Unreleased]' header in $cl."
    # Replace the exact header line (first occurrence) with the dated release one.
    local new="## [${version}] ${CHANGELOG_DASH} ${date} ${CHANGELOG_DASH} ${desc}"
    awk -v old="$hdr" -v new="$new" '
        !done && $0 == old { print new; done=1; next }
        { print }
    ' "$cl" > "$cl.tmp" && mv "$cl.tmp" "$cl"
    grep -qE "^## \[${version//./\\.}\] ${CHANGELOG_DASH} ${date}" "$cl" \
        || log_die "cut_changelog_release: header rewrite failed."
    log_ok "CHANGELOG: [Unreleased] -> [${version}] — ${date}"
}

# reopen_changelog_unreleased <next-dev-version>
# Insert a fresh "## [Unreleased] — <next-dev> — <desc>" section (with the
# standard Added/Changed/Fixed/Removed scaffolding) ABOVE the top-most existing
# "## [" version section, so post-release PRs have somewhere to add bullets.
# Idempotent: no-op when an [Unreleased] header already exists.
reopen_changelog_unreleased() {
    local nextdev="$1"
    is_version "$nextdev" || log_die "reopen_changelog_unreleased: '$nextdev' is not a valid version"
    local cl; cl="$(ref_file changelog)"
    if grep -qE '^## \[Unreleased\]' "$cl"; then
        log_warn "reopen_changelog_unreleased: an [Unreleased] section already exists; leaving it."
        return 0
    fi
    local desc; desc="$(changelog_desc)"
    local hdr="## [Unreleased] ${CHANGELOG_DASH} ${nextdev} ${CHANGELOG_DASH} ${desc}"
    # Insert the new section immediately before the first "## [" line (the most
    # recent release section). Uses a temp file so the write is atomic-ish.
    awk -v hdr="$hdr" '
        !done && /^## \[/ {
            print hdr; print ""; print "### Added"; print ""; done=1
        }
        { print }
    ' "$cl" > "$cl.tmp" && mv "$cl.tmp" "$cl"
    grep -qE "^## \[Unreleased\] ${CHANGELOG_DASH} ${nextdev//./\\.}" "$cl" \
        || log_die "reopen_changelog_unreleased: could not insert the [Unreleased] header."
    log_ok "CHANGELOG: re-opened [Unreleased] — ${nextdev}"
}

# changelog_section <version>
# Echo the markdown body of the "## [<version>]" section (header line through the
# line before the next "## [" header). Empty when the section does not exist.
changelog_section() {
    local version="$1" cl
    cl="$(ref_file changelog)"
    awk -v ver="$version" '
        BEGIN { inseg=0 }
        /^## \[/ {
            # A new top-level section starts.
            if (inseg) { exit }
            line=$0
            want="## [" ver "]"
            if (index(line, want) == 1) { inseg=1; print; next }
        }
        inseg { print }
    ' "$cl"
}
