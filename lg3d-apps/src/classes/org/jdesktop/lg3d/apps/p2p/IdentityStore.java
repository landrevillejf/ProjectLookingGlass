/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.p2p;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON persistence for a node's own long-term identity and its trust-on-first-use
 * (TOFU) record of peer identities. It mirrors the project's other stores
 * (notably {@code SshProfileStore}): a directory under {@code ~/.lg3d/p2p} by
 * default, overridable with the {@code lg3d.p2p.dir} system property (which tests
 * point at a temp folder), Jackson beans that tolerate unknown fields, and loads
 * that fall back to safe defaults rather than throwing.
 *
 * <p><strong>Our identity.</strong> {@link #loadOrCreateIdentity()} returns the
 * stored static X25519 keypair, generating and persisting one on first use. The
 * private key is written to {@value #IDENTITY_FILE} with owner-only {@code 0600}
 * permissions (like an SSH host key) and an atomic temp-then-rename, so a crash
 * never leaves a half-written key. This is honest at-rest key material: it is not
 * encrypted, so the file permissions and the OS user boundary are what protect it.
 * Nothing else in the store is secret - fingerprints are derived from public keys.</p>
 *
 * <p><strong>Peer pins.</strong> Pins are keyed by a stable caller-chosen
 * {@code peerKey} (an account id, a {@code host:port}, or the fingerprint itself for
 * a plain known-identity set) and hold the fingerprint we expect from that peer.
 * {@link #verifyAndRecord} implements TOFU: a first contact pins what was presented,
 * a later match refreshes it, and a mismatch is left untouched so the change stays
 * visible until the user explicitly calls {@link #overrideMismatch}. Detecting a
 * mismatch requires a {@code peerKey} that is stable across a peer changing keys
 * (a fingerprint-keyed pin can only ever be FIRST_CONTACT or MATCH).</p>
 *
 * <p>Instances cache the pin set in memory and persist on each mutation; two stores
 * over the same directory therefore see each other's writes on their next read.</p>
 */
public final class IdentityStore {

    private static final Logger LOG = LoggerFactory.getLogger(IdentityStore.class);

    /** System property overriding the store directory (used by tests). */
    public static final String DIR_PROPERTY = "lg3d.p2p.dir";

    static final String IDENTITY_FILE = "identity.json";
    static final String PINS_FILE = "pins.json";

    private final Path configDir;
    private final ObjectMapper mapper;
    private final Map<String, PinRecord> pins = new LinkedHashMap<>();
    private boolean pinsLoaded;

    /** Creates a store rooted at {@link #defaultConfigDir()}. */
    public IdentityStore() {
        this(defaultConfigDir());
    }

    /**
     * Creates a store rooted at an explicit directory.
     *
     * @param configDir where the JSON files live; created on first write
     */
    public IdentityStore(Path configDir) {
        this.configDir = (configDir == null) ? defaultConfigDir() : configDir;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Resolves the store directory.
     *
     * @return {@code lg3d.p2p.dir} when set, else {@code ~/.lg3d/p2p}
     */
    public static Path defaultConfigDir() {
        String override = System.getProperty(DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".lg3d", "p2p");
    }

    /** @return the directory this store reads and writes. */
    public Path getConfigDir() {
        return configDir;
    }

    // ------------------------------------------------------------------
    // Our identity
    // ------------------------------------------------------------------

    /**
     * Loads our long-term identity keypair, generating and persisting one (with
     * {@code 0600} permissions) if none exists or the stored one is corrupt.
     *
     * @return our static keypair, never null
     * @throws IllegalStateException if a new keypair cannot be generated
     */
    public synchronized KeyPair loadOrCreateIdentity() {
        IdentityBean bean = readIdentity();
        if (bean != null && bean.publicKey != null && bean.privateKey != null) {
            try {
                PublicKey publicKey = P2pCrypto.decodePublicKey(P2pCrypto.fromBase64(bean.publicKey));
                PrivateKey privateKey = P2pCrypto.decodePrivateKey(P2pCrypto.fromBase64(bean.privateKey));
                return new KeyPair(publicKey, privateKey);
            } catch (GeneralSecurityException | IllegalArgumentException ex) {
                LOG.warn("Stored identity in {} is corrupt; generating a new one", configDir, ex);
            }
        }
        try {
            KeyPair generated = P2pCrypto.generateKeyPair();
            writeIdentity(generated);
            return generated;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot generate a P2P identity keypair", ex);
        }
    }

    /**
     * Our identity fingerprint, generating the identity if necessary.
     *
     * @return the SHA-256 fingerprint of our static public key
     */
    public String getOurFingerprint() {
        try {
            return P2pCrypto.fingerprint(loadOrCreateIdentity().getPublic());
        } catch (GeneralSecurityException ex) {
            // SHA-256 is mandated by the platform, so this is unreachable in practice.
            throw new IllegalStateException("Cannot fingerprint our identity", ex);
        }
    }

    private IdentityBean readIdentity() {
        Path file = configDir.resolve(IDENTITY_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return mapper.readValue(file.toFile(), IdentityBean.class);
        } catch (IOException | RuntimeException ex) {
            LOG.warn("Could not read {}; a new identity will be generated", file, ex);
            return null;
        }
    }

    private void writeIdentity(KeyPair keyPair) {
        IdentityBean bean = new IdentityBean();
        bean.publicKey = P2pCrypto.toBase64(P2pCrypto.encodePublicKey(keyPair.getPublic()));
        bean.privateKey = P2pCrypto.toBase64(P2pCrypto.encodePrivateKey(keyPair.getPrivate()));
        try {
            bean.fingerprint = P2pCrypto.fingerprint(keyPair.getPublic());
        } catch (GeneralSecurityException ex) {
            bean.fingerprint = null;
        }
        bean.createdAtMs = System.currentTimeMillis();
        write(IDENTITY_FILE, bean, true);
    }

    // ------------------------------------------------------------------
    // Peer pins (TOFU)
    // ------------------------------------------------------------------

    /**
     * Verifies a presented fingerprint against the pin for {@code peerKey}, without
     * changing anything.
     *
     * @param peerKey              the stable peer identifier
     * @param presentedFingerprint the fingerprint the peer presented
     * @return the trust verdict
     */
    public synchronized TrustDecision verify(String peerKey, String presentedFingerprint) {
        ensureLoaded();
        PinRecord record = lookup(peerKey);
        return TrustDecision.evaluate(record == null ? null : record.fingerprint, presentedFingerprint);
    }

    /**
     * Verifies a presented fingerprint and applies TOFU: a first contact is pinned
     * now, a match refreshes the record, and a mismatch is left untouched (the pin
     * still points at the old fingerprint) so the change stays visible until the
     * user explicitly overrides it.
     *
     * @param peerKey              the stable peer identifier
     * @param presentedFingerprint the fingerprint the peer presented
     * @param nickname             a display name to record (may be null/blank)
     * @return the trust verdict that was applied
     */
    public synchronized TrustDecision verifyAndRecord(String peerKey, String presentedFingerprint,
                                                      String nickname) {
        TrustDecision decision = verify(peerKey, presentedFingerprint);
        long now = System.currentTimeMillis();
        switch (decision) {
            case FIRST_CONTACT -> upsert(peerKey, presentedFingerprint, nickname, now, false);
            case MATCH -> {
                PinRecord record = lookup(peerKey);
                if (record != null) {
                    record.lastSeenMs = now;
                    if (nickname != null && !nickname.isBlank()) {
                        record.nickname = nickname;
                    }
                    persistPins();
                }
            }
            case MISMATCH -> {
                // Deliberately do not overwrite the pin; see overrideMismatch.
            }
        }
        return decision;
    }

    /**
     * Records or updates the pin for {@code peerKey} (an explicit first-contact pin
     * or a re-pin), without marking it as an override.
     *
     * @param peerKey     the stable peer identifier
     * @param fingerprint the fingerprint to expect from this peer
     * @param nickname    a display name to record (may be null/blank)
     */
    public synchronized void pin(String peerKey, String fingerprint, String nickname) {
        ensureLoaded();
        upsert(peerKey, fingerprint, nickname, System.currentTimeMillis(), false);
    }

    /**
     * Explicitly trusts a peer whose presented fingerprint no longer matches its pin
     * (a user-confirmed MITM override): the pin is replaced with the new fingerprint
     * and flagged {@code overridden} so the UI can keep showing that this slot was
     * force-accepted.
     *
     * @param peerKey         the stable peer identifier
     * @param newFingerprint  the fingerprint to trust from now on
     * @param nickname        a display name to record (may be null/blank)
     */
    public synchronized void overrideMismatch(String peerKey, String newFingerprint, String nickname) {
        ensureLoaded();
        upsert(peerKey, newFingerprint, nickname, System.currentTimeMillis(), true);
    }

    /** Forgets a peer's pin, returning it to first-contact behaviour. */
    public synchronized void remove(String peerKey) {
        ensureLoaded();
        String key = normalizeKey(peerKey);
        if (key != null && pins.remove(key) != null) {
            persistPins();
        }
    }

    /** @return true if a pin exists for {@code peerKey}. */
    public synchronized boolean isPinned(String peerKey) {
        ensureLoaded();
        return lookup(peerKey) != null;
    }

    /** @return the pin for {@code peerKey}, or null. */
    public synchronized PinRecord getPin(String peerKey) {
        ensureLoaded();
        return lookup(peerKey);
    }

    /** @return the pinned fingerprint for {@code peerKey}, or null. */
    public synchronized String getPinnedFingerprint(String peerKey) {
        PinRecord record = getPin(peerKey);
        return (record == null) ? null : record.fingerprint;
    }

    /** @return a snapshot of every pin. */
    public synchronized List<PinRecord> getPins() {
        ensureLoaded();
        return new ArrayList<>(pins.values());
    }

    /** @return the number of pins. */
    public synchronized int getPinCount() {
        ensureLoaded();
        return pins.size();
    }

    // ------------------------------------------------------------------
    // Pin internals
    // ------------------------------------------------------------------

    private PinRecord lookup(String peerKey) {
        String key = normalizeKey(peerKey);
        return (key == null) ? null : pins.get(key);
    }

    private void upsert(String peerKey, String fingerprint, String nickname, long now,
                        boolean overridden) {
        String key = normalizeKey(peerKey);
        if (key == null || fingerprint == null || fingerprint.isBlank()) {
            return; // nothing stable to key on, or nothing to pin
        }
        PinRecord record = pins.get(key);
        if (record == null) {
            record = new PinRecord();
            record.peerKey = peerKey.trim();
            record.firstSeenMs = now;
            pins.put(key, record);
        }
        record.fingerprint = fingerprint;
        if (nickname != null && !nickname.isBlank()) {
            record.nickname = nickname;
        }
        record.lastSeenMs = now;
        record.overridden = overridden || record.overridden;
        persistPins();
    }

    private void ensureLoaded() {
        if (pinsLoaded) {
            return;
        }
        for (PinRecord record : readPins()) {
            String key = normalizeKey(record.peerKey);
            if (key != null) {
                pins.put(key, record);
            }
        }
        pinsLoaded = true;
    }

    private List<PinRecord> readPins() {
        Path file = configDir.resolve(PINS_FILE);
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            List<PinRecord> list = mapper.readValue(file.toFile(), new TypeReference<List<PinRecord>>() {
            });
            return (list == null) ? new ArrayList<>() : list;
        } catch (IOException | RuntimeException ex) {
            LOG.warn("Could not read {}; starting with no pins", file, ex);
            return new ArrayList<>();
        }
    }

    private void persistPins() {
        write(PINS_FILE, new ArrayList<>(pins.values()), false);
    }

    private static String normalizeKey(String peerKey) {
        if (peerKey == null) {
            return null;
        }
        String trimmed = peerKey.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ------------------------------------------------------------------
    // File I/O
    // ------------------------------------------------------------------

    private void write(String fileName, Object value, boolean restrict) {
        Path file = configDir.resolve(fileName);
        try {
            Files.createDirectories(configDir);
            Path tmp = file.resolveSibling(fileName + ".tmp");
            mapper.writeValue(tmp.toFile(), value);
            if (restrict) {
                restrictToOwner(tmp);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            if (restrict) {
                restrictToOwner(file);
            }
        } catch (IOException ex) {
            LOG.error("Could not write {}", fileName, ex);
        }
    }

    private static void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ex) {
            // A non-POSIX filesystem (e.g. Windows) cannot carry 0600; best effort.
            LOG.debug("Could not restrict permissions on {}", path);
        }
    }

    // ------------------------------------------------------------------
    // Persisted beans
    // ------------------------------------------------------------------

    /** Our long-term identity, persisted as base64 DER. The private key is secret. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IdentityBean {
        public String publicKey;
        public String privateKey;
        public String fingerprint;
        public long createdAtMs;
    }

    /**
     * A TOFU pin: the fingerprint we expect from a stable {@code peerKey}, plus the
     * display name and the first/last time we saw it. {@code overridden} records
     * that the user force-accepted a fingerprint change for this slot.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PinRecord {
        public String peerKey;
        public String fingerprint;
        public String nickname;
        public long firstSeenMs;
        public long lastSeenMs;
        public boolean overridden;

        /** No-arg constructor for Jackson. */
        public PinRecord() {
        }

        public String getPeerKey() {
            return peerKey;
        }

        public String getFingerprint() {
            return fingerprint;
        }

        public String getNickname() {
            return nickname;
        }

        public long getFirstSeenMs() {
            return firstSeenMs;
        }

        public long getLastSeenMs() {
            return lastSeenMs;
        }

        public boolean isOverridden() {
            return overridden;
        }

        @Override
        public String toString() {
            String name = (nickname == null || nickname.isBlank()) ? "peer" : nickname;
            return "PinRecord[" + name + " " + peerKey + " -> " + fingerprint
                    + (overridden ? " overridden" : "") + "]";
        }
    }
}
