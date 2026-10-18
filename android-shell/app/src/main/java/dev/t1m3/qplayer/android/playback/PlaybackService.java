package dev.t1m3.qplayer.android.playback;

import dev.t1m3.qplayer.android.app.QPlayerApplication;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.media.session.MediaButtonReceiver;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.util.Logger;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Foreground service that bridges {@link PlayerController} to the system media
 * session: lockscreen / notification / bluetooth transport controls, and — by
 * keeping the process foregrounded — uninterrupted background playback.
 *
 * <p>The controller is owned by {@link ComposeQPlayerActivity}; since both live in the
 * same process the activity hands it over via {@link #controller} and pokes this
 * service ({@link #ACTION_REFRESH}) through its
 * {@link PlayerController.PlaybackListener} whenever playback changes. Metadata is
 * read from {@link PlayerController#currentTrack()} (plain state, valid off the
 * render thread) rather than the UI Properties, which lag while backgrounded.
 */
public final class PlaybackService extends Service {

    public static final String ACTION_REFRESH = "dev.t1m3.qplayer.action.REFRESH";
    private static final String CHANNEL_ID = "qplayer.playback";
    /** Package-visible so {@link QPlayerApplication}'s crash handler can cancel
     *  this notification without needing a live Service instance. */
    public static final int NOTIF_ID = 1;

    /** Set by the activity before the service is started (same-process handoff). */
    private static volatile PlayerController controller;
    /** The activity's listener that boot-starts this service; restored on destroy so a
     *  later play re-bootstraps. While the service lives it registers itself instead, so
     *  refreshes run in-process (no startForegroundService — which is barred from the
     *  background on Android 12+, the bug that left controls stuck after a focus pause). */
    private static volatile PlayerController.PlaybackListener bootstrapListener;
    private static volatile PlaybackService instance;

    private MediaSessionCompat session;
    private final PlayerController.PlaybackListener selfListener = this::onControllerChanged;
    private ScheduledExecutorService checkpointWorker;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = this::refreshSafely;
    private boolean refreshPending;
    private boolean stopping;
    private ExecutorService artworkWorker;
    private PlaybackArtworkLoader<Bitmap> artworkLoader;
    private Track metadataTrack;
    private String metadataTitle, metadataArtist, metadataAlbum;
    private long metadataDuration = -1L;
    private Bitmap metadataArt;
    private AudioManager outputAudioManager;
    private boolean noisyReceiverRegistered;
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                pauseForDisconnectedOutput();
            }
        }
    };
    private final AudioDeviceCallback outputDeviceCallback = new AudioDeviceCallback() {
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            // Some ROMs omit AUDIO_BECOMING_NOISY on Bluetooth power-off. The
            // audio-device API also covers LE/hearing-aid outputs without asking
            // for Bluetooth scan/connect permissions or reacting to non-audio ACLs.
            for (AudioDeviceInfo device : removedDevices) {
                if (!device.isSink()) continue;
                // Ending a Bluetooth call can remove SCO while the headset's
                // music output remains connected. That is not a disconnect.
                if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        && hasBluetoothMediaOutput()) continue;
                switch (device.getType()) {
                    case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                    case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                    case AudioDeviceInfo.TYPE_HEARING_AID:
                    case AudioDeviceInfo.TYPE_BLE_HEADSET:
                    case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                    case AudioDeviceInfo.TYPE_BLE_BROADCAST:
                        pauseForDisconnectedOutput();
                        return;
                    default:
                        break;
                }
            }
        }
    };

    private boolean hasBluetoothMediaOutput() {
        if (outputAudioManager == null) return false;
        for (AudioDeviceInfo device : outputAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (device.getType()) {
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                case AudioDeviceInfo.TYPE_HEARING_AID:
                case AudioDeviceInfo.TYPE_BLE_HEADSET:
                case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                case AudioDeviceInfo.TYPE_BLE_BROADCAST:
                    return true;
                default:
                    break;
            }
        }
        return false;
    }

    private void pauseForDisconnectedOutput() {
        if (stopping) return;
        PlayerController c = controller;
        // Also clear pending focus auto-resume if focus already paused playback.
        // Repeated system signals must never toggle playback back on.
        if (c != null) {
            Logger.info("PlaybackService: audio output disconnected, pausing");
            c.mediaPauseForDisconnectedOutput();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        ContextCompat.registerReceiver(this, noisyReceiver,
                new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        noisyReceiverRegistered = true;
        outputAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (outputAudioManager != null) {
            outputAudioManager.registerAudioDeviceCallback(outputDeviceCallback, refreshHandler);
        }
        artworkWorker = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "notification-artwork");
            thread.setDaemon(true);
            return thread;
        });
        artworkLoader = new PlaybackArtworkLoader<>(artworkWorker,
                command -> refreshHandler.post(command), PlaybackService::decode, this::onControllerChanged);
        createChannel();
        session = new MediaSessionCompat(this, "qplayer");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() {
                PlayerController c = controller;
                if (c != null) c.mediaResume();
            }
            @Override public void onPause() {
                PlayerController c = controller;
                if (c != null) c.mediaPause();
            }
            @Override public void onSkipToNext() {
                PlayerController c = controller;
                if (c != null) c.next();
            }
            @Override public void onSkipToPrevious() {
                PlayerController c = controller;
                if (c != null) c.prev();
            }
            @Override public void onSeekTo(long pos) {
                PlayerController c = controller;
                if (c != null) c.mediaSeek(pos);
            }
            @Override public void onStop() {
                PlayerController c = controller;
                if (c != null) c.mediaPause();
                clearNotification();
                stopSelf();
            }
        });
        session.setActive(true);
        // Android does not call onDestroy() for a low-memory process kill. Keep the
        // queue/position checkpoint close to the live play head so a rare kill loses
        // at most a few seconds instead of restoring the beginning of the track.
        checkpointWorker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "playback-checkpoint");
            t.setDaemon(true);
            return t;
        });
        checkpointWorker.scheduleWithFixedDelay(() -> {
            PlayerController current = controller;
            if (current != null && current.isPlaying()) current.saveSessionState();
        }, 15L, 15L, TimeUnit.SECONDS);
        // Take over as the controller's listener so state changes refresh us directly,
        // in-process, instead of round-tripping through startForegroundService.
        PlayerController c = controller;
        if (c != null) c.setPlaybackListener(selfListener);
    }

    private void onControllerChanged() {
        // Several cover/state callbacks can belong to one switch. One refresh
        // after this display frame avoids repeating metadata/notification IPC.
        if (stopping || refreshPending) return;
        refreshPending = true;
        refreshHandler.postDelayed(refreshTask, 16L);
    }

    private void refreshSafely() {
        refreshPending = false;
        if (stopping) return;
        try {
            refresh();
        } catch (Throwable e) {
            Logger.error("PlaybackService: refresh failed: {}", e.toString());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Hardware / bluetooth media buttons routed through the session callback.
        MediaButtonReceiver.handleIntent(session, intent);
        PlayerController c = controller;
        if (c != null) c.setPlaybackListener(selfListener);
        refreshHandler.removeCallbacks(refreshTask);
        // The first foreground notification must be immediate; its artwork is
        // filled in later and must never hold up startForeground().
        refreshSafely();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        // Stop playback and release audio backend — called when the task is
        // swiped away (stopWithTask="true") or explicitly stopped.
        stopVisualRefreshes();
        if (noisyReceiverRegistered) {
            unregisterReceiver(noisyReceiver);
            noisyReceiverRegistered = false;
        }
        if (outputAudioManager != null) {
            outputAudioManager.unregisterAudioDeviceCallback(outputDeviceCallback);
            outputAudioManager = null;
        }
        if (instance == this) instance = null;
        if (checkpointWorker != null) {
            checkpointWorker.shutdownNow();
            checkpointWorker = null;
        }
        PlayerController c = controller;
        if (c != null) {
            // Capture position/queue/playMode before anything below pauses/tears
            // playback down — ComposeQPlayerActivity.onDestroy() skips PlayerController.
            // shutdown() while playing precisely so this Service can keep going in
            // the background, so this is the only save-on-real-exit this path gets.
            c.saveSessionState();
            if (c.isPlaying()) c.toggle();
            c.setPlaybackListener(bootstrapListener);
        }
        session.setActive(false);
        session.release();
        // Explicit stop + cancel rather than relying on "destroying a foreground
        // service auto-clears its notification" — that contract isn't reliable
        // across OEM ROMs (MIUI/HyperOS, ColorOS, EMUI task-cleanup) and doesn't
        // hold at all if this method never runs (see onTaskRemoved / the crash
        // handler in QPlayerApplication for the paths where it might not).
        clearNotification();
        super.onDestroy();
    }

    /** Connect a newly-created/recreated Activity to the already-running playback
     * service without letting the Activity steal the controller listener. */
    public static void attachController(PlayerController c,
                                        PlayerController.PlaybackListener bootstrap) {
        controller = c;
        bootstrapListener = bootstrap;
        PlaybackService live = instance;
        c.setPlaybackListener(live != null ? live.selfListener : bootstrap);
    }

    public static boolean isRunning() {
        return instance != null;
    }

    /** Swiping the task away from Recents calls this instead of a normal destroy
     *  on many OEM ROMs' aggressive task-cleanup, which doesn't reliably run
     *  onDestroy() afterward — stop the service outright here so the notification
     *  and foreground state never survive the task disappearing. */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        PlayerController c = controller;
        if (c != null) {
            // See onDestroy(): capture position/queue/playMode before pausing.
            c.saveSessionState();
            // Unregister before toggle(): toggle() runs on the controller's main
            // executor (a Handler.post on Android, i.e. deferred, not inline), so its
            // notifyPlayback() callback lands *after* clearNotification() below has
            // already run. Left wired to selfListener, that deferred callback calls
            // refresh() -> startForeground(), which resurrects the notification right
            // after it was cleared (the disappear-then-reappear bug). Left wired to
            // bootstrapListener it's worse — that calls startForegroundService(),
            // which can spin up a whole new service instance. Null makes the deferred
            // callback a no-op; the next ComposeQPlayerActivity launch rewires the listener.
            c.setPlaybackListener(null);
            if (c.isPlaying()) c.toggle();
        }
        clearNotification();
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    private void stopVisualRefreshes() {
        stopping = true;
        refreshPending = false;
        refreshHandler.removeCallbacks(refreshTask);
        if (artworkLoader != null) artworkLoader.close();
        if (artworkWorker != null) artworkWorker.shutdownNow();
    }

    private void clearNotification() {
        // A pending refresh or decode result must not resurrect a stopped
        // notification (including the gap before onDestroy()).
        stopVisualRefreshes();
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Throwable ignored) {
        }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_ID);
        } catch (Throwable ignored) {
        }
    }

    /** Rebuild metadata + playback state + notification from the controller. */
    private void refresh() {
        PlayerController c = controller;
        if (c == null) return;
        Track t = c.currentTrack();
        boolean playing = c.isPlaying();
        // c.position()/c.duration() read the live backend clock directly, which
        // is 0 until playAt() actually runs -- e.g. right after a session
        // restore (task-removed kill + relaunch), positionMs/durationMs are set
        // correctly but the backend itself hasn't been told to play anything yet
        // until the user's first tap. Same fallback already applied to
        // LyricCompositor's progress bar / lyric-column position and
        // PlayerController.applyLyrics's lyricIndex sync -- this is the fourth
        // (notification / lock-screen / Bluetooth media control progress) spot
        // reading that same clock, missed when the other three were fixed.
        Long durProp = c.durationMs.peek();
        // This also reads the backend clock after a real background pause. Using
        // positionMs while paused regressed the notification to the last render-
        // thread tick (the GL pump is suspended in background), even though the
        // backend and periodic checkpoint had the correct newer position.
        long pos = c.mediaSessionPosition();
        long dur = playing ? c.duration() : (durProp != null && durProp > 0 ? durProp : 0L);

        // Track bytes describe the current selection even before pump(). Never
        // fall back to the outgoing cover Property while this cover is loading.
        Bitmap art = artworkLoader.get(t != null ? t.coverBytes : null);
        String title = t != null ? nz(t.title) : "";
        String artist = t != null ? nz(t.artist) : "";
        String album = t != null ? nz(t.album) : "";
        if (metadataTrack != t || metadataDuration != dur || metadataArt != art
                || !Objects.equals(metadataTitle, title)
                || !Objects.equals(metadataArtist, artist)
                || !Objects.equals(metadataAlbum, album)) {
            MediaMetadataCompat.Builder mb = new MediaMetadataCompat.Builder();
            if (t != null) {
                mb.putString(MediaMetadataCompat.METADATA_KEY_TITLE, title);
                mb.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist);
                mb.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album);
                if (dur > 0) mb.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, dur);
                if (art != null) mb.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art);
            }
            session.setMetadata(mb.build());
            metadataTrack = t;
            metadataDuration = dur;
            metadataArt = art;
            metadataTitle = title;
            metadataArtist = artist;
            metadataAlbum = album;
        }

        session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackStateCompat.ACTION_SEEK_TO
                        | PlaybackStateCompat.ACTION_STOP)
                .setState(playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        pos, playing ? 1f : 0f)
                .build());

        // Extract Monet seed from cover for notification accent (progress bar tint).
        String seedHex = c.coverSeed.peek();
        int accentColor = parseHexColor(seedHex);

        Notification n = buildNotification(t, playing, art, accentColor);
        // Stay foreground for the whole session — including while paused — so we never
        // need to re-promote from the background (Android 12+ forbids starting a FGS
        // from the background, which previously left controls stuck after a pause).
        // The notification is dismissible (setOngoing not set) and STOP fully exits.
        startForeground(NOTIF_ID, n);
    }

    private Notification buildNotification(Track t, boolean playing, Bitmap art, int accentColor) {
        // Resolve by manifest class name so this Java service does not require
        // javac to load the Compose activity and its complete Kotlin/Lifecycle
        // type graph during mixed-source compilation.
        Intent open = new Intent()
                .setClassName(this, "dev.t1m3.qplayer.android.ui.ComposeQPlayerActivity")
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pf = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, open, pf);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(t != null ? nz(t.title) : "QPlayer")
                .setContentText(t != null ? nz(t.artist) : "")
                .setContentIntent(contentPi)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                // CATEGORY_TRANSPORT + an ongoing notification while playing is how OEM
                // "live media" surfaces (ColorOS 流体云 media capsule, MIUI/HyperOS, etc.)
                // recognise this as an active session to elevate. Paused -> not ongoing so
                // the notification stays swipe-dismissible (and STOP fully exits).
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setColor(accentColor)
                .setColorized(true);
        if (art != null) b.setLargeIcon(art);

        b.addAction(new NotificationCompat.Action(android.R.drawable.ic_media_previous, "prev",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this,
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)));
        b.addAction(new NotificationCompat.Action(
                playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                playing ? "pause" : "play",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this,
                        PlaybackStateCompat.ACTION_PLAY_PAUSE)));
        b.addAction(new NotificationCompat.Action(android.R.drawable.ic_media_next, "next",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this,
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT)));

        b.setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(session.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2)
                .setShowCancelButton(true)
                .setCancelButtonIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this,
                        PlaybackStateCompat.ACTION_STOP)));
        return b.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Playback",
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private static Bitmap decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            // Decode at notification size on the artwork worker. Passing the full
            // 1024px cover through MediaSession/Notification adds decode and IPC cost.
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 256) {
                options.inSampleSize *= 2;
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** Parse a #RRGGBB hex string to an int color (A=FF). Returns 0 on failure. */
    private static int parseHexColor(String hex) {
        if (hex == null || hex.length() != 7 || hex.charAt(0) != '#') return 0;
        try {
            return 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
