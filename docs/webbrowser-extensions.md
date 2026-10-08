# Web Browser extensions

The LG3D Web Browser (`org.jdesktop.lg3d.apps.webbrowser`, a JavaFX/WebKit
`WebView` bridged into Swing) exposes a small **extension SPI** so developers can
hook navigation, popups and page loads, and contribute toolbar buttons — the way
a popup blocker or a tracker blocker works.

Extensions are plain Java, discovered with the standard
[`java.util.ServiceLoader`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/ServiceLoader.html).
There is no custom class format, no scripting runtime and no build plugin: you
implement one interface, register it, and drop a jar in a folder.

---

## 1. The contract

Everything a developer touches lives in the AWT/JavaFX-free package
`org.jdesktop.lg3d.apps.webbrowser.ext`, so extensions are unit-testable
headlessly.

- **`BrowserExtension`** — the SPI. Every hook except `manifest()` has a
  sensible default, so you implement only what you need:
  - `ExtensionManifest manifest()` — identity + declared permissions (required).
  - `NavigationDecision onNavigate(NavigationRequest)` — ALLOW / BLOCK /
    REDIRECT(url).
  - `PopupDecision onPopup(PopupRequest)` — ALLOW / BLOCK.
  - `void onPageLoaded(PageContext)` — the content-script injection point.
  - `void onBrowserStarted(BrowserContext)` / `onBrowserStopping(BrowserContext)`
    — lifecycle.
  - `List<ToolbarContribution> toolbarContributions()` — extra toolbar buttons.
- **`ExtensionManifest`** — immutable `(id, name, version, description, author,
  Set<Permission>)`. The `id` is the stable key used for persisted state; keep it
  reverse-DNS-ish and never change it (e.g. `com.example.my-extension`).
- **`Permission`** — `NAVIGATE`, `POPUP`, `CONTENT_SCRIPT`, `DOWNLOADS`,
  `SETTINGS`, `TOOLBAR`. Declare only what you use; the manager shows the list to
  the user before enabling.
- **Value types** — `NavigationRequest`/`NavigationDecision`,
  `PopupRequest`/`PopupDecision`, `PageContext` (`getUrl`, `getTitle`,
  `executeScript(js)`), `BrowserContext` (`openTab`, `navigate`, `log`, gated by
  granted permissions), `ToolbarContribution`.

### Example: a popup blocker

```java
package com.example.blocker;

import java.util.Set;
import org.jdesktop.lg3d.apps.webbrowser.ext.*;

public final class MyPopupBlocker implements BrowserExtension {

    @Override
    public ExtensionManifest manifest() {
        return new ExtensionManifest(
                "com.example.my-popup-blocker", "My Popup Blocker", "1.0.0",
                "Blocks popups the user did not ask for.", "Example",
                Set.of(Permission.POPUP));
    }

    @Override
    public PopupDecision onPopup(PopupRequest request) {
        return request.isUserInitiated() ? PopupDecision.ALLOW : PopupDecision.BLOCK;
    }
}
```

### Example: an HTTPS upgrader (redirect)

```java
@Override
public ExtensionManifest manifest() {
    return new ExtensionManifest("com.example.https", "HTTPS Upgrade", "1.0.0",
            "Upgrades http to https.", "Example", Set.of(Permission.NAVIGATE));
}

@Override
public NavigationDecision onNavigate(NavigationRequest request) {
    String url = request.getUrl();
    if (url.startsWith("http://") && !url.contains("localhost")) {
        return NavigationDecision.redirect("https://" + url.substring("http://".length()));
    }
    return NavigationDecision.allow();
}
```

### Example: a content script

```java
@Override
public ExtensionManifest manifest() {
    return new ExtensionManifest("com.example.hide-ads", "Hide Ads", "1.0.0",
            "Hides common ad containers.", "Example",
            Set.of(Permission.CONTENT_SCRIPT));
}

@Override
public void onPageLoaded(PageContext page) {
    page.executeScript("document.querySelectorAll('.ad').forEach(e => e.remove());");
}
```

`executeScript` is a **silent no-op** unless the user granted
`CONTENT_SCRIPT`, so an over-reaching extension degrades gracefully instead of
running unapproved code.

---

## 2. Packaging & registration

Register the implementation with a `META-INF/services` file whose name is the
fully-qualified SPI interface:

```
META-INF/services/org.jdesktop.lg3d.apps.webbrowser.ext.BrowserExtension
```

with your implementation class name(s), one per line:

```
com.example.blocker.MyPopupBlocker
```

Build a jar containing your classes and that service file. Your jar must be
compiled against the extension API; mark that dependency `compileOnly` — the
browser provides the SPI at runtime, so nothing needs bundling.

### Getting the API jar

