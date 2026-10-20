# TODO — Signed releases (end-to-end PGP signing of the lg3d update bundle)

Status tracker for turning the **already-implemented but dormant** release-signing
machinery into a live, enforced, end-to-end signed release.

The code paths exist on both sides; what is missing is **key material, secret
configuration, the bundled public key and flipping the client defaults on**. This
document lists exactly what remains, in the order it must be done.

---

## Progress (updated 2026-10-07)

- ✅ **§1** Key generated (RSA-4096 *Project Looking Glass Release Signing*,
  key-id `9A5DAD01CF4F5054`, no passphrase, no expiry); `RELEASE_SIGNING_KEY` +
  `RELEASE_SIGNING_KEY_ID` secrets set; sign→verify round-trip proven locally.
  Still open: `RELEASE_PAT` (train hand-off) — a release is cut via
  `make-release.sh` instead.
- ✅ **§2** `public-key.asc` committed; `update.signature.key.id` +
  `.fingerprint` set. `enabled` / `required` intentionally left `false`
  (safest first pass; flip on later).
- ✅ **§3** `UpdateVerifier` misleading PGP TODOs removed; `verifyWithSignature`
  marked `@Deprecated` with an honest notice.
- ✅ **§6** `docs/release-process.md` §13 + `update-manager/AGENTS.md` + CHANGELOG
  updated.
- ⏳ **§4** End-to-end validation against a *published* release — pending the next
  tag cut (the local round-trip already passed).
- ⏳ **§5** Key escrow/rotation — documented in §13.5; the offline private-key
  backup + revocation cert (`~/.gnupg/openpgp-revocs.d/`) are the operator's to
  store safely.


---

## 0. What already works (do not rebuild)

