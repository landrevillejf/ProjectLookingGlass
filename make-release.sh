#!/usr/bin/env bash
# make-release.sh — cut a Project Looking Glass (lg3d) release.
#
# This is a thin, convention-compliant helper. It does ONLY the two things CI
# cannot do for you; everything else — build, test, packaging lg3d-<version>.zip,
# version.json, optional PGP signing and publishing the GitHub Release — is done
# by .github/workflows/release.yml, which fires automatically on a pushed `v*`
# tag (see that workflow). So "cutting a release" here means:
#
#   1. bump the four canonical version references and open a `chore` PR, then
#   2. once that PR is merged, create + push the annotated v<version> tag that
#      triggers the release workflow.
#
# The four canonical version references (kept in sync by convention) are:
#   build.gradle   -> allprojects { version = '<version>' }
#   README.md      -> Project coordinates: - Version: `<version>`
#   AGENTS.md      -> Project coordinates: org.jdesktop.lg3d:<version>
#   CHANGELOG.md   -> the `## [Unreleased] — <version> — ...` header
#
# Usage:
#   ./make-release.sh <version> [options]
#     <version>      target release version, X.Y.Z (no leading 'v'), e.g. 1.9.0
#
# Options:
#   --skip-bump      do not edit the four refs (assume main is already bumped);
#                    useful with --tag to only create and push the tag.
#   --no-pr          commit the bump on the CURRENT branch instead of creating a
#                    chore/bump-<version> branch + pull request.
#   --tag            also create the annotated v<version> tag and push it, which
#                    triggers .github/workflows/release.yml.
#   --dry-run        print every action without writing, committing or pushing.
#   -h, --help       show this help.
#
# SAFETY: this script NEVER runs `git add -A` / `git add .`. The working tree
# intentionally holds untracked runtime artefacts (lg3d-core/lgscreen-*.png,
# libs/swing-ide.jar, downloaded files, ...) that must stay out of commits, so
# only the four version files are ever staged. It also refuses to run when
# tracked files are dirty, so it never silently swallows unrelated work.
set -euo pipefail

# ---------- Colors (disabled when stdout is not a TTY) ----------
if [ -t 1 ]; then
    RED=$'\033[0;31m'; GREEN=$'\033[0;32m'; YELLOW=$'\033[1;33m'
    BLUE=$'\033[0;34m'; NC=$'\033[0m'
else
    RED=''; GREEN=''; YELLOW=''; BLUE=''; NC=''
fi
info()  { echo "${BLUE}==>${NC} $*"; }
ok()    { echo "${GREEN}==>${NC} $*"; }
warn()  { echo "${YELLOW}warning:${NC} $*" >&2; }
die()   { echo "${RED}error:${NC} $*" >&2; exit 1; }

# ---------- Help ----------
show_help() {
    # Print the leading comment block (line 2 up to the first non-comment line),
    # stripping the leading '# '. Robust to the header changing length.
    awk 'NR>=2 { if ($0 ~ /^#/) { sub(/^#[[:space:]]?/, ""); print } else exit }' "${BASH_SOURCE[0]}"
    exit 0
}

# ---------- Parse arguments ----------
VERSION=""
SKIP_BUMP=false
NO_PR=false
DO_TAG=false
DRY_RUN=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-bump) SKIP_BUMP=true ;;
        --no-pr)     NO_PR=true ;;
        --tag)       DO_TAG=true ;;
        --dry-run)   DRY_RUN=true ;;
        -h|--help)   show_help ;;
        -*)          die "unknown option: $1 (see --help)" ;;
        *)           [ -z "$VERSION" ] || die "unexpected argument: $1"; VERSION="$1" ;;
    esac
    shift
done

[ -n "$VERSION" ] || die "a target version is required, e.g. ./make-release.sh 1.9.0 (see --help)"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] \
    || die "version '$VERSION' is not X.Y.Z (do not include a leading 'v' or a -dev suffix)"

# ---------- Locate the repository root ----------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
git rev-parse --git-dir >/dev/null 2>&1 || die "this directory is not a Git repository."
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

