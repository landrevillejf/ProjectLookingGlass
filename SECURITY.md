# Security Policy

Project Looking Glass (lg3d) is a **modernization port of 2006-era abandonware**
maintained on a best-effort, volunteer basis. It is a local desktop environment,
not a networked service, but it does interact with the host system, so security
issues are taken seriously.

## Reporting a vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

Use one of these private channels instead:

1. **GitHub private vulnerability reporting** — open the repository's
   **Security** tab and choose **Advisories → Report a vulnerability**. This is
   the preferred path: it keeps the report private while allowing maintainers to
   coordinate a fix and, if warranted, publish an advisory.
2. If private reporting is unavailable, contact the repository maintainer
   privately through GitHub (the account that owns
   `landrevillejf/ProjectLookingGlass`) rather than in a public thread.

Please include:

- A description of the issue and its impact.
- Step-by-step reproduction instructions (module, task, or app involved).
- The affected version/commit and platform.
- Any suggested fix or mitigation, if you have one.

We will acknowledge a report as soon as we can and work with you to understand
and address it. Because this is a volunteer-maintained port there is **no formal
SLA or guaranteed response time**, and no bug bounty.

## Supported versions

Only the current `main` branch (the `1.9.0-dev` line) receives fixes. There are
no maintained release branches or backports.

## Security-relevant design notes

- **System access is pure Java.** The desktop shells out to standard tools
  (`xrandr`, `xdg-open`, `pkexec`, `/proc`, `/sys`) through `ProcessBuilder`
  (there are no `Runtime.getRuntime().exec` call sites in the built modules) —
  no JNI, no JNA, no patched JDK.
- **Privilege escalation is user-consented.** Operations that need root go
  through `pkexec`, which prompts the user; the desktop itself does not run as
  root.
- **No telemetry by default.** The only outbound network use in the shipped
  desktop is the opt-in **Weather widget**, which queries the free Open-Meteo
  API with no API key and no credentials.
- **X11 compositor mode is privileged.** `-Pcompositor` / `run-lg3d.sh -x` makes
  lg3d the X11 window manager/compositor and claims `SubstructureRedirect`. It
  must be started on a bare Xorg with **no other window manager** already
  holding that selection; treat it as an advanced, trust-the-display mode.

## Known risk: bundled legacy third-party libraries

The experimental **`lg3d-incubator`** module still compiles against ~26
2006-era third-party jars vendored under `lg3d-incubator/ext/` — including
`log4j-1.2.8`, `axis`, `bcprov-jdk14` (BouncyCastle for JDK 1.4), `xercesImpl`,
old `commons-*`, `jmf`, `jai`, `jxta` and similar. These libraries are
end-of-life and **may contain unpatched CVEs**. They are retained only because
no compatible, redistributable replacement exists for the historical incubator
apps that use them.

Mitigations in place:

- The **core desktop does not depend on them.** They are compile-time
  dependencies of the incubator apps only.
- The desktop **run classpath** (`:lg3d-core:run`) adds only a specific,
  minimal subset actually needed at runtime (JAI/JMF codecs, commons-cli,
  javanlp, nanoxml, prefuse). The JAXP-overriding `xercesImpl` and the other
  unused jars are deliberately kept **off** the run classpath.
- Incubator applications should be treated as **experimental and untrusted**;
  they are not part of the validated desktop.

## Supply-chain scanning (hard gate)

Automated supply-chain visibility is wired in and **fails the build**: a CVSS >= 7
finding in a dependency the build resolves reddens the pull request.

This is a change of policy, not just of wording. This file used to describe a
scan that could inform but never fail, configured with a CVSS ceiling above the
maximum real score of 10 -- a value chosen precisely because it can never fire --
and the reason given for the softness was the retained legacy jars above. The
reason was sound; the mechanism was not. A bank does not answer an unfixable
vulnerability by switching the scanner off. It accepts the risk **in writing**,
per artefact, with a named owner of the decision and a date when the decision
must be made again. That is what the gate now enforces:

- **SBOM.** `./gradlew cyclonedxBom` writes a CycloneDX Software Bill of
  Materials (exact resolved component versions) per module under
  `<module>/build-gradle/reports/bom.{json,xml}`.
- **Dependency vulnerability scanning.** `./gradlew dependencyCheckAggregate`
  runs OWASP Dependency-Check across every module's resolved dependencies
  (including the bundled incubator jars) against the NVD, gated at
  `failBuildOnCVSS = 7f` with `failOnError = true`. The second knob matters as
  much as the first: an interrupted NVD sync must not exit 0 on an empty report,
  because a scan that silently checked nothing publishes a clean result. Pass
  `-PnvdApiKey=KEY` (or set `ORG_GRADLE_PROJECT_nvdApiKey`) to speed up the NVD
  feed download.
- **Every exception is a signed lease.**
  [`config/owasp/suppressions.xml`](config/owasp/suppressions.xml) is the only
  place a finding may be set aside, and each entry is exactly one vendored binary
  matched on its own file path, carrying a written justification and an `until`
  review date. Dependency-check stops applying an expired suppression, so a
  set-aside whose date has passed makes the build red again until a person
  re-signs it or removes the jar -- which is the point of the date. Naming a CVE,
  a CPE, a Maven coordinate, a file hash or a CVSS threshold is not allowed:
  those silence a *class* of findings instead of one decision, and for anything
  resolved from Maven Central the answer is the upgrade, never an exclusion.
  [`scripts/mandela/cve-scope.mnd`](scripts/mandela/cve-scope.mnd) enforces that
  shape in `repositoryChecks`, so the exclusions file cannot quietly decay into a
  mute button, and it fails if a newly vendored binary inherits a neighbour's
  waiver instead of carrying its own.
- **What the gate cannot see.** Dependency-check analyses *declared*
  dependencies. The three executables the desktop forks as child processes --
  `swing-ide.jar`, `openapi-editor.jar` and `payloadman.jar` -- are fetched on
  demand and appear in no build file, so they ship in the release bundle without
  ever being scanned. Closing that gap means scanning the assembled bundle, not
  writing a suppression for a jar the scan never read.
- **CI.** The `cve` job in `.github/workflows/build.yml` runs the aggregate scan
  on every pull request and every push to `main`, with no `continue-on-error`:
  a step that cannot fail is not a gate. The `security` job additionally
  regenerates the SBOM on the weekly schedule and manual dispatch, where the cold
  NVD download is acceptable, and uploads both reports as artifacts.

Findings against the retained legacy jars are expected, and are therefore not
silent: they are listed by name, with their justification and their expiry, in
the exclusions file.

See [`AUDIT.md`](AUDIT.md) for the tracked remediation plan.
