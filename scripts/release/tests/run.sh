#!/usr/bin/env bash
# scripts/release/tests/run.sh — self-tests for the release-train shell logic.
#
# Pure bash (no bats dependency so CI needs nothing extra). Every test that
# touches files runs inside a throwaway `git init` repo populated with fixtures
# shaped exactly like the real build.gradle / README.md / AGENTS.md / CHANGELOG.md,
# so lib.sh's repo_root()-relative functions are exercised for real. Exits
# non-zero on the first summary if any assertion failed.
#
# Run: bash scripts/release/tests/run.sh   (or ./scripts/release/tests/run.sh)
set -uo pipefail

TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELEASE_DIR="$(cd "$TESTS_DIR/.." && pwd)"
LIB="$RELEASE_DIR/lib.sh"

RESULTS="$(mktemp)"; : > "$RESULTS"
trap 'rm -f "$RESULTS"' EXIT
_c_green=$'\033[0;32m'; _c_red=$'\033[0;31m'; _c_nc=$'\033[0m'
[ -t 1 ] || { _c_green=''; _c_red=''; _c_nc=''; }

# Counters live in a file because each test group runs in a ( ... ) subshell,
# whose variable increments would otherwise vanish when the subshell exits.
ok()   { printf 'PASS\n' >> "$RESULTS"; }
bad()  { printf 'FAIL %s\n' "$1" >> "$RESULTS"; echo "${_c_red}FAIL:${_c_nc} $1" >&2; }
# assert_eq <desc> <want> <got>
assert_eq() {
    if [ "$2" = "$3" ]; then ok; else bad "$1: want [$2] got [$3]"; fi
}
# assert_contains <desc> <haystack> <needle>
assert_contains() {
    case "$2" in *"$3"*) ok ;; *) bad "$1: [$3] not found in output" ;; esac
}

DASH=$'\xe2\x80\x94'  # em dash, matches the real CHANGELOG headers

# Build a throwaway repo with realistic fixtures; echoes its path.
fixture_repo() {
    local d; d="$(mktemp -d)"
    (
        cd "$d"
        git init -q
        git config user.email "test@example.com"
        git config user.name "Test"
        cat > build.gradle <<'EOF'
plugins { }
allprojects {
    group = 'org.jdesktop.lg3d'
    version = '1.10.0-dev'
    repositories { mavenCentral() }
}
EOF
        cat > README.md <<'EOF'
## Project coordinates

- Group: `org.jdesktop.lg3d`
- Version: `1.10.0-dev`
EOF
        cat > AGENTS.md <<'EOF'
# AGENTS.md
**Project coordinates:** `org.jdesktop.lg3d:1.10.0-dev`
EOF
        printf '%s\n' \
"# Changelog" \
"" \
"Intro prose." \
"" \
"## [Unreleased] ${DASH} 1.10.0-dev ${DASH} Gradle / JDK 21 modernization" \
"" \
"### Added" \
"- **Feature A** — does a thing." \
"- **Feature B** — does another." \
"" \
"### Fixed" \
"- **Bug C** — fixed." \
"" \
"## [1.9.0] ${DASH} 2026-09-24 ${DASH} Gradle / JDK 21 modernization" \
"" \
"### Added" \
"- old release content" \
            > CHANGELOG.md
        git add -A
        git commit -qm "chore: initial fixture"
    )
    echo "$d"
}

# ===========================================================================
echo "== version parsing / comparison / bumping =="
(
    set -uo pipefail; . "$LIB"
    assert_eq "is_semver 1.2.3"      "0" "$(is_semver 1.2.3 && echo 0 || echo 1)"
    assert_eq "is_semver 1.2.3-dev no" "1" "$(is_semver 1.2.3-dev && echo 0 || echo 1)"
    assert_eq "is_version rc"        "0" "$(is_version 1.2.3-rc.1 && echo 0 || echo 1)"
    assert_eq "tag_to_version"       "1.9.0" "$(tag_to_version v1.9.0)"
    assert_eq "strip_dev"            "1.10.0" "$(strip_dev 1.10.0-dev)"
    assert_eq "strip_dev no suffix"  "1.10.0" "$(strip_dev 1.10.0)"
    assert_eq "bump major"           "2.0.0" "$(bump_version 1.9.0 major)"
    assert_eq "bump minor"           "1.10.0" "$(bump_version 1.9.0 minor)"
    assert_eq "bump minor rollover"  "1.10.0" "$(bump_version 1.9.5 minor)"
    assert_eq "bump patch"           "1.9.1" "$(bump_version 1.9.0 patch)"
    assert_eq "bump none"            "1.9.0" "$(bump_version 1.9.0 none)"
    assert_eq "cmp lt"               "-1" "$(version_cmp 1.9.0 1.10.0)"
    assert_eq "cmp gt"               "1"  "$(version_cmp 1.10.0 1.9.0)"
    assert_eq "cmp eq"               "0"  "$(version_cmp 1.9.0 1.9.0)"
    assert_eq "cmp patch"            "-1" "$(version_cmp 1.9.0 1.9.1)"
)

