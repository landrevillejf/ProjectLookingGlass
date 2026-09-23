package com.protonmail.landrevillejf.swingide.update.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.*;

class UpdateSignatureVerifierTest {
    
    private UpdateSignatureVerifier verifier;
    private PGPKeyManager keyManager;
    
    @BeforeEach
    void setUp() {
        keyManager = new PGPKeyManager();
        verifier = new UpdateSignatureVerifier(keyManager);
    }
    
    @Test
    void testSignatureVerifierInitialization() {
        assertThat(verifier).isNotNull();
        assertThat(keyManager).isNotNull();
    }
    
    @Test
    void testVerifySignatureWithNullFile() {
        assertThatThrownBy(() -> 
            verifier.verifySignature(null, Paths.get("/tmp/test.asc"), "TESTKEY"))
            .isInstanceOf(Exception.class);
    }
    
    @Test
    void testVerifySignatureWithNullSignaturePath() {
        assertThatThrownBy(() -> 
            verifier.verifySignature(Paths.get("/tmp/test.jar"), null, "TESTKEY"))
            .isInstanceOf(Exception.class);
    }
    
    @Test
    void testVerifySignatureWithNonexistentFile() {
        assertThatThrownBy(() ->
            verifier.verifySignature(
                Paths.get("/tmp/nonexistent.jar"),
                Paths.get("/tmp/nonexistent.asc"),
                "TESTKEY"
            ))
            .isInstanceOf(UpdateSecurityException.class);
    }
    
    @Test
    void testGetSignatureKeyIdWithNonexistentFile() {
        assertThatThrownBy(() ->
            verifier.getSignatureKeyId(Paths.get("/tmp/nonexistent.asc")))
            .isInstanceOf(UpdateSecurityException.class);
    }
    
    @Test
    void testVerifySignatureStrictWithExpiredKey() {
        assertThatThrownBy(() ->
            verifier.verifySignatureStrict(
                Paths.get("/tmp/test.jar"),
                Paths.get("/tmp/test.asc"),
                "NONEXISTENT",
                "ABCDEF"
            ))
            .isInstanceOf(UpdateSecurityException.class);
    }
    
    @Test
    void testSignatureVerifierWithMultipleFiles() {
        UpdateSignatureVerifier verifier2 = new UpdateSignatureVerifier(keyManager);
        assertThat(verifier).isNotSameAs(verifier2);
    }
}
