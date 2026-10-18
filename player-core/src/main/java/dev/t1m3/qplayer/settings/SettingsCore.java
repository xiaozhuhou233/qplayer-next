package dev.t1m3.qplayer.settings;

import io.github.timer_err.qml4j.engine.QObject;
import io.github.timer_err.qml4j.engine.binding.Property;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.customapi.CustomApiConfig;
import dev.t1m3.qplayer.lyric.skia.Fonts;
import dev.t1m3.qplayer.lyric.skia.LyricCompositor;
import dev.t1m3.qplayer.lyric.skia.LyricConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The {@code settings} context global: one value store, one set of side effects,
 * one UI, shared by both hosts.
 *
 * <p>The settings page reads {@link #categories()} and {@link #rows(String)} and
 * renders whatever comes back — there is no hand-written row per setting on
 * either side. QML reads a value with {@link #value(String)} inside a binding,
 * which registers the underlying {@link Property} as a dependency (Property.get
 * records the read into the evaluating binding), so generated rows are as
 * reactive as hand-written ones were.
 *
 * <p>What each host still owns: a {@link SettingsStore} (JSON file vs
 * SharedPreferences), the platform id (so desktop-only rows don't show up on
 * Android), defaults that depend on the environment ({@link #setDefault}),
 * actions and live text the page can invoke/show ({@link #registerAction},
 * {@link #registerInfo}), and any extra reaction to a change
 * ({@link #onChange}). Everything else — persistence, clamping, the lyric/font/
 * player-controller wiring — happens here.
 */
public final class SettingsCore extends QObject implements LyricCompositor.SettingsBridge {

    /** Resolved dark flag (mode + system state), read by Main.qml's StyleManager
     *  binding. Derived, never persisted. */
    public final Property<Boolean> resolvedDark = new Property<>(Boolean.FALSE);
    /** Safe-area insets in logical px; Android publishes system-bar/cutout values,
     *  desktop leaves the side and bottom values at 0. */
    public final Property<Double> topInset = new Property<>(0.0);
    public final Property<Double> bottomInset = new Property<>(0.0);
    public final Property<Double> leftInset = new Property<>(0.0);
    public final Property<Double> rightInset = new Property<>(0.0);
    /** Installed font families for the picker dialog. Not persisted. */
    public final Property<List<String>> availableFontFamilies =
            new Property<>(Collections.emptyList());
    /** Desktop sets this when a requested Vulkan backend failed and startup
     *  continued with OpenGL. Non-persistent; Main.qml presents the explanation. */
    public final Property<Boolean> graphicsFallbackNotice = new Property<>(Boolean.FALSE);

    private final Map<String, SettingSpec> specsByKey = new LinkedHashMap<>();
    private final Map<String, Property<Object>> values = new HashMap<>();
    private final Map<String, List<Consumer<Object>>> hooks = new HashMap<>();
    private final Map<String, Object> defaultOverrides = new HashMap<>();
    private final Map<String, Runnable> actions = new HashMap<>();
    private final Map<String, Supplier<String>> infos = new HashMap<>();

    private List<SettingSpec> specs = Collections.emptyList();
    private SettingsStore store;
    private String platform = SettingSpec.ANY;
    private PlayerController controller;
    private volatile DirectoryPicker directoryPicker;
    /** Plain thread-safe mirror for non-QML consumers such as desktop lyrics. */
    private volatile boolean resolvedDarkSnapshot;
    private boolean systemDark;
    private boolean loaded;
    private boolean nativeFontRendererEnabled = true;

    // ---- host setup ---------------------------------------------------------

    /** Compose hosts render fonts themselves and must never initialize Skija. */
    public void setNativeFontRendererEnabled(boolean enabled) {
        if (loaded) throw new IllegalStateException("Configure font renderer before load");
        nativeFontRendererEnabled = enabled;
    }

    /** Override a default that only the host knows (a home-relative music folder,
     *  the platform cache directory). Call before {@link #load}. */
    public void setDefault(String key, Object value) {
        defaultOverrides.put(key, value);
    }

    /** Wire the settings that drive playback. Call before {@link #load} so the
     *  controller sees the persisted values as they're seeded. */
    public void attach(PlayerController controller) {
        this.controller = controller;
    }

    /** A button row's handler ({@link SettingSpec#action}). */
    public void registerAction(String id, Runnable r) {
        actions.put(id, r);
    }

    /** Live text for an info row or an action row's subtitle
     *  ({@link SettingSpec#provider}). */
    public void registerInfo(String id, Supplier<String> s) {
        infos.put(id, s);
    }

    /** Extra reaction to a value change, on top of the built-in effects. Fires
     *  after the new value is stored and persisted. */
    public void onChange(String key, Consumer<Object> handler) {
        hooks.computeIfAbsent(key, k -> new ArrayList<>()).add(handler);
    }

    /** Host hook for a platform directory chooser. The host must invoke the
     *  supplied callback on the QML/render thread. Desktop installs this after
     *  its window has been initialized; Android currently has no directory rows. */
    @FunctionalInterface
    public interface DirectoryPicker {
        void pick(String initialPath, Consumer<String> onPicked);
    }

    public void setDirectoryPicker(DirectoryPicker picker) {
        this.directoryPicker = picker;
    }

    /**
     * Seed every value from {@code store} and start persisting writes.
     *
     * @param platform {@link SettingsCatalog#DESKTOP} or
     *                 {@link SettingsCatalog#ANDROID} — filters the row list.
     */
    public void load(SettingsStore store, String platform) {
        this.store = store;
        this.platform = platform;
        migrateLegacyKeys();
        this.specs = new ArrayList<>();
        for (SettingSpec s : SettingsCatalog.specs()) {
            if (!s.appliesTo(platform)) continue;
            specs.add(s);
            specsByKey.put(s.key, s);
            if (s.hasValue()) values.put(s.key, new Property<>(read(s)));
        }
        specs = Collections.unmodifiableList(specs);
        // The Compose Android shell does not ship the legacy Skija renderer.
        // Font selection is optional there, so a missing native FontMgr must not
        // prevent the rest of the settings and the application from starting.
        availableFontFamilies.set(nativeFontRendererEnabled ? sortedFontFamilies() : Collections.emptyList());
        registerFontProviders();
        loaded = true;
        applyAll();
    }

    // ---- QML API ------------------------------------------------------------

    /** Tab names, in order. */
    public List<String> categories() {
        return SettingsCatalog.CATEGORIES;
    }

    /** The rows of one category, in declaration order. */
    public List<SettingSpec> rows(String category) {
        List<SettingSpec> out = new ArrayList<>();
        for (SettingSpec s : specs) {
            if (!s.hidden && s.category.equals(category)) out.add(s);
        }
        return out;
    }

    /** The cards of one category: consecutive rows sharing a group id, in
     *  declaration order. The page renders one card per group. */
    public List<SettingGroup> groups(String category) {
        List<SettingGroup> out = new ArrayList<>();
        List<SettingSpec> current = null;
        String currentId = null;
        for (SettingSpec s : rows(category)) {
            if (current == null || !s.group.equals(currentId)) {
                current = new ArrayList<>();
                currentId = s.group;
                out.add(new SettingGroup(currentId, current));
            }
            current.add(s);
        }
        return out;
    }

    /** Current value, as a reactive read: called inside a QML binding this
     *  registers the backing Property, so the binding re-evaluates on change. */
    public Object value(String key) {
        Property<Object> p = values.get(key);
        return p != null ? p.get() : null;
    }

    /** Whether this build has the setting at all (desktop-only rows on Android). */
    public boolean has(String key) {
        return values.containsKey(key);
    }

    /** Write from the UI: normalizes (QML hands integers over as Long), clamps a
     *  numeric control to its declared range, persists, then applies. */
    public void setValue(String key, Object raw) {
        SettingSpec spec = specsByKey.get(key);
        Property<Object> p = values.get(key);
        if (spec == null || p == null) return;
        Object v = normalize(spec, raw);
        if (v == null) return;
        // An explicit duration edit selects seconds rather than the automatic
        // style/beat-count window, even if the slider commits the same value.
        // Update the pair before pushing it to the controller so no render can
        // start with the new seconds and the old automatic-window policy.
        boolean pinWindow = "autoMixWindow".equals(key) && !bool("autoMixForceWindow");
        if (v.equals(p.peek()) && !pinWindow) return;
        if (pinWindow) {
            values.get("autoMixForceWindow").set(Boolean.TRUE);
            persist(specsByKey.get("autoMixForceWindow"), Boolean.TRUE);
        }
        if ("autoMixTuning".equals(key)) {
            try { dev.t1m3.qplayer.audio.mix.MixTuning.parse(String.valueOf(v)); }
            catch (dev.t1m3.qplayer.audio.mix.TuningException ex) {
                if (controller != null) controller.toast.set("AutoMix 参数无效：" + ex.getMessage());
                return;
            }
        }
        if ("aiProvider".equals(key)) {
            saveAiProviderFields(((Number) p.peek()).intValue());
        }
        p.set(v);
        persist(spec, v);
        apply(spec, v);
        if (pinWindow) fireHooks("autoMixForceWindow", Boolean.TRUE);
        // Design switches are exclusive even when changed through a non-Compose host.
        // Claude uses a lightweight paper theme and may coexist with low-spec rendering.
        if (Boolean.TRUE.equals(v)) {
            if ("claudeDesign".equals(key)) {
                setValue("iosDesign", false);
                setValue("sonicDesign", false);
            }
            if ("sonicDesign".equals(key)) {
                setValue("claudeDesign", false);
                setValue("iosDesign", false);
            }
            if ("iosDesign".equals(key)) {
                setValue("claudeDesign", false);
                setValue("sonicDesign", false);
                setValue(SettingsCatalog.LOW_SPEC_MODE_KEY, false);
            }
            if (SettingsCatalog.LOW_SPEC_MODE_KEY.equals(key)) setValue("iosDesign", false);
        }
        if ("aiProvider".equals(key)) applyAiProvider(((Number) v).intValue());
    }

    /** Keep provider-specific connection fields usable without replacing the user's API key. */
    private void applyAiProvider(int provider) {
        String url = "https://api.openai.com/v1";
        String model = "gpt-5.6-luna";
        if (provider == 1) { url = "https://api.deepseek.com/v1"; model = "deepseek-flash"; }
        else if (provider == 3) { url = "https://generativelanguage.googleapis.com/v1beta"; model = "gemini-3.5-flash-lite"; }
        String savedUrl = str("aiProviderUrl_" + provider);
        String savedKey = str("aiProviderKey_" + provider);
        String savedModel = str("aiProviderModel_" + provider);
        setValueSilently("aiBaseUrl", savedUrl.isEmpty() ? url : savedUrl);
        setValueSilently("aiApiKey", savedKey);
        setValueSilently("aiModel", savedModel.isEmpty() ? model : savedModel);
    }

    private void saveAiProviderFields(int provider) {
        setValueSilently("aiProviderUrl_" + provider, str("aiBaseUrl"));
        setValueSilently("aiProviderKey_" + provider, str("aiApiKey"));
        setValueSilently("aiProviderModel_" + provider, str("aiModel"));
    }

    private void setValueSilently(String key, String value) {
        SettingSpec spec = specsByKey.get(key); Property<Object> p = values.get(key);
        if (spec == null || p == null || value.equals(p.peek())) return;
        p.set(value); persist(spec, value); apply(spec, value);
    }

    /** Stepper -/+ , clamped. Keeps the arithmetic out of every generated row. */
    public void bump(String key, int direction) {
        SettingSpec spec = specsByKey.get(key);
        if (spec == null || !SettingSpec.STEPPER.equals(spec.type)) return;
        setValue(key, intOf(key) + direction * spec.step);
    }

    /** Text shown by an info row / an action row's subtitle. */
    public String info(String provider) {
        Supplier<String> s = infos.get(provider);
        if (s == null) return "";
        String v = s.get();
        return v != null ? v : "";
    }

    /** Run an action row's handler. */
    public void invoke(String action) {
        Runnable r = actions.get(action);
        if (r != null) r.run();
    }

    /** Open the host directory chooser for a PATH setting. Cancelling leaves the
     *  current value untouched. */
    public void pickDirectory(String key) {
        SettingSpec spec = specsByKey.get(key);
        DirectoryPicker picker = directoryPicker;
        if (picker == null || spec == null || !SettingSpec.PATH.equals(spec.type)) return;
        picker.pick(str(key), selected -> {
            if (selected == null || selected.trim().isEmpty()) return;
            setValue(key, selected);
        });
    }

    // ---- Java API -----------------------------------------------------------

    public boolean bool(String key) {
        return Boolean.TRUE.equals(peek(key));
    }

    public int intOf(String key) {
        Object v = peek(key);
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    public String str(String key) {
        Object v = peek(key);
        return v instanceof String ? (String) v : "";
    }

    /**
     * The curve a 「淡化曲线」 index names: the row's options are, in order,
     * {@code FadeCurve.LINEAR}, {@code EQUAL_POWER} and {@code DJ_BLEND} — taken from
     * the enum rather than hard-coded per index, so a fourth shape added to the enum
     * cannot silently become "linear" here.
     */
    static dev.t1m3.qplayer.audio.FadeCurve curveOf(int index) {
        dev.t1m3.qplayer.audio.FadeCurve[] curves = dev.t1m3.qplayer.audio.FadeCurve.values();
        if (index < 0 || index >= curves.length) return dev.t1m3.qplayer.audio.FadeCurve.DJ_BLEND;
        return curves[index];
    }

    /** Host-side write (not from QML) — same path, so effects still run. */
    public void put(String key, Object value) {
        setValue(key, value);
    }

    private Object peek(String key) {
        Property<Object> p = values.get(key);
        return p != null ? p.peek() : null;
    }

    // ---- dark mode ----------------------------------------------------------

    /** Safe-area insets in logical px (render thread). */
    public void setInsets(double left, double top, double right, double bottom) {
        leftInset.set(left);
        topInset.set(top);
        rightInset.set(right);
        bottomInset.set(bottom);
    }

    /** Desktop only reserves a custom title bar at the top. */
    public void setInsets(double top, double bottom) {
        setInsets(0, top, 0, bottom);
    }

    /** The OS's current dark state; hosts that can observe it live call this on
     *  every change, the others once at startup. */
    public void setSystemDark(boolean dark) {
        if (systemDark == dark && loaded) return;
        systemDark = dark;
        recomputeDark();
    }

    public boolean resolvedDarkValue() {
        return resolvedDarkSnapshot;
    }

    private void recomputeDark() {
        int mode = intOf("darkMode");
        boolean dark = mode == SettingsCatalog.MODE_DARK
                || (mode == SettingsCatalog.MODE_SYSTEM && systemDark);
        resolvedDarkSnapshot = dark;
        resolvedDark.set(dark);
    }

    // ---- LyricCompositor.SettingsBridge -------------------------------------

    @Override
    public float topInset() {
        Double v = topInset.peek();
        return v != null ? v.floatValue() : 0f;
    }

    @Override
    public boolean lyricBgStatic() {
        return intOf(SettingsCatalog.BG_MODE_KEY) == 1;
    }

    @Override
    public int lyricBgStyle() {
        return intOf(SettingsCatalog.BG_STYLE_KEY);
    }

    // ---- value plumbing -----------------------------------------------------

    private Object read(SettingSpec spec) {
        Object def = defaultOverrides.containsKey(spec.key) ? defaultOverrides.get(spec.key) : spec.def;
        switch (spec.type) {
            case SettingSpec.SWITCH:
                return store.getBool(spec.key, Boolean.TRUE.equals(def));
            case SettingSpec.STEPPER:
            case SettingSpec.SLIDER:
            case SettingSpec.SEGMENTED:
            case SettingSpec.RADIO:
            case SettingSpec.DROPDOWN:
                return store.getInt(spec.key, def instanceof Number ? ((Number) def).intValue() : 0);
            case SettingSpec.TEXT:
            case SettingSpec.PATH:
                return store.getString(spec.key, def instanceof String ? (String) def : "");
            default:
                return null;
        }
    }

    private void persist(SettingSpec spec, Object v) {
        switch (spec.type) {
            case SettingSpec.SWITCH:
                store.putBool(spec.key, Boolean.TRUE.equals(v));
                break;
            case SettingSpec.STEPPER:
            case SettingSpec.SLIDER:
            case SettingSpec.SEGMENTED:
            case SettingSpec.RADIO:
            case SettingSpec.DROPDOWN:
                store.putInt(spec.key, v instanceof Number ? ((Number) v).intValue() : 0);
                break;
            case SettingSpec.TEXT:
            case SettingSpec.PATH:
                store.putString(spec.key, v instanceof String ? (String) v : "");
                break;
            default:
                break;
        }
    }

    /** QML writes arrive as Long/Double for numbers and can be anything for a
     *  text field; coerce to the spec's own type and clamp numeric controls. */
    private Object normalize(SettingSpec spec, Object raw) {
        switch (spec.type) {
            case SettingSpec.SWITCH:
                return Boolean.TRUE.equals(raw);
            case SettingSpec.STEPPER:
            case SettingSpec.SLIDER: {
                if (!(raw instanceof Number)) return null;
                int v = ((Number) raw).intValue();
                int clamped = Math.max(spec.min, Math.min(spec.max, v));
                if (SettingSpec.SLIDER.equals(spec.type) && spec.dots && spec.step > 0) {
                    int steps = Math.round((clamped - spec.min) / (float) spec.step);
                    clamped = Math.max(spec.min,
                            Math.min(spec.max, spec.min + steps * spec.step));
                }
                return clamped;
            }
            case SettingSpec.SEGMENTED:
            case SettingSpec.RADIO:
            case SettingSpec.DROPDOWN: {
                if (!(raw instanceof Number)) return null;
                int v = ((Number) raw).intValue();
                int last = Math.max(0, spec.options.size() - 1);
                return Math.max(0, Math.min(last, v));
            }
            case SettingSpec.TEXT:
            case SettingSpec.PATH:
                return raw != null ? raw.toString() : "";
            default:
                return null;
        }
    }

    // ---- side effects -------------------------------------------------------

    /** Push every seeded value into whatever consumes it. Runs once at load, so
     *  a consumer sees the persisted state without the host replaying it. */
    private void applyAll() {
        recomputeDark();
        applyLyricConfig();
        applyFontSelection(fontSelection());
        pushToController();
        for (SettingSpec s : specs) {
            if (!s.hasValue()) continue;
            fireHooks(s.key, peek(s.key));
        }
    }

    private void apply(SettingSpec spec, Object v) {
        if (spec.key.startsWith("lyric") && !SettingsCatalog.BG_MODE_KEY.equals(spec.key)) {
            applyLyricConfig();
        } else if (spec.key.startsWith("customApi")) {
            pushCustomApi();
        } else if ("darkMode".equals(spec.key)) {
            recomputeDark();
        } else if (controller != null) {
            switch (spec.key) {
                case "monet": controller.setMonetEnabled(bool("monet")); break;
                case SettingsCatalog.LOW_SPEC_MODE_KEY:
                    controller.setLowSpecMode(bool(SettingsCatalog.LOW_SPEC_MODE_KEY)); break;
                case "unblock": controller.setUnblockEnabled(bool("unblock")); break;
                case "mirror": controller.setUpdateMirror(bool("mirror")); break;
                case "fade": controller.setFadeEnabled(bool("fade")); break;
                case "autoMixMode":
                case "autoMixAutoCue":
                case "autoMixPitchEnabled":
                case "precacheNextTrack":
                case "autoMixStyle":
                case "autoMixWindow":
                case "autoMixForceWindow":
                case "autoMixWidth":
                case "autoMixScale":
                case "autoMixStretch":
                case "autoMixTuning":
                case SettingsCatalog.SMART_TRANSITION_KEY:
                case "aiBaseUrl":
                case "aiApiKey":
                case "aiModel":
                case "aiTimeoutMs":
                    pushTransition();
                    break;
                case "highQuality": controller.setHighQualityEnabled(bool("highQuality")); break;
                case "maxCacheSizeMB": controller.setCacheMaxSizeMB(intOf("maxCacheSizeMB")); break;
                default: break;
            }
        }
        fireHooks(spec.key, v);
    }

    private void fireHooks(String key, Object v) {
        List<Consumer<Object>> list = hooks.get(key);
        if (list == null) return;
        for (Consumer<Object> h : list) h.accept(v);
    }

    private void pushToController() {
        if (controller == null) return;
        controller.setMonetEnabled(bool("monet"));
        controller.setLowSpecMode(bool(SettingsCatalog.LOW_SPEC_MODE_KEY));
        controller.setUnblockEnabled(bool("unblock"));
        controller.setUpdateMirror(bool("mirror"));
        controller.setFadeEnabled(bool("fade"));
        controller.setHighQualityEnabled(bool("highQuality"));
        controller.setCacheMaxSizeMB(intOf("maxCacheSizeMB"));
        pushCustomApi();
        pushTransition();

    }

    /** AutoMix settings and the existing recommendation API timeout. */
    private void pushTransition() {
        if (controller == null) return;
        controller.setPrecacheNextEnabled(bool("precacheNextTrack"));
        try {
            controller.setAutoMixConfig(new dev.t1m3.qplayer.audio.automix.AutoMixConfig(
                    bool(SettingsCatalog.SMART_TRANSITION_KEY), intOf("autoMixMode"),
                    intOf("autoMixStyle"), intOf("autoMixWindow"), bool("autoMixForceWindow"),
                    intOf("autoMixWidth"), intOf("autoMixScale"), bool("autoMixStretch"), str("autoMixTuning"), bool("autoMixAutoCue"), bool("autoMixPitchEnabled")));
        } catch (dev.t1m3.qplayer.audio.mix.TuningException ex) {
            controller.toast.set("AutoMix 参数无效：" + ex.getMessage());
            controller.setAutoMixConfig(new dev.t1m3.qplayer.audio.automix.AutoMixConfig(
                    bool(SettingsCatalog.SMART_TRANSITION_KEY), intOf("autoMixMode"),
                    intOf("autoMixStyle"), intOf("autoMixWindow"), bool("autoMixForceWindow"),
                    intOf("autoMixWidth"), intOf("autoMixScale"), bool("autoMixStretch"), "", bool("autoMixAutoCue"), bool("autoMixPitchEnabled")));
        }
        controller.setAiTransitionConfig(str("aiBaseUrl"), str("aiApiKey"), str("aiModel"), intOf("aiTimeoutMs"));
    }

    private void pushCustomApi() {
        if (controller == null) return;
        CustomApiConfig cfg = new CustomApiConfig();
        cfg.enabled = bool("customApiEnabled");
        cfg.searchUrl = str("customApiSearchUrl");
        cfg.searchListPath = str("customApiSearchListPath");
        cfg.idPath = str("customApiIdPath");
        cfg.namePath = str("customApiNamePath");
        cfg.artistPath = str("customApiArtistPath");
        cfg.albumPath = str("customApiAlbumPath");
        cfg.coverPath = str("customApiCoverPath");
        cfg.durationPath = str("customApiDurationPath");
        cfg.urlUrl = str("customApiUrlUrl");
        cfg.urlResultPath = str("customApiUrlResultPath");
        cfg.lyricUrl = str("customApiLyricUrl");
        cfg.lyricResultPath = str("customApiLyricResultPath");
        cfg.extraHeaders = str("customApiHeaders");
        controller.setCustomApiConfig(cfg);
    }

    private void applyLyricConfig() {
        LyricConfig c = LyricConfig.instance;
        c.lyricFontSize.setValue(intOf("lyricFontSize"));
        int w = Math.max(0, Math.min(3, intOf("lyricFontWeight")));
        c.fontWeight.setValue(LyricConfig.FontWeight.values()[w]);
        c.lineSpacing.setValue(intOf("lyricLineSpacing") / 100f);
        c.springPhysics.setValue(bool("lyricSpring"));
        c.scaleEmphasis.setValue(bool("lyricScale"));
        c.glow.setValue(bool("lyricGlow"));
        c.dropShadow.setValue(bool("lyricShadow"));
        c.linearAnimForPlainLrc.setValue(bool("lyricLinearAnim"));
        c.edgeBlur.setValue(bool("lyricEdgeBlur"));
    }

    // ---- fonts --------------------------------------------------------------

    /** The font source is stored under its own key rather than as a catalog row:
     *  it's picked from a dialog (bundled / system default / any installed
     *  family), not from a row widget. */
    public static final String FONT_KEY = "fontFamily";

    public String fontSelection() {
        if (store == null) return "";
        return store.getString(FONT_KEY, migratedFontSelection());
    }

    /** Set from the font picker dialog. */
    public void setFontSelection(String family) {
        String v = family != null ? family : "";
        if (store != null) store.putString(FONT_KEY, v);
        applyFontSelection(v);
        fontFamilyChanged.set(fontFamilyChanged.peek() + 1);
    }

    /** Bumped on every font change so QML rows showing the current font
     *  re-evaluate (the value itself lives in the store, not in a Property). */
    public final Property<Integer> fontFamilyChanged = new Property<>(0);
    /** The font picker is a dialog rather than a row widget, so the "pickFont"
     *  action just raises this flag and the settings page binds its dialog to it. */
    public final Property<Boolean> fontPickerOpen = new Property<>(Boolean.FALSE);

    /** Reactive current-font readout for the picker dialog and the 外观 row. */
    public String fontFamily() {
        fontFamilyChanged.get();
        return fontSelection();
    }

    /** The font row's action and live text: core-owned (the selection lives in
     *  the store, not in a catalog row), so neither host has to wire them. A host
     *  may still override either by registering the same id first. */
    private void registerFontProviders() {
        infos.putIfAbsent("fontName", () -> {
            String sel = fontFamily();
            if (sel.isEmpty()) return "当前：内置字体 PingFang SC";
            if (Fonts.SYSTEM.equals(sel)) return "当前：系统默认字体";
            return "当前：" + sel;
        });
        actions.putIfAbsent("pickFont", () -> fontPickerOpen.set(Boolean.TRUE));
    }

    /** One-time key moves, run before anything is read: the store keeps whatever
     *  type it was first written with, so a setting that changed shape needs a new
     *  key seeded from the old one. */
    private void migrateLegacyKeys() {
        // "lyricBgStatic" (bool) -> "lyricBgMode" (0 dynamic / 1 static).
        if (!store.has(SettingsCatalog.BG_MODE_KEY) && store.getBool("lyricBgStatic", false)) {
            store.putInt(SettingsCatalog.BG_MODE_KEY, 1);
        }
    }

    /** Fold in the two settings the single font selection replaced. */
    private String migratedFontSelection() {
        String legacyFamily = store.getString("lyricFontFamily", "");
        if (!legacyFamily.isEmpty()) return legacyFamily;
        return store.getBool("useSystemFont", false) ? Fonts.SYSTEM : "";
    }

    private static List<String> sortedFontFamilies() {
        try {
            String[] names = Fonts.listFamilies();
            java.util.Arrays.sort(names, String.CASE_INSENSITIVE_ORDER);
            return java.util.Arrays.asList(names);
        } catch (LinkageError unavailable) {
            // Skija is optional for hosts that render lyrics without the legacy
            // native renderer, such as the Compose Android shell.
            return Collections.emptyList();
        }
    }

    private void applyFontSelection(String selection) {
        if (!nativeFontRendererEnabled) return;
        try {
            Fonts.setSelection(selection);
        } catch (LinkageError unavailable) {
            // Keep non-Skija hosts usable; the selection remains persisted and
            // will be applied when a Skija-backed host is used.
        }
    }
}
