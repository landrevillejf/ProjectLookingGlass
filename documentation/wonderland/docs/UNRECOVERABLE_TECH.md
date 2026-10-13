# Wonderland port — unrecoverable tech, rebuilt vs. ported

Wonderland.jar is a **400 MB installer of Java 5 bytecode**, not a source archive.
Most of it can be decompiled and hand-adapted to JDK 21 — but a set of bundled
technologies **cannot be recovered at all**: they were removed from the JDK, retired
upstream, or (like the 3D scene graph) were never in the bundle and were fetched over
Java Web Start at launch. This page is the standing decision list for each one: what
gets **ported** and what gets **rebuilt from scratch** with a modern replacement.

It is a *veto-at-confirm* list — the migration was approved with these decisions in
place, but any of them can be reversed before its milestone lands. The living version
lives in
[`docs/wonderland-migration.md`](https://github.com/landrevillejf/ProjectLookingGlass/blob/main/docs/wonderland-migration.md).

## The rule that governs the whole port

Nothing is silently dropped. Where a technology is unrecoverable, the port **rebuilds
its function** on a maintained equivalent and says so; where even that is deferred, it
is documented as deferred, never claimed as working. Rendering and the scene graph are
the biggest such seam and are folded into **M6** — see
**[Milestones](MILESTONES.md)**.

## Rebuild-vs-port decisions

| Bundled tech | Fate | Replacement |
|--------------|------|-------------|
| **Java Web Start / JNLP launcher** | ❌ rebuilt (removed in JDK 9) | Plain `main()` launched via `run-wonderland.sh` and a Gradle classpath task. |
| **`javax.vecmath`** | ❌ rebuilt (removed; house rule forbids the import) | `org.jogamp.vecmath` — the repo-wide vecmath since the Looking Glass migration. |
| **`javax.media.j3d` + `com.sun.j3d`** | ❌ rebuilt (never bundled; removed; forbidden) | `org.jogamp.java3d` — the repo-wide Java 3D since the LG3D migration. |
| **`com.jme.*` (jMonkeyEngine 1.x)** | ❌ rebuilt (was fetched over JNLP — no bytecode in the jar) | A minimal in-tree shim on `org.jogamp.vecmath`, implementing exactly the surface the data model calls. |
| **`javax.xml.bind.*` (JAXB)** | ❌ rebuilt (removed from the JDK in Java 11) | `jakarta.xml.bind` API (still the `javax.xml.bind` namespace) + the GlassFish JAXB runtime; verified by the `ChecksumList` round-trip. |
| **`sun.misc.Service.providers(...)`** | ❌ rebuilt (internal, removed in JDK 9) | `java.util.ServiceLoader` inside the rebuilt `ScannedClassLoader`. |
| **scannotation** | ❌ rebuilt (dead; can't read modern class files) | `org.reflections`, scoped to `org.jdesktop.wonderland` so the scan stays cheap. |
| **GlassFish Embedded 3.0** (~26,206 classes) | ❌ rebuilt (retired; drags in a whole app server) | `wonderland-server` on **Spring Boot 3** — embedded Tomcat + Spring WebSocket + REST controllers. |
| **Sun Game Server (SGS)** | ❌ rebuilt (retired) | Folded into the Spring Boot server as a plain `@Service` owning the session registry. |
| **Apache MINA** | ❌ rebuilt (only SGS used it) | Spring's `WebSocketHandler` gives the codec-per-frame loop; no NIO pipeline, no MINA/Netty. |
| **winp** (native JNI) | ❌ rebuilt (retired; only enumerated processes) | `java.lang.ProcessHandle` (JDK 9+). |
| **NetBeans Platform module system** (71 module jars) | ❌ rebuilt (NBM toolchain gone; no source) | A plain `ServiceLoader` plugin loader — **[Plugin Loader](PLUGIN_LOADER.md)**. |
| **`darkstar`, `sas`/`sasxremwin`/`xremwin`** | ❌ dropped | Their applet / 2D-window embedding is already covered by the migrated Jogamp stack + `SwingNode`. |

**Deferred, not rejected** (may still be dropped at their milestone with a written
reason):

- **`stun`** — VoIP / softphone, lands or is descoped at **M7** (candidate: `ice4j`).
- **`NetworkAddress`** — only used by the GlassFish stack; deferred to the server.
- **`ComposerVector3f`** — only referenced by avatar/animation paths; deferred to **M7**.

## What "rebuilt" means for the reader

The three most consequential rebuilds are the **server** (GlassFish → Spring Boot 3),
the **module system** (NetBeans → `ServiceLoader`), and the **3D stack** (jME 1.x +
Java Web Start → an in-tree `com.jme` shim on `org.jogamp.*`). In each case the port
keeps the *wire contract and the observable behaviour* the original's own classes
exposed, and re-derives the plumbing that the unrecoverable library used to provide —
verified by the headless tests described in **[Milestones](MILESTONES.md)**, not by a
claim.

Back to the **[overview](README.md)**.
