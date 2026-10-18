package dev.t1m3.qplayer.android.recognize;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 听歌识曲's fingerprint engine, behind a hidden {@link WebView} (round 37).
 *
 * <p>Why a WebView: the fingerprint is NetEase's own AFP algorithm, shipped as WebAssembly
 * ({@code afp.wasm}, wrapped by {@code afp.js}). Reimplementing it in Java is not realistic and there
 * is no Java port, so the same route the other working Android clients take is used here — load
 * {@code assets/afp/host.html} offline, hand it the samples, take the blob back. The two scripts are
 * the official helper's own; {@code host.html} is ours.
 *
 * <p>The contract with the page is one function: {@code extractFp(base64 of the little-endian
 * float32 samples)} → {@code AndroidAfp.result(base64 blob)}. The page cannot reach the network
 * ({@link WebSettings#setBlockNetworkLoads}) and has no JavaScript bridge except this class, so a
 * compromised asset cannot do more than answer.
 *
 * <p>⚠️ One WebView is created per recorder and kept for its lifetime: creating one costs ~100 ms and
 * instantiating the wasm a few hundred more, which is not something to pay per attempt.
 */
public final class AfpFingerprint {

    /** Long enough for a cold wasm instantiation on a slow device, short enough not to hang a
     *  listener who is standing in a shop. */
    private static final long TIMEOUT_MS = 12_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private volatile CountDownLatch waiting;
    private volatile String answer;
    private volatile String failure;

    /** Build the engine. <b>Must be called on the main thread</b> — a WebView has to be created
     *  there, and the recorder that owns this object is built from a coroutine on it. */
    public AfpFingerprint(Context context) {
        web = new WebView(context.getApplicationContext());
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setBlockNetworkLoads(true);
        settings.setAllowFileAccess(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // The page's own scripts have run by now, so `extractFp` exists.
            }
        });
        web.addJavascriptInterface(this, "AndroidAfp");
        web.loadUrl("file:///android_asset/afp/host.html");
    }

    /** The page's success callback. */
    @JavascriptInterface
    public void result(String fingerprint) {
        answer = fingerprint;
        CountDownLatch latch = waiting;
        if (latch != null) latch.countDown();
    }

    /** The page's failure callback. */
    @JavascriptInterface
    public void error(String why) {
        failure = why;
        CountDownLatch latch = waiting;
        if (latch != null) latch.countDown();
    }

    /**
     * The fingerprint of a 3 s window of 8 kHz mono samples — the base64 blob the match endpoint
     * wants. Blocking (the caller is the recogniser's own thread) and bounded by
     * {@link #TIMEOUT_MS}.
     *
     * @throws Exception when the engine could not answer, which the caller reports as a failed
     *                   attempt rather than as a failed feature
     */
    public String of(float[] pcm) throws Exception {
        if (pcm == null || pcm.length == 0) throw new IllegalArgumentException("no samples");
        byte[] raw = new byte[pcm.length * 4];
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        for (float sample : pcm) buffer.putFloat(sample);
        final String encoded = Base64.encodeToString(raw, Base64.NO_WRAP);
        answer = null;
        failure = null;
        final CountDownLatch latch = new CountDownLatch(1);
        waiting = latch;
        main.post(() -> {
            WebView view = web;
            if (view == null) {
                failure = "engine closed";
                latch.countDown();
                return;
            }
            view.evaluateJavascript("window.extractFp('" + encoded + "')", null);
        });
        if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("指纹引擎超时");
        }
        waiting = null;
        if (failure != null) throw new IllegalStateException("指纹引擎失败：" + failure);
        if (answer == null || answer.isEmpty()) throw new IllegalStateException("指纹为空");
        return answer;
    }

    /** Release the WebView. Safe to call more than once; must be called on the main thread. */
    public void close() {
        final WebView view = web;
        web = null;
        if (view == null) return;
        view.removeJavascriptInterface("AndroidAfp");
        view.destroy();
    }
}
