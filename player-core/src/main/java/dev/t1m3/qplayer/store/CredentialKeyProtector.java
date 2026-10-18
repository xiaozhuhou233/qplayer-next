package dev.t1m3.qplayer.store;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Host-provided protection for QPlayer's random credential data key.
 *
 * <p>Implementations delegate to an OS credential facility (Android Keystore,
 * DPAPI, Keychain or Secret Service). The returned bytes are safe to persist;
 * recovering the original key must require the same OS user/application identity.
 */
public interface CredentialKeyProtector {

    /** Stable identifier written into the protected-key envelope. */
    String id();

    byte[] protect(byte[] key) throws IOException, GeneralSecurityException;

    /**
     * Explicit user-requested protection. Implementations that can display a
     * system unlock prompt may wait longer here; callers must run this off the
     * UI thread.
     */
    default byte[] protectInteractively(byte[] key)
            throws IOException, GeneralSecurityException {
        return protect(key);
    }

    byte[] unprotect(byte[] protectedKey) throws IOException, GeneralSecurityException;

    /**
     * Explicit user-requested retry. Implementations that can display a system
     * unlock prompt may wait longer here; the caller must run this off the UI thread.
     */
    default byte[] unprotectInteractively(byte[] protectedKey)
            throws IOException, GeneralSecurityException {
        return unprotect(protectedKey);
    }
}
