package dev.t1m3.qplayer.store;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class AccountTransferTest {
    private static char[] password() { return "test-only-口令-123".toCharArray(); }
    private static AccountTransfer.Accounts accounts() {
        return new AccountTransfer.Accounts("MUSIC_U=test-only-netease; __csrf=example",
                "SESSDATA=test-only-bili; DedeUserID=123; bili_jct=example");
    }

    @Test public void bothAccountsRoundTripAcrossPortablePasswordEncryption() throws Exception {
        AccountTransfer.Accounts original = accounts();
        byte[] file = AccountTransfer.encrypt(original, password());
        AccountTransfer.Accounts restored = AccountTransfer.decrypt(file, password());
        assertEquals(original.netease, restored.netease);
        assertEquals(original.bili, restored.bili);
        assertFalse(new String(file, java.nio.charset.StandardCharsets.ISO_8859_1).contains("test-only-netease"));
    }

    @Test public void repeatedExportsUseIndependentSaltAndNonce() throws Exception {
        assertFalse(Arrays.equals(AccountTransfer.encrypt(accounts(), password()),
                AccountTransfer.encrypt(accounts(), password())));
    }

    @Test(expected = GeneralSecurityException.class) public void wrongPasswordCannotReadAccounts() throws Exception {
        byte[] file = AccountTransfer.encrypt(accounts(), password());
        AccountTransfer.decrypt(file, "another-password".toCharArray());
    }

    @Test(expected = GeneralSecurityException.class) public void tamperingFailsAuthentication() throws Exception {
        byte[] file = AccountTransfer.encrypt(accounts(), password());
        file[file.length - 3] ^= 1;
        AccountTransfer.decrypt(file, password());
    }

    @Test public void missingPlatformStaysAbsent() throws Exception {
        AccountTransfer.Accounts onlyMusic = new AccountTransfer.Accounts("MUSIC_U=test-only", "");
        AccountTransfer.Accounts restored = AccountTransfer.decrypt(AccountTransfer.encrypt(onlyMusic, password()), password());
        assertEquals("", restored.bili);
    }

    @Test(expected = IllegalArgumentException.class) public void oversizedFilesRejectedBeforeDecryption() throws Exception {
        AccountTransfer.decrypt(new byte[AccountTransfer.MAX_FILE_BYTES + 1], password());
    }

    @Test(expected = IllegalArgumentException.class) public void invalidSessionsCannotBeExported() throws Exception {
        AccountTransfer.encrypt(new AccountTransfer.Accounts("MUSIC_U=", ""), password());
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsHeaderInjection() throws Exception {
        AccountTransfer.encrypt(new AccountTransfer.Accounts("MUSIC_U=test\r\nInjected=yes", ""), password());
    }
}
