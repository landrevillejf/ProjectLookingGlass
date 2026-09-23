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

There is currently **no automated dependency vulnerability scanning** (e.g.
OWASP Dependency-Check) and **no generated SBOM**. A hard CVE gate is not
appropriate while these known-vulnerable jars are intentionally retained; see
[`AUDIT.md`](AUDIT.md) for the tracked remediation plan.