BUILD_FILE="build.gradle"
README_FILE="README.md"
AGENTS_FILE="AGENTS.md"
CHANGELOG_FILE="CHANGELOG.md"
WORKFLOW_FILE=".github/workflows/release.yml"
for f in "$BUILD_FILE" "$README_FILE" "$AGENTS_FILE" "$CHANGELOG_FILE"; do
    [ -f "$f" ] || die "expected file '$f' not found at the repository root."
done

# ---------- Refuse to run on a dirty tracked tree ----------
# Untracked files (runtime artefacts) are fine and deliberately ignored.
if ! git diff --quiet || ! git diff --cached --quiet; then
    git status --short | grep -vE '^\?\?' || true
    die "there are uncommitted changes to tracked files (above). Commit or stash them first; this script never runs 'git add -A'."
fi

CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
DATE="$(date +%Y-%m-%d)"

# ---------- Read the current version from build.gradle ----------
OLD_VERSION="$(grep -oP "^\s*version\s*=\s*'\K[^']+" "$BUILD_FILE" | head -1 || true)"
[ -n "$OLD_VERSION" ] || die "could not read the current version from $BUILD_FILE."
info "current version: ${OLD_VERSION}   target version: ${VERSION}"

if [ "$OLD_VERSION" = "$VERSION" ]; then
    warn "$BUILD_FILE is already at ${VERSION}; the bump is a no-op."
fi

run() {  # run <cmd...>: echo, and execute unless --dry-run
    echo "  ${BLUE}\$${NC} $*"
    [ "$DRY_RUN" = true ] || "$@"
}

# ---------- Step 1: bump the four canonical refs ----------
bump() {
    info "bumping the four canonical version references to ${VERSION}"

    # Each edit is verified beforehand so a pattern that no longer matches is a
    # hard failure rather than a silent, corrupting no-op.
    grep -q "version = '${OLD_VERSION}'" "$BUILD_FILE" \
        || die "$BUILD_FILE does not contain \"version = '${OLD_VERSION}'\"."
    grep -q "\`org.jdesktop.lg3d:${OLD_VERSION}\`" "$AGENTS_FILE" \
        || die "$AGENTS_FILE does not contain the org.jdesktop.lg3d:${OLD_VERSION} coordinate."
    grep -q -e "- Version: \`${OLD_VERSION}\`" "$README_FILE" \
        || die "$README_FILE does not contain \"- Version: \`${OLD_VERSION}\`\"."
    grep -qE "^## \[Unreleased\] — ${OLD_VERSION//./\\.} — " "$CHANGELOG_FILE" \
        || die "$CHANGELOG_FILE has no '## [Unreleased] — ${OLD_VERSION} — ' header to release-cut."

    run sed -i.bak "s/version = '${OLD_VERSION}'/version = '${VERSION}'/" "$BUILD_FILE"
    run sed -i.bak "s|- Version: \`${OLD_VERSION}\`|- Version: \`${VERSION}\`|" "$README_FILE"
    run sed -i.bak "s|org.jdesktop.lg3d:${OLD_VERSION}|org.jdesktop.lg3d:${VERSION}|" "$AGENTS_FILE"
    # Release-cut the changelog header: [Unreleased] — <old> —  ->  [<version>] — <date> —
    run sed -i.bak -E "s/^## \[Unreleased\] — [^ ]+ — /## [${VERSION}] — ${DATE} — /" "$CHANGELOG_FILE"
    [ "$DRY_RUN" = true ] || rm -f "$BUILD_FILE.bak" "$README_FILE.bak" "$AGENTS_FILE.bak" "$CHANGELOG_FILE.bak"

    if [ "$DRY_RUN" != true ]; then
        # Post-condition: every ref now reports the target version.
        grep -q "version = '${VERSION}'" "$BUILD_FILE"            || die "bump failed in $BUILD_FILE."
        grep -q -e "- Version: \`${VERSION}\`" "$README_FILE"         || die "bump failed in $README_FILE."
        grep -q "org.jdesktop.lg3d:${VERSION}" "$AGENTS_FILE"      || die "bump failed in $AGENTS_FILE."
        grep -qE "^## \[${VERSION}\] — ${DATE} — " "$CHANGELOG_FILE" || die "bump failed in $CHANGELOG_FILE."
        ok "four refs now report ${VERSION}"
    fi
}

