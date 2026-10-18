package dev.t1m3.qplayer.store;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Portable, password-protected transfer; device Keystore keys never leave the app. */
public final class AccountTransfer {
    public static final int MAX_FILE_BYTES = 262144;
    private static final byte[] HEADER = "QPLAYER-ACCOUNTS-1\n".getBytes(StandardCharsets.US_ASCII);
    private static final int ITERATIONS = 210000;
    private static final int SALT_BYTES = 16, IV_BYTES = 12;
    private AccountTransfer() {}

    public static final class Accounts {
        public final String netease, bili;
        public Accounts(String netease, String bili) {
            this.netease = netease == null ? "" : netease;
            this.bili = bili == null ? "" : bili;
        }
    }

    public static byte[] encrypt(Accounts accounts, char[] password) throws GeneralSecurityException {
        requirePassword(password);
        validate(accounts);
        JsonObject object = new JsonObject();
        object.addProperty("format", 1);
        object.addProperty("netease", accounts.netease);
        object.addProperty("bili", accounts.bili);
        byte[] plain = object.toString().getBytes(StandardCharsets.UTF_8);
        byte[] salt = new byte[SALT_BYTES], iv = new byte[IV_BYTES];
        new SecureRandom().nextBytes(salt);
        new SecureRandom().nextBytes(iv);
        byte[] key = derive(password, salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(HEADER);
            byte[] encrypted = cipher.doFinal(plain);
            return ByteBuffer.allocate(HEADER.length + salt.length + iv.length + encrypted.length)
                    .put(HEADER).put(salt).put(iv).put(encrypted).array();
        } finally { Arrays.fill(plain, (byte) 0); Arrays.fill(key, (byte) 0); }
    }

    public static Accounts decrypt(byte[] bytes, char[] password) throws GeneralSecurityException {
        requirePassword(password);
        int prefix = HEADER.length + SALT_BYTES + IV_BYTES;
        if (bytes == null || bytes.length <= prefix + 16 || bytes.length > MAX_FILE_BYTES)
            throw new IllegalArgumentException("不是有效的 QPlayer 账号迁移文件");
        for (int i = 0; i < HEADER.length; i++) if (bytes[i] != HEADER[i])
            throw new IllegalArgumentException("不支持该账号迁移文件版本");
        byte[] salt = Arrays.copyOfRange(bytes, HEADER.length, HEADER.length + SALT_BYTES);
        byte[] iv = Arrays.copyOfRange(bytes, HEADER.length + SALT_BYTES, prefix);
        byte[] key = derive(password, salt);
        byte[] plain = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(HEADER);
            plain = cipher.doFinal(bytes, prefix, bytes.length - prefix);
            JsonObject object;
            try { object = JsonParser.parseString(new String(plain, StandardCharsets.UTF_8)).getAsJsonObject(); }
            catch (RuntimeException e) { throw new IllegalArgumentException("迁移文件内容无效"); }
            Accounts result;
            try {
                if (object.get("format").getAsInt() != 1) throw new IllegalArgumentException();
                result = new Accounts(object.get("netease").getAsString(), object.get("bili").getAsString());
            } catch (RuntimeException e) { throw new IllegalArgumentException("迁移文件内容无效"); }
            validate(result);
            return result;
        } finally {
            Arrays.fill(key, (byte) 0);
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    private static byte[] derive(char[] password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }

    private static void requirePassword(char[] password) {
        if (password == null || password.length < 8 || password.length > 256)
            throw new IllegalArgumentException("迁移口令需要 8–256 个字符");
    }

    private static void validate(Accounts accounts) {
        if (accounts == null || (accounts.netease.isEmpty() && accounts.bili.isEmpty()))
            throw new IllegalArgumentException("当前没有可迁移的登录账号");
        for (String header : new String[] { accounts.netease, accounts.bili }) {
            if (header.length() > 32768 || header.indexOf('\n') >= 0 || header.indexOf('\r') >= 0 || header.indexOf('\0') >= 0)
                throw new IllegalArgumentException("迁移登录信息格式无效");
        }
        if (!accounts.netease.isEmpty() && !hasCookie(accounts.netease, "MUSIC_U"))
            throw new IllegalArgumentException("迁移文件没有有效的网易云登录信息");
        if (!accounts.bili.isEmpty() && !hasCookie(accounts.bili, "SESSDATA") && !hasCookie(accounts.bili, "access_token"))
            throw new IllegalArgumentException("迁移文件没有有效的 B 站登录信息");
    }

    private static boolean hasCookie(String header, String name) {
        for (String cookie : header.split(";")) {
            int split = cookie.indexOf('=');
            if (split > 0 && name.equals(cookie.substring(0, split).trim()) &&
                    !cookie.substring(split + 1).trim().isEmpty()) return true;
        }
        return false;
    }
}
