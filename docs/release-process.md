# Release process — the automated release train

This is the turnkey guide to shipping a Project Looking Glass release. The whole
lifecycle — milestone tracking, version derivation, release-candidate builds,
release notes and the final publish — is automated by two GitHub Actions
workflows plus a small, unit-tested shell library. **The only manual step is the
approval gate: merging the `release/X.Y.Z` pull request.**

Everything here is driven by [Conventional Commits](https://www.conventionalcommits.org/)
merged to `main`, so writing good commit/PR titles is what feeds the machine.

---

## 1. The moving parts

| Piece | Path | Role |
| --- | --- | --- |
| Orchestrator workflow | [`.github/workflows/release-train.yml`](../.github/workflows/release-train.yml) | Milestones, RC cuts, promotion, dev re-open. |
| Publisher workflow | [`.github/workflows/release.yml`](../.github/workflows/release.yml) | Builds a `v*` tag and publishes the GitHub Release + assets. |
| CI lint + self-test | [`.github/workflows/build.yml`](../.github/workflows/build.yml) (`release-ci` job) | ShellChecks the scripts and runs their self-tests on every push/PR. |
| Shared shell library | [`scripts/release/lib.sh`](../scripts/release/lib.sh) | All deterministic logic (version math, CHANGELOG surgery, commit classification). |
| Version deriver | [`scripts/release/next-version.sh`](../scripts/release/next-version.sh) | Computes the next `X.Y.Z` from the commits since the last stable tag. |
| Release cutter | [`scripts/release/prepare-release.sh`](../scripts/release/prepare-release.sh) | Dates the CHANGELOG and drops `-dev` from the version refs. |
| Dev re-opener | [`scripts/release/reopen-dev.sh`](../scripts/release/reopen-dev.sh) | Re-adds `[Unreleased]` and bumps the refs to the next `-dev`. |
| Notes builder | [`scripts/release/release-notes.sh`](../scripts/release/release-notes.sh) | Assembles the GitHub Release body (CHANGELOG section + PR/issue appendix). |
| Self-tests | [`scripts/release/tests/run.sh`](../scripts/release/tests/run.sh) | 63 assertions over throwaway fixture repos; no external test framework. |

Design rule: **all decision logic lives in the shell library (unit-tested); the
YAML is thin orchestration.** That is why the release train can be verified
headless with `bash scripts/release/tests/run.sh` instead of needing a live
GitHub event.

---

## 2. One-time setup (required for full automation)

Create a repository secret named **`RELEASE_PAT`**:

1. GitHub → your account → *Settings → Developer settings → Personal access
   tokens → Fine-grained tokens → Generate new token*.
2. Repository access: **Only select repositories** → `landrevillejf/ProjectLookingGlass`.
3. Permissions: **Contents: Read and write** and **Pull requests: Read and write**
   (Issues: Read and write is also used for milestone/issue handling).
4. Copy the token into the repo: *Settings → Secrets and variables → Actions →
   New repository secret*, name `RELEASE_PAT`, paste the value.

**Why a PAT and not the default `GITHUB_TOKEN`?** GitHub never starts a new
workflow run from an event caused by the default `GITHUB_TOKEN` (an
anti-recursion guard). The release train hands off to `release.yml` by pushing a
`v*` **tag**; if that push used `GITHUB_TOKEN`, `release.yml` would *not* fire and
no release would be published. Pushing tags/branches with `RELEASE_PAT` makes the
hand-off fire.

> **Degraded mode.** If `RELEASE_PAT` is missing, every job still runs using
> `GITHUB_TOKEN` and prints a `::warning::`. The tag/branch is created, but you
> must then start `release.yml` yourself from its *Run workflow* (workflow_dispatch)
> button. Never commit a PAT to the repository.

No other setup is needed: the milestone, branches, tags, PRs and releases are all
created by the workflows.

---

## 3. The lifecycle end to end

```
              PRs merged to main
                     │
                     ▼
        ┌────────────────────────┐   every merged PR is attached to the
        │  sync-milestone        │   one open milestone, whose title is the
        │  (next version X.Y.Z)  │   next version derived from the commits
        └───────────┬────────────┘
                    │  milestone has 0 open items and ≥1 closed
                    ▼
        ┌────────────────────────┐   opens PR "Release X.Y.Z" on branch
        │  cut-rc                │   release/X.Y.Z (CHANGELOG dated, -dev
        │  tag vX.Y.Z-rc.1       │   dropped) and tags the first RC
        └───────────┬────────────┘
                    │                     release.yml builds the tag and
                    │  (push more fixes)  publishes a PRE-RELEASE
                    ▼
        ┌────────────────────────┐   each new commit on release/X.Y.Z
        │  refresh-rc            │   tags rc.2, rc.3, …
        └───────────┬────────────┘
                    │
        ★ THE ONE MANUAL STEP: review + merge the "Release X.Y.Z" PR ★
                    │
                    ▼
        ┌────────────────────────┐   tags vX.Y.Z  → release.yml publishes
        │  promote               │   the LATEST release + version.json,
        │  (on PR merge)         │   closes the milestone, and opens the
        └───────────┬────────────┘   chore: bump to <next>-dev PR
                    ▼
        ┌────────────────────────┐   re-adds [Unreleased], sets refs to
        │  reopen-dev (auto PR)  │   <next>-dev → the cycle starts again
        └────────────────────────┘
```

### The four canonical version references

`prepare-release.sh` and `reopen-dev.sh` keep these four in lock-step (never edit
them by hand during a release):

1. `build.gradle` — `allprojects { version = 'X.Y.Z' }`
2. `README.md` — *Project coordinates → Version:*
3. `AGENTS.md` — `**Project coordinates:** org.jdesktop.lg3d:X.Y.Z`
4. `CHANGELOG.md` — the `## [Unreleased] — X.Y.Z-dev — …` / `## [X.Y.Z] — <date> — …` header

---

## 4. Version policy (auto from Conventional Commits)

`next-version.sh` scans `git log --no-merges` from the latest **stable** tag
(`vX.Y.Z`, pre-releases ignored) to `HEAD` and takes the **highest** bump:

| Highest commit type in range | Bump | Example (from `1.9.0`) |
| --- | --- | --- |
| `feat(…)!:` or a `BREAKING CHANGE` footer | **major** | `2.0.0` |
| `feat(…):` | **minor** | `1.10.0` |
| `fix(…):` | **patch** | `1.9.1` |
| anything else (`chore`, `docs`, `refactor`, `perf`, `test`) | none → falls back to **patch** | `1.9.1` |

Merge commits are skipped (their subjects are `Merge pull request #N …`), so the
classification reads the real work, not the merge noise. During development the
refs carry a `-dev` suffix (`1.10.0-dev`); the release cut drops it.

---

## 5. Release notes content

`release-notes.sh` builds the GitHub Release body from two parts:

1. **The curated CHANGELOG section** for the version (falls back to
   `[Unreleased]` when cutting an RC whose section is not dated yet).
2. **An auto appendix** of the merged pull requests in `PREV..HEAD`, grouped into
   *Features* / *Fixes* / *Other changes*, each linked and attributed to its author
   (`gh pr view` when available, otherwise the merge-commit subject), plus the
   *Closed issues* for the milestone.

The appendix is **best-effort**: if `gh` is unavailable or an API call fails
(offline, no token), the notes degrade gracefully to the CHANGELOG section and the
raw merge subjects, so publishing never hard-fails on notes generation.

---

## 6. Pre-release vs. latest (why an RC is safe)

`release.yml` inspects the tag:

- `vX.Y.Z-rc.N` (or `-alpha`, `-beta`) → published with `--prerelease`.
  GitHub's `releases/latest` endpoint **excludes** pre-releases, so an RC never
  hijacks the update-manager's `releases/latest/download/version.json`.
- `vX.Y.Z` → published as the **latest** release, and `version.json` is updated
  so the update-manager offers it.

Both build the same `:lg3d-core:releaseBundle -PreleaseVersion=<v>` bundle
(`lg3d-<v>.zip`) and attach the generated `RELEASE-NOTES.md` as the body.

---

## 7. Stage reference (`release-train.yml`)

| Job | Trigger | What it does |
| --- | --- | --- |
| `sync-milestone` | every train event | Derives the next version; ensures exactly **one** open milestone titled with it. Outputs `version`, `milestone`, `milestone_number`. |
| `assign` | a PR is **merged** to `main` (not `release/*`, not `chore/bump*`) | Attaches that PR to the open milestone, then fires a `repository_dispatch: evaluate-release`. |
| `cut-rc` | `evaluate-release` dispatch, daily schedule, an issue closed, or manual `cut-rc` | Gate: version resolvable, no existing `vX.Y.Z-rc.*` tag, no open `release/X.Y.Z` PR, and (unless `force`) the milestone is **complete** (0 open, ≥1 closed). Then creates `release/X.Y.Z`, runs `prepare-release.sh`, opens the *Release X.Y.Z* PR with the notes body + promotion checklist, and tags `vX.Y.Z-rc.1`. |
| `refresh-rc` | a **push** to an existing `release/**` branch (`created == false`) | Tags the next `rc.N+1` so updated branches rebuild. |
| `promote` | the `release/*` PR is **merged** | Tags the final `vX.Y.Z` (→ `release.yml` publishes latest), closes the milestone, and opens the `chore: bump to <next>-dev` PR via `reopen-dev.sh`. |
| `manual-reopen-dev` | manual `reopen-dev` | Re-opens the dev cycle by hand if the auto PR was skipped. |

**Idempotency.** Every stage checks for existing tags/branches/PRs before acting,
so a re-run (or the daily schedule) never creates duplicates.

**Race-free triggering.** The train does not rely on fragile event ordering. The
`assign` job explicitly dispatches `evaluate-release`, and a **daily schedule**
(`cron: '17 7 * * *'`) re-checks milestone completion as a safety net, so even if
a dispatch is dropped (e.g. degraded mode) the RC still gets cut within a day.

---

## 8. Manual overrides (workflow_dispatch)

*Actions → Release train → Run workflow* exposes:

- **`action`**: `sync-milestone`, `cut-rc`, `promote`, or `reopen-dev`.
- **`version`**: override the derived `X.Y.Z` (blank = derive from commits).
- **`force`**: cut even if the milestone still has open items.

Use these to drive any single stage by hand — e.g. `cut-rc` with `force=true` to
ship an RC before every milestone issue is closed, or `reopen-dev` if the
post-release bump PR needs recreating.

---

## 9. Verifying locally

```bash
# All deterministic logic (63 assertions over throwaway fixture repos):
bash scripts/release/tests/run.sh

# What version would the next release be, from real history?
bash scripts/release/next-version.sh --quiet

# Preview the notes for a version (best-effort; uses gh if authenticated):
bash scripts/release/release-notes.sh --version 1.10.0 --repo landrevillejf/ProjectLookingGlass

# Dry-run the CHANGELOG/ref surgery without writing:
bash scripts/release/prepare-release.sh 1.10.0 --dry-run
bash scripts/release/reopen-dev.sh --released 1.10.0 --dry-run
```

The same checks run in CI: the `release-ci` job in `build.yml` YAML-parses every
workflow, `bash -n` + ShellChecks the scripts, and runs the self-tests on every
push and pull request.

---

## 10. Shipping a release — the short version

1. Merge feature/fix PRs to `main` as usual (Conventional Commit titles). The
   train attaches each to the open milestone automatically.
2. When the milestone is complete, the train opens the **Release X.Y.Z** PR and
   publishes `vX.Y.Z-rc.1` as a pre-release. Smoke-test the RC bundle.
3. Need another fix? Merge it into the `release/X.Y.Z` branch — the train tags
   `rc.2` and rebuilds.
4. **Merge the Release X.Y.Z PR** (the approval gate). The train tags `vX.Y.Z`,
   `release.yml` publishes it as the latest release + `version.json`, the
   milestone closes, and the `chore: bump to <next>-dev` PR opens.
5. Merge the bump PR (or let it merge automatically per your policy) — the dev
   cycle is re-opened and the train starts tracking the next version.

That is the entire process. Steps 1–4 require no commands; step 4 is a single
click.

---

## 11. Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| RC tag pushed but no release published | `RELEASE_PAT` missing → the tag used `GITHUB_TOKEN` and `release.yml` did not fire. Add the secret, or run `release.yml` manually for that tag. |
| No RC is ever cut | The milestone still has open items (the "right moment" gate). Close them, or run `cut-rc` with `force=true`. The daily schedule retries regardless. |
| More than one open milestone | The train warns and uses the first. Close the extras so the version is unambiguous. |
| `release/X.Y.Z` PR exists but no new RC | `refresh-rc` only fires on a **push to an existing** branch (`created == false`). Push a commit, or run `cut-rc` manually. |
| Notes missing the PR appendix | `gh` was unauthenticated/offline at build time. The CHANGELOG section is still emitted; re-run once `RELEASE_PAT`/`gh` is available. |
| Wrong next version | Version comes from commit types since the last **stable** tag. Check `bash scripts/release/next-version.sh`, or pass an explicit `version` override on dispatch. |