echo "== conventional-commit classification =="
(
    set -euo pipefail; . "$LIB"
    assert_eq "feat"            "minor" "$(classify_subject 'feat(lg3d-core): add x')"
    assert_eq "feat no scope"   "minor" "$(classify_subject 'feat: add x')"
    assert_eq "fix"             "patch" "$(classify_subject 'fix(lg3d-core): y')"
    assert_eq "fix no scope"    "patch" "$(classify_subject 'fix: y')"
    assert_eq "breaking bang"   "major" "$(classify_subject 'feat(api)!: change')"
    assert_eq "breaking word"   "major" "$(classify_subject 'refactor: x BREAKING CHANGE')"
    assert_eq "chore"           "none"  "$(classify_subject 'chore: bump version to 1.10.0-dev')"
    assert_eq "docs"            "none"  "$(classify_subject 'docs(agents): note')"
    assert_eq "refactor"        "none"  "$(classify_subject 'refactor(core): tidy')"
    assert_eq "perf"            "none"  "$(classify_subject 'perf: faster')"
    assert_eq "test"            "none"  "$(classify_subject 'test(core): cover')"
    assert_eq "merge subject"   "none"  "$(classify_subject 'Merge pull request #91 from x/y')"
)

echo "== changelog section extraction =="
(
    set -euo pipefail; . "$LIB"
    d="$(fixture_repo)"; cd "$d"
    sec="$(changelog_section 1.9.0)"
    assert_contains "1.9.0 header"     "$sec" "## [1.9.0]"
    assert_contains "1.9.0 body"       "$sec" "old release content"
    # Must stop at the section boundary and not bleed into [Unreleased].
    case "$sec" in *"Feature A"*) bad "section 1.9.0 bled into [Unreleased]";; *) ok;; esac
    unrel="$(changelog_section Unreleased)"
    assert_contains "unreleased header" "$unrel" "## [Unreleased]"
    assert_contains "unreleased body"   "$unrel" "Feature A"
    case "$unrel" in *"old release content"*) bad "unreleased bled into 1.9.0";; *) ok;; esac
    assert_eq "missing section empty" "" "$(changelog_section 9.9.9)"
    assert_eq "desc parsed" "Gradle / JDK 21 modernization" "$(changelog_desc)"
)

echo "== set_version_refs edits exactly the three non-changelog refs =="
(
    set -euo pipefail; . "$LIB"
    d="$(fixture_repo)"; cd "$d"
    set_version_refs 1.10.0 >/dev/null 2>&1
    assert_contains "build.gradle" "$(cat build.gradle)" "version = '1.10.0'"
    assert_contains "README"       "$(cat README.md)"    "- Version: \`1.10.0\`"
    assert_contains "AGENTS"       "$(cat AGENTS.md)"    "org.jdesktop.lg3d:1.10.0"
    # CHANGELOG must be untouched by set_version_refs.
    assert_contains "CHANGELOG unchanged" "$(cat CHANGELOG.md)" "## [Unreleased] ${DASH} 1.10.0-dev"
    # No .bak files left behind.
    assert_eq "no bak files" "0" "$(find . -name '*.bak' | wc -l | tr -d ' ')"
)

echo "== cut_changelog_release dates the header =="
(
    set -euo pipefail; . "$LIB"
    d="$(fixture_repo)"; cd "$d"
    cut_changelog_release 1.10.0 2026-10-01 >/dev/null 2>&1
    hdr="$(grep -m1 -E '^## \[1\.10\.0\]' CHANGELOG.md)"
    assert_contains "dated header" "$hdr" "## [1.10.0] ${DASH} 2026-10-01 ${DASH} Gradle / JDK 21 modernization"
    # The old [Unreleased] header is gone.
    case "$(cat CHANGELOG.md)" in *"## [Unreleased]"*) bad "cut left an [Unreleased] header";; *) ok;; esac
    # Body preserved.
    assert_contains "body preserved" "$(cat CHANGELOG.md)" "Feature A"
    # Idempotent: a second cut warns and does not duplicate.
    cut_changelog_release 1.10.0 2026-10-02 >/dev/null 2>&1
    assert_eq "one 1.10.0 header" "1" "$(grep -c -E '^## \[1\.10\.0\]' CHANGELOG.md)"
)

echo "== reopen_changelog_unreleased inserts a fresh section =="
(
    set -euo pipefail; . "$LIB"
    d="$(fixture_repo)"; cd "$d"
    cut_changelog_release 1.10.0 2026-10-01 >/dev/null 2>&1
    reopen_changelog_unreleased 1.11.0-dev >/dev/null 2>&1
    top="$(grep -m1 -E '^## \[' CHANGELOG.md)"
    assert_contains "reopened header on top" "$top" "## [Unreleased] ${DASH} 1.11.0-dev ${DASH}"
    # [Unreleased] must appear ABOVE [1.10.0].
    u_line="$(grep -n -E '^## \[Unreleased\]' CHANGELOG.md | head -1 | cut -d: -f1)"
    r_line="$(grep -n -E '^## \[1\.10\.0\]' CHANGELOG.md | head -1 | cut -d: -f1)"
    assert_eq "unreleased above release" "true" "$([ "$u_line" -lt "$r_line" ] && echo true || echo false)"
    # Idempotent: a second reopen is a no-op.
    reopen_changelog_unreleased 1.12.0-dev >/dev/null 2>&1
    assert_eq "one Unreleased header" "1" "$(grep -c -E '^## \[Unreleased\]' CHANGELOG.md)"
)

