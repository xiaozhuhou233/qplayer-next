package dev.t1m3.qplayer.audio.automix;

/** Adapter for the imported engine; per-block diagnostics stay off on phones. */
public final class AutoMixLog {
    private AutoMixLog() {}
    public static boolean verbose() { return false; }
    public static void debug(String message, Object... args) { }
    public static void info(String message, Object... args) {
        dev.t1m3.qplayer.util.Logger.info(message, args);
    }
    public static void warn(String message, Object... args) {
        dev.t1m3.qplayer.util.Logger.warn(message, args);
    }
}