You do **not** need all of `lg3d-apps` to compile an extension. The SPI is
published on its own as a small, stable **`lg3d-webbrowser-ext-api-<version>.jar`**
(plus a `-sources.jar` for IDEs) that contains exactly
`org.jdesktop.lg3d.apps.webbrowser.ext` — the `BrowserExtension` interface and its
immutable value types — and **not** the browser's internal runtime
(`ExtensionRegistry` / `ExtensionBroker` / `ExtensionState`) nor the `ext.builtin`
reference extensions. Download it from the dedicated **`webbrowser-api-v<version>`**
GitHub Release (the repository's *Releases* page), then compile against it:

```gradle
// Gradle — provided by the browser at runtime, so never bundle it.
compileOnly files('libs/lg3d-webbrowser-ext-api-<version>.jar')
```

That jar is produced by two on-demand Gradle tasks:

```bash
./gradlew :lg3d-apps:webBrowserApiJar :lg3d-apps:webBrowserApiSourcesJar
# add -PreleaseVersion=<v> to stamp a specific version instead of the build's own
```

Maintainers publish it by running the **Release Web Browser API** workflow
(`.github/workflows/release-webbrowser-api.yml`, *Actions → Run workflow*,
optionally with a `version` input); it builds both jars and uploads them — with a
`SHA256SUMS.txt` — to the `webbrowser-api-v<version>` release. This is independent
of the desktop-bundle release (`release.yml`, the `v*` tag), so cutting one never
cuts the other.

---

## 3. Installing

Drop the jar into the extensions directory:

```
~/.lg3d/webbrowser/extensions/your-extension.jar
```

(The directory follows the same override as the rest of the profile: set the
system property `lg3d.webbrowser.dir` to relocate it; the extensions folder is
`<dir>/extensions`.) Then open the browser's **Extensions** manager and press
**Rescan** — or restart the browser.

The browser loads each jar in its own child `URLClassLoader` (parent = the
application loader), so a third-party extension can ship its own dependencies.

---

## 4. The manager, permissions & the security model

The **Extensions** toolbar button opens `ExtensionManagerDialog`, which lists
every discovered extension (name, version, source, permissions) and lets you:

- **Enable / disable** — the checkbox. Built-ins start enabled; a **third-party
  extension starts disabled with nothing granted**.
- **Approve** — enabling a third-party extension is the user's explicit consent:
  a dialog lists the permissions it requests before it is turned on. Enabling
  grants the declared permissions.
- **Edit permissions** — select a row and press **Permissions…** to grant/revoke
  individual permissions.
- **Rescan** and **Open extensions folder**.

Enable/grant state persists to `~/.lg3d/webbrowser/extensions.json` and is
restored on the next launch.

> **Honest security posture.** This is a **consent / UX boundary, not a JVM
> sandbox.** Extension code runs in the same JVM as the browser with the full
> privileges of the user — the JVM has no capability sandbox for in-process
> bytecode. The permission gate makes an extension *declare* what it needs and
> makes the user *approve* it, and the runtime enforces the gate on every hook
> (an extension cannot navigate, block popups, inject scripts or add toolbar
> buttons beyond its grants). It does **not** and cannot stop a malicious jar
> from doing arbitrary things once loaded. Only install extensions you trust.

Robustness is enforced regardless: every hook call is wrapped in `try/catch`, so
a throwing extension is logged and skipped — it can never break the browser or
the other extensions.

> **Blocks are observable.** When an enabled extension returns `BLOCK` from
> `onNavigate`, or the popup hook vetoes a window, the browser no longer swallows
> the decision: `FxBrowser` fires its `onNavigationBlocked(url)` /
> `onPopupBlocked(url)` listener callbacks, and the panel tallies them per host in
> a `SiteStats` counter. The user sees those counts — navigations blocked and
> popups blocked for the current site — in the address-bar **site-info popup**
> (click the security indicator). These are internal browser callbacks, **not**
> new SPI hooks: the extension contract above is unchanged, and an extension
> neither sees nor controls the counting.

---

## 5. Built-in reference extensions

Three first-party extensions ship in-tree, registered through the very same
`META-INF/services` mechanism, and double as working examples:

| Id | Permission(s) | Behaviour |
| --- | --- | --- |
| `lg3d.popup-blocker` | `POPUP` | Blocks popups that were not user-initiated. |
| `lg3d.tracker-blocker` | `NAVIGATE`, `CONTENT_SCRIPT` | Vetoes a small bundled tracker/ad host list and hides common ad containers. |
| `lg3d.https-upgrade` | `NAVIGATE` | Redirects `http://` to `https://`, leaving local hosts alone. |

They live in `org.jdesktop.lg3d.apps.webbrowser.ext.builtin`.

---

## 6. Threading

Hooks are invoked on the browser's threads: `onNavigate`, `onPopup` and
`onPageLoaded` run on the **JavaFX Application Thread** (where the `WebEngine`
lives); `onBrowserStarted`/`onBrowserStopping` and toolbar contributions are
realised on the **EDT**. Keep hooks short and non-blocking; do not touch Swing
from a JavaFX-thread hook or vice-versa.

---

## 7. Tests

The SPI and its runtime are covered by headless JUnit 5 tests under
`lg3d-apps/src/test/java/org/jdesktop/lg3d/apps/webbrowser/`:
`ExtensionManifestTest`, `ExtensionRegistryTest` (classpath + temp-dir jar
discovery, the permission gate, enable/grant persistence), `ExtensionBrokerTest`
(dispatch, exception isolation, permission enforcement, block/redirect
decisions, content-script gating, toolbar de-dup), `BrowserStoreExtensionsTest`
and the three built-in extension tests. None require a display, a JavaFX toolkit
or a network.