# ---------- Step 2: commit the bump (explicit staging only) ----------
commit_bump() {
    local branch="chore/bump-${VERSION}"
    if [ "$NO_PR" = true ]; then
        info "committing the bump on the current branch (${CURRENT_BRANCH})"
    else
        if [ "$CURRENT_BRANCH" != "main" ] && [ "$CURRENT_BRANCH" != "master" ]; then
            warn "not on main/master (on '${CURRENT_BRANCH}'); branching '${branch}' from here."
        fi
        run git checkout -b "$branch"
    fi

    # Stage ONLY the four version files — never `git add -A`.
    run git add "$BUILD_FILE" "$README_FILE" "$AGENTS_FILE" "$CHANGELOG_FILE"
    run git commit -s -m "chore: bump version to ${VERSION}"
}

# ---------- Step 3: open the pull request ----------
open_pr() {
    local branch="chore/bump-${VERSION}"
    command -v gh >/dev/null 2>&1 || { warn "gh CLI not found; skipping PR creation. Push manually:  git push -u origin ${branch}"; return 0; }
    run git push -u origin "$branch"
    if [ "$DRY_RUN" = true ]; then
        echo "  ${BLUE}\$${NC} gh pr create --base main --head ${branch} --title \"chore: bump version to ${VERSION}\""
        return 0
    fi
    gh pr create --base main --head "$branch" \
        --title "chore: bump version to ${VERSION}" \
        --body "Cut the ${VERSION} release: drop the -dev suffix from the four canonical version references (build.gradle, README.md, AGENTS.md, and the CHANGELOG header, now dated $DATE) so main matches the v${VERSION} tag the release workflow is triggered from.

After this merges, tag and push to publish:

\`\`\`bash
git tag v${VERSION} && git push origin v${VERSION}
\`\`\`

or run \`./make-release.sh ${VERSION} --skip-bump --tag\`. CI (.github/workflows/release.yml) then builds, tests, packages lg3d-${VERSION}.zip and publishes version.json to the GitHub Release." \
        || die "gh pr create failed."
    ok "pull request opened for ${branch}"
}

# ---------- Step 4: create + push the annotated tag (triggers CI release) ----------
make_tag() {
    # The tag fires the release workflow only if the tagged commit carries the
    # push:tags trigger, so warn when it is missing (e.g. tagging an old commit).
    if [ -f "$WORKFLOW_FILE" ] && ! grep -qE "^\s*tags:" "$WORKFLOW_FILE"; then
        warn "$WORKFLOW_FILE has no 'tags:' trigger on this commit; pushing v${VERSION} may not start the release workflow."
    fi
    # Sanity: the tag should point at a commit whose build.gradle is at VERSION.
    local head_version
    head_version="$(grep -oP "^\s*version\s*=\s*'\K[^']+" "$BUILD_FILE" | head -1 || true)"
    if [ "$head_version" != "$VERSION" ]; then
        warn "$BUILD_FILE at HEAD reports '${head_version}', not '${VERSION}'. Make sure the bump PR is merged before tagging."
    fi
    if git rev-parse "v${VERSION}" >/dev/null 2>&1; then
        die "tag v${VERSION} already exists. Delete it first (git tag -d v${VERSION}) if you intend to re-cut."
    fi
    run git tag -a "v${VERSION}" -m "Release v${VERSION}"
    run git push origin "v${VERSION}"
    ok "pushed tag v${VERSION} — .github/workflows/release.yml will build and publish the release."
}

# ---------- Orchestrate ----------
if [ "$SKIP_BUMP" != true ]; then
    bump
    commit_bump
    if [ "$NO_PR" != true ]; then
        open_pr
        echo
        info "next: merge the PR, then tag with:"
        echo "     git checkout main && git pull --ff-only"
        echo "     ./make-release.sh ${VERSION} --skip-bump --tag"
    fi
else
    info "--skip-bump: leaving the four refs untouched."
fi

if [ "$DO_TAG" = true ]; then
    make_tag
fi

if [ "$DRY_RUN" = true ]; then
    echo
    warn "dry run: nothing was written, committed or pushed."
fi
ok "done."