| Piece | Path | State |
| --- | --- | --- |
| CI signing + `version.json` | [`.github/workflows/release.yml`](.github/workflows/release.yml) | ✅ Implemented. Signs `lg3d-<v>.zip` → `.zip.asc` (armored detached), exports `public-key.asc`, and injects `signatureUrl` / `signingKeyId` into `version.json` **only when the secrets are set**. Unsigned otherwise (checksum-only). |
| Client signature gate | `update-manager/.../security/UpdateSignatureGate.java` | ✅ Implemented + unit-tested (`UpdateSignatureGateTest`). |
| Client PGP verifier | `update-manager/.../security/UpdateSignatureVerifier.java` | ✅ Implemented (BouncyCastle, armored detached, strict-fingerprint variant). |
| Keyring loader | `update-manager/.../security/PGPKeyManager.java` | ✅ Implemented (`loadPublicKey` from classpath resource). |
| Metadata parsing | `update-manager/.../UpdateRepository.java` | ✅ Parses `signatureUrl` + `signingKeyId`. |
| Bundle installer | `update-manager/.../BundleUpdateInstaller.java` | ✅ Applies the multi-jar zip (PR #268). |

**Conclusion:** no new signing/verification code is required. The remaining work is
configuration, key management, one committed public-key resource, one dead-code
cleanup and an end-to-end validation.

---

## 1. Server / CI side — key material & secrets

- [ ] **Generate a dedicated release-signing PGP key.** A sign-capable RSA key
      (e.g. 4096-bit) used *only* for release signing — never a personal key.
      Record its **long key-id** (16 hex chars) and **fingerprint**.
- [ ] **Set repository secret `RELEASE_SIGNING_KEY`** — the armored private key,
      base64-encoded on a single line:
      `gpg --armor --export-secret-keys <KEY_ID> | base64 -w0`
      (`gh secret set RELEASE_SIGNING_KEY`).
- [ ] **Set repository secret `RELEASE_SIGNING_PASSPHRASE`** — the key passphrase
      (`gh secret set RELEASE_SIGNING_PASSPHRASE`). If the key has no passphrase,
      leave it unset (the workflow handles the empty case).
- [ ] **Set repository secret `RELEASE_SIGNING_KEY_ID`** (optional but recommended)
      — pins the exact signing key instead of relying on `release.yml`'s
      `gpg --list-secret-keys` auto-detection (first `sec` entry).
- [ ] **Set repository secret `RELEASE_PAT`** (independent blocker for the *train*).
      Today it is **not** configured, so `release-train.yml` runs in degraded mode
      with `GITHUB_TOKEN`; a tag pushed by the train does **not** fire
      `release.yml`. Either add `RELEASE_PAT` (Contents + Pull requests, read/write,
      scoped to this repo) or keep cutting releases via the local
      [`make-release.sh`](make-release.sh) path, whose tag is pushed with real
      credentials and *does* trigger `release.yml`.
- [ ] **Never commit** the private key or passphrase. Only the *public* half is
      committed (see §2).

### Verify after the next tag
- [ ] `release.yml` run uploads **all five** assets:
      `lg3d-<v>.zip`, `lg3d-<v>.zip.asc`, `public-key.asc`, `version.json`,
      `changelog.md`.
- [ ] `version.json` contains non-empty `platforms.universal.signatureUrl` **and**
      `signingKeyId`.

---

## 2. Client side — bundle the public key & enable verification

The client currently **cannot** verify anything because the trusted public key is
not shipped and every signature flag defaults to off.

- [ ] **Commit the exported public key** to
      `update-manager/src/main/resources/public-key.asc`
      (`gpg --armor --export <KEY_ID> > update-manager/src/main/resources/public-key.asc`).
      This file **does not exist yet** (confirmed) and is what
      `update.signature.public.key.resource=public-key.asc` resolves to.
- [ ] **Set `update.signature.key.id`** in
      [`update-manager/src/main/resources/update-config.properties`](update-manager/src/main/resources/update-config.properties)
      to the same **uppercase hex long key-id** used in the metadata
      (currently empty). This is the fallback when `signingKeyId` is absent.
- [ ] **Set `update.signature.fingerprint`** to the key fingerprint to enable the
      **strict** verification path (`verifySignatureStrict`) — recommended
      (currently empty).
- [ ] **Flip `update.signature.enabled=true`** (currently `false`) so
      `UpdateService.verifySignature` actually runs the gate.
- [ ] **Decide `update.signature.required`**:
      - `true` → an update whose signature is missing/unverifiable is **rejected**
        (fail-closed; the right posture once signing is live and stable).
      - `false` (current) → a missing key/signature only logs a warning; a
        present-but-invalid signature **always** fails regardless.
      Ship `false` for the first signed release to avoid stranding older clients,
      then move to `true` once the signed pipeline is proven.
- [ ] Confirm the config change is picked up by the release bundle build (the
      resource is packaged into the `update-manager-*.jar` inside
      `:lg3d-core:releaseBundle`).

---

## 3. Code cleanup (dead / stale path)

- [ ] **`UpdateVerifier.verify()`** still carries
      `// TODO: Implement PGP signature verification — For now, we only verify
      checksum`. It is a **legacy** verifier: the live signature path is
      `UpdateSignatureGate` + `UpdateSignatureVerifier`. Decide and act:
      - either **remove** the misleading TODO / dead `verifyWithSignature` stub and
        document that `UpdateVerifier` is checksum-only by design, or
      - **delegate** it to the real gate so there is a single source of truth.
      (It is still referenced by `UpdateService` and `UpdateVerifierTest`, so do
      not delete blindly — reconcile first.)

---

## 4. End-to-end validation (before declaring "signed releases done")

- [ ] Cut a release (RC is enough) via `make-release.sh` or the train.
- [ ] Download the published `lg3d-<v>.zip`, `lg3d-<v>.zip.asc` and
      `public-key.asc`, then verify out-of-band:
      ```bash
      gpg --import public-key.asc
      gpg --verify lg3d-<v>.zip.asc lg3d-<v>.zip
      ```
      Expect a **Good signature** from the configured key-id/fingerprint.
- [ ] Verify the client path: with `update.signature.enabled=true` and the bundled
      `public-key.asc`, an install against a **tampered** zip must fail
      (`UpdateSecurityException`), and against the **genuine** zip must succeed.
- [ ] Confirm the SHA-256 in `version.json` still matches the signed zip
      (checksum + signature are complementary).

---

## 5. Key lifecycle & governance

- [ ] Document the **key rotation** procedure (new key → re-export `public-key.asc`
      → update `key.id` / `fingerprint` → ship a client release before rotating the
      signing secret, so clients never reject a valid new key).
- [ ] Set and track a key **expiry**; note `PGPKeyManager` key-validity handling so
      an expired key is surfaced clearly rather than silently failing verification.
- [ ] Store an **offline backup** of the private key + passphrase (escrow), and
      restrict who can read/rotate the `RELEASE_SIGNING_*` secrets.
- [ ] Consider protecting `main` / release workflows, since any run of
      `release.yml` can read the signing secrets.

---

## 6. Docs to update once done

- [ ] [`docs/release-process.md`](docs/release-process.md) — add a "Signed
      releases" section (secrets, public-key bundling, client enforcement).
- [ ] [`update-manager/AGENTS.md`](update-manager/AGENTS.md) — record that
      signature enforcement is live and the `public-key.asc` resource is shipped.
- [ ] `CHANGELOG.md` — a bullet under the release that first ships a signed bundle
      with client verification enabled.

---

## Minimal path to a first signed release

1. Generate the key; set `RELEASE_SIGNING_KEY`, `RELEASE_SIGNING_PASSPHRASE`,
   `RELEASE_SIGNING_KEY_ID` (§1).
2. Commit `update-manager/src/main/resources/public-key.asc` and set
   `update.signature.key.id` + `.fingerprint`; keep `enabled=false` /
   `required=false` for this first pass (§2).
3. Cut a release; confirm the five assets + `signatureUrl` / `signingKeyId` in
   `version.json` (§1 verify).
4. `gpg --verify` the downloaded triple (§4).
5. In a **follow-up** release, flip `update.signature.enabled=true` (then
   `required=true`) once the pipeline is proven, and clean up the legacy
   `UpdateVerifier` TODO (§3).