echo "== prepare-release.sh end to end =="
(
    set -uo pipefail
    d="$(fixture_repo)"; cd "$d"
    bash "$RELEASE_DIR/prepare-release.sh" 1.10.0 --date 2026-10-01 >/dev/null 2>&1
    assert_contains "build.gradle cut" "$(cat build.gradle)" "version = '1.10.0'"
    assert_contains "CHANGELOG dated"  "$(cat CHANGELOG.md)" "## [1.10.0] ${DASH} 2026-10-01"
    # A dirty tracked tree must be refused.
    echo "// dirty" >> build.gradle
    if bash "$RELEASE_DIR/prepare-release.sh" 1.11.0 --date 2026-10-02 >/dev/null 2>&1; then
        bad "prepare-release ran on a dirty tree"
    else
        ok
    fi
)

echo "== reopen-dev.sh end to end =="
(
    set -uo pipefail
    d="$(fixture_repo)"; cd "$d"
    bash "$RELEASE_DIR/prepare-release.sh" 1.10.0 --date 2026-10-01 >/dev/null 2>&1
    # Mirror the real flow: the release cut is committed (on the release branch)
    # and merged to main before reopen-dev runs, so main is clean again.
    git add -A && git commit -qm "chore(release): cut 1.10.0"
    bash "$RELEASE_DIR/reopen-dev.sh" --released 1.10.0 >/dev/null 2>&1
    assert_contains "next dev derived" "$(cat build.gradle)" "version = '1.10.1-dev'"
    assert_contains "unreleased readded" "$(cat CHANGELOG.md)" "## [Unreleased] ${DASH} 1.10.1-dev"
)

echo "== highest_bump / next_version_from_range over a synthetic history =="
(
    set -euo pipefail; . "$LIB"
    d="$(fixture_repo)"; cd "$d"
    git tag v1.0.0
    git commit -q --allow-empty -m "fix: a bug"
    git commit -q --allow-empty -m "chore: tidy"
    assert_eq "fix+chore -> patch" "patch" "$(highest_bump v1.0.0 HEAD)"
    assert_eq "next patch" "1.0.1" "$(next_version_from_range 1.0.0 v1.0.0 HEAD)"
    git commit -q --allow-empty -m "feat(x): a feature"
    assert_eq "feat wins -> minor" "minor" "$(highest_bump v1.0.0 HEAD)"
    assert_eq "next minor" "1.1.0" "$(next_version_from_range 1.0.0 v1.0.0 HEAD)"
    git commit -q --allow-empty -m "feat(api)!: breaking"
    assert_eq "breaking wins -> major" "major" "$(highest_bump v1.0.0 HEAD)"
    assert_eq "next major" "2.0.0" "$(next_version_from_range 1.0.0 v1.0.0 HEAD)"
    # Empty range falls back to the default (patch).
    git tag v2.0.0
    assert_eq "empty range -> none" "none" "$(highest_bump v2.0.0 HEAD)"
    assert_eq "empty range fallback" "2.0.1" "$(next_version_from_range 2.0.0 v2.0.0 HEAD)"
)

echo "== next-version.sh against a synthetic repo =="
(
    set -uo pipefail
    d="$(fixture_repo)"; cd "$d"
    git tag v1.4.0
    git commit -q --allow-empty -m "feat(core): new thing"
    got="$(bash "$RELEASE_DIR/next-version.sh" --quiet)"
    assert_eq "next-version feat -> 1.5.0" "1.5.0" "$got"
    got2="$(bash "$RELEASE_DIR/next-version.sh" --quiet --level patch)"
    assert_eq "next-version forced patch" "1.4.1" "$got2"
)

echo "== latest_stable_tag ignores pre-releases =="
(
    set -euo pipefail; . "$LIB"
    d="$(fixture_repo)"; cd "$d"
    git tag v1.8.0; git tag v1.9.0; git tag v1.10.0-rc.1; git tag v2.0.0-alpha
    assert_eq "latest stable" "v1.9.0" "$(latest_stable_tag)"
)

PASS="$(grep -c '^PASS$' "$RESULTS" || true)"
FAIL="$(grep -c '^FAIL ' "$RESULTS" || true)"
echo
echo "-----------------------------------------"
echo "passed: ${_c_green}${PASS}${_c_nc}   failed: ${_c_red}${FAIL}${_c_nc}"
[ "$FAIL" -eq 0 ] || exit 1
echo "ALL RELEASE-SCRIPT TESTS PASSED"
