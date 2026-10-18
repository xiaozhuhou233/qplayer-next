package dev.t1m3.qplayer.android.security;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import dev.t1m3.qplayer.store.CredentialKeyProtector;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Wraps QPlayer's data key with a non-exportable Android Keystore AES key. */
public final class AndroidKeystoreKeyProtector implements CredentialKeyProtector {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "dev.t1m3.qplayer.credentials.master.v1";
    private static final int TAG_BITS = 128;

    @Override
    public String id() {
        return "android-keystore";
    }

    @Override
    public byte[] protect(byte[] key) throws GeneralSecurityException, IOException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey());
        byte[] nonce = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(key);
        if (nonce == null || nonce.length == 0 || nonce.length > 255) {
            throw new GeneralSecurityException("Android Keystore returned an invalid nonce");
        }
        byte[] result = new byte[1 + nonce.length + ciphertext.length];
        result[0] = (byte) nonce.length;
        System.arraycopy(nonce, 0, result, 1, nonce.length);
        System.arraycopy(ciphertext, 0, result, 1 + nonce.length, ciphertext.length);
        return result;
    }

    @Override
    public byte[] unprotect(byte[] protectedKey)
            throws GeneralSecurityException, IOException {
        if (protectedKey == null || protectedKey.length < 1 + 12 + 16) {
            throw new IOException("Android Keystore key envelope is truncated");
        }
        int nonceLength = protectedKey[0] & 0xff;
        if (nonceLength == 0 || protectedKey.length < 1 + nonceLength + 16) {
            throw new IOException("Android Keystore key envelope is invalid");
        }
        byte[] nonce = new byte[nonceLength];
        System.arraycopy(protectedKey, 1, nonce, 0, nonceLength);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, loadExistingKey(),
                new GCMParameterSpec(TAG_BITS, nonce));
        return cipher.doFinal(protectedKey, 1 + nonceLength,
                protectedKey.length - 1 - nonceLength);
    }

    private static SecretKey loadOrCreateKey()
            throws GeneralSecurityException, IOException {
        KeyStore store = loadStore();
        java.security.Key existing = store.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static SecretKey loadExistingKey()
            throws GeneralSecurityException, IOException {
        java.security.Key key = loadStore().getKey(ALIAS, null);
        if (!(key instanceof SecretKey)) {
            throw new GeneralSecurityException("Android Keystore credential key is missing");
        }
        return (SecretKey) key;
    }

    private static KeyStore loadStore() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        return store;
    }
}
