package com.protonmail.landrevillejf.swingide.update.security;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Enforces the OpenPGP detached signature of a downloaded update.
 * <p>
 * The gate is deliberately forgiving about <em>absence</em> and strict about
 * <em>mismatch</em>: when no key is bundled, no signature is published or the
 * metadata carries no signature URL, the outcome depends on the {@code required}
 * flag (a required signature that cannot be checked fails the update, an
 * optional one only logs a warning). Whenever a signature <em>is</em> present
 * and can be checked, an invalid one always fails, whatever the flag says.
 * </p>
 * <p>
 * The network fetch is injected through {@link SignatureDownloader} so the whole
 * verification chain can be exercised without touching the network or a real
 * keyring.
 * </p>
 */
@Slf4j
public class UpdateSignatureGate {

    /**
     * Fetches a remote resource into a local file.
     */
    @FunctionalInterface
    public interface SignatureDownloader {
        /**
         * @param url    resource to fetch
         * @param target file receiving the bytes
         * @return {@code true} when the resource was written to {@code target}
         */
        boolean download(String url, Path target);
    }

    private final PGPKeyManager keyManager;
    private final UpdateSignatureVerifier verifier;
    private final SignatureDownloader downloader;
    private final String keyResource;
    private final String defaultKeyId;
    private final String expectedFingerprint;
    private final boolean required;

    private volatile boolean keyUnavailable;

    /**
     * @param keyManager          keyring holding the trusted release keys
     * @param verifier            OpenPGP verifier running against the keyring
     * @param downloader          fetches the detached {@code .asc} signature
     * @param keyResource         classpath resource of the bundled public keyring
     * @param defaultKeyId        key id used when the metadata advertises none
     * @param expectedFingerprint optional fingerprint for strict key validation
     * @param required            whether a missing/unverifiable signature fails the update
     */
    public UpdateSignatureGate(PGPKeyManager keyManager,
                               UpdateSignatureVerifier verifier,
                               SignatureDownloader downloader,
                               String keyResource,
                               String defaultKeyId,
                               String expectedFingerprint,
                               boolean required) {
        this.keyManager = keyManager;
        this.verifier = verifier;
        this.downloader = downloader;
        this.keyResource = keyResource;
        this.defaultKeyId = defaultKeyId;
        this.expectedFingerprint = expectedFingerprint;
        this.required = required;
    }

    /**
     * Whether a signature that cannot be verified must fail the update.
     */
    public boolean isRequired() {
        return required;
    }

    /**
     * Verifies the detached signature of a downloaded update.
     *
     * @param jarFile       the downloaded update JAR
     * @param signatureUrl  URL of the detached {@code .asc} signature, may be blank
     * @param signingKeyId  key id advertised by the metadata, may be blank
     * @throws UpdateSecurityException when a required signature cannot be checked
     *                                 or when a present signature is invalid
     */
    public void verify(Path jarFile, String signatureUrl, String signingKeyId)
            throws UpdateSecurityException {

        String keyId = firstNonBlank(signingKeyId, defaultKeyId);

        if (signatureUrl == null || signatureUrl.isBlank()) {
            handleMissing("no signature URL published for this release");
            return;
        }

        if (keyId == null) {
            handleMissing("no signing key id configured or published");
            return;
        }

        if (!ensureKeyLoaded(keyId)) {
            handleMissing("the release public key is not bundled (" + keyResource + ")");
            return;
        }

        Path signatureFile = null;
        try {
            signatureFile = Files.createTempFile("lg3d-update-", ".asc");

            if (!downloader.download(signatureUrl, signatureFile)) {
                handleMissing("the signature could not be downloaded from " + signatureUrl);
                return;
            }

            if (expectedFingerprint != null && !expectedFingerprint.isBlank()) {
                verifier.verifySignatureStrict(jarFile, signatureFile, keyId, expectedFingerprint);
            } else {
                if (!verifier.verifySignature(jarFile, signatureFile, keyId)) {
                    throw new UpdateSecurityException(
                        "PGP signature verification failed for " + jarFile.getFileName()
                    );
                }
            }

            log.info("PGP signature verified for {} with key {}", jarFile.getFileName(), keyId);

        } catch (UpdateSecurityException e) {
            // A present-but-invalid signature always fails, optional or not.
            throw e;
        } catch (java.io.IOException | RuntimeException e) {
            throw new UpdateSecurityException("Signature verification could not be completed", e);
        } finally {
            deleteQuietly(signatureFile);
        }
    }

    private void handleMissing(String reason) throws UpdateSecurityException {
        if (required) {
            throw new UpdateSecurityException("Required signature is missing: " + reason);
        }
        log.warn("Skipping PGP signature verification: {}", reason);
    }

    /**
     * Loads the bundled keyring once. A key already present in the keyring (for
     * instance pre-loaded by the host or by a test) is reused as-is. A key that
     * cannot be loaded is remembered so a broken resource is not retried on every
     * update.
     */
    private boolean ensureKeyLoaded(String keyId) {
        if (keyManager.hasKey(keyId)) {
            return true;
        }
        if (keyUnavailable) {
            return false;
        }

        try (InputStream in = getClass().getClassLoader().getResourceAsStream(keyResource)) {
            if (in == null) {
                log.warn("Bundled update signing key '{}' not found on the classpath", keyResource);
                keyUnavailable = true;
                return false;
            }

            keyManager.loadPublicKey(in, keyId);
            return true;

        } catch (UpdateSecurityException | java.io.IOException | RuntimeException e) {
            log.warn("Could not load the bundled update signing key '{}': {}", keyResource, e.getMessage());
            keyUnavailable = true;
            return false;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (java.io.IOException | RuntimeException e) {
            log.debug("Could not delete the temporary signature file {}", file, e);
        }
    }
}
