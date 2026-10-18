package dev.t1m3.qplayer.store;

/** Process-wide platform key protector installed by the Android/desktop host. */
public final class CredentialKeyProtection {

    private static volatile CredentialKeyProtector current;

    private CredentialKeyProtection() {
    }

    /**
     * Install the protector before constructing {@code PlayerController}. Reinstalling
     * the same provider is allowed for Android Activity recreation.
     */
    public static synchronized void install(CredentialKeyProtector protector) {
        if (protector == null) throw new IllegalArgumentException("protector");
        CredentialKeyProtector existing = current;
        if (existing != null && !existing.id().equals(protector.id())) {
            throw new IllegalStateException("credential key protector already installed: "
                    + existing.id());
        }
        current = protector;
    }

    public static CredentialKeyProtector current() {
        return current;
    }
}
