package dev.t1m3.qplayer.settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Every setting the app has, declared once. The settings page is generated from
 * this list at runtime — adding a row here is the whole change, on both
 * platforms at once.
 *
 * <p>Rows render in declaration order under the category they name, and
 * consecutive rows sharing a {@code group} share one card. The grouping,
 * wording and widget choice below reproduce the hand-written page this replaced
 * one for one.
 *
 * <p>A row limited to one host (the desktop-only folder paths) carries
 * {@link SettingSpec.Builder#onlyOn}; the other host never sees it, which
 * replaces the {@code typeof settings.x !== "undefined"} guards the QML used to
 * carry.
 */
public final class SettingsCatalog {

    public static final String DESKTOP = "desktop";
    public static final String ANDROID = "android";

    public static final String APPEARANCE = "外观";
    public static final String PLAYBACK = "播放";
    public static final String LYRIC = "歌词";
    public static final String LOCAL = "本地";
    public static final String SOUND = "音效";
    public static final String ABOUT = "关于";
    public static final String AI = "AI";
    /** ACE-Step: the cloud model that GENERATES a transition passage from the two neighbours.
     *  Its own group, not a corner of {@link #AI}: that group is the AI music ASSISTANT (an
     *  OpenAI-compatible chat endpoint used for style analysis and recommendations), while this
     *  one is a music-generation endpoint with its own protocol, its own key and its own model
     *  name. Sharing a key between them would be wrong. */
    public static final String ACE_STEP = "ACE-Step";
    public static final String PALETTE = "调色板";
    public static final String LOW_SPEC_MODE_KEY = "lowSpecMode";

    /** The ACE-Step group's rows, by key: the switch, the address, the key (the user's own, never
     *  this app's), the model, and how far under the passage the generated bed sits. Named here
     *  because {@code SettingsCore} pushes all five as one configuration, and a key that is only a
     *  string literal at its own spec is a key the push can miss. */
    public static final String ACE_STEP_ENABLED_KEY = "aceStepEnabled";
    public static final String ACE_STEP_BASE_URL_KEY = "aceStepBaseUrl";
    public static final String ACE_STEP_API_KEY_KEY = "aceStepApiKey";
    public static final String ACE_STEP_MODEL_KEY = "aceStepModel";
    public static final String ACE_STEP_BED_DB_KEY = "aceStepBedDb";

    public static final List<String> CATEGORIES = Collections.unmodifiableList(
            Arrays.asList(APPEARANCE, PLAYBACK, SOUND, LYRIC, LOCAL, AI, PALETTE, ABOUT));

    /** Fluid-background mode, 0 dynamic / 1 static. Stored under a new key
     *  because the same setting used to be a boolean ("lyricBgStatic") and a
     *  store can't reinterpret a persisted bool as an int — see the one-time
     *  migration in SettingsCore.load. */
    public static final String BG_MODE_KEY = "lyricBgMode";

    /** Fluid backdrop renderer, kept separate from dynamic/static so every style
     *  can still use the existing battery-saving static cache. */
    public static final String BG_STYLE_KEY = "lyricBgStyle";
    /** When enabled, lyric/detail pages use the blurred album artwork. When
     *  disabled, they use a flat Monet/MD3 colour background instead. */
    public static final String COVER_BACKGROUND_KEY = "lyricCoverBackground";
    public static final String MV_BACKGROUND_KEY = "playerMvBackground";
    public static final int BG_STYLE_PIXI_RENDERER = 0;
    public static final int BG_STYLE_MESH_GRADIENT = 1;
    public static final int BG_STYLE_CLASSIC = 2;

    // Dark-mode row values (the segmented control's indices).
    public static final int MODE_SYSTEM = 0;
    public static final int MODE_LIGHT = 1;
    public static final int MODE_DARK = 2;

    /** Shared full-page transition presets. QML and the host-drawn lyric page
     *  both read this index so navigation never changes motion language when it
     *  crosses the QML/Skia rendering boundary. */
    public static final String PAGE_TRANSITION_KEY = "pageTransitionPreset";
    public static final int PAGE_TRANSITION_ZOOM = 0;
    public static final int PAGE_TRANSITION_FADE = 1;
    public static final int PAGE_TRANSITION_SLIDE_HORIZONTAL = 2;
    public static final int PAGE_TRANSITION_SLIDE_VERTICAL = 3;
    public static final int PAGE_TRANSITION_NONE = 4;

    // ---- Track-to-track transitions (「智能过渡」) -------------------------
    // Three rows, one feature. The switch is the single source of truth for
    // whether any transition happens at all; the other two only say which one,
    // and are hidden while it is off (dependsOn below).

    /** Whether one track gives way to the next through a transition instead of
     *  the historical hard cut. Persisted as a bool and pushed straight into
     *  PlayerController.setTransitionEnabled, which is the only thing that reads
     *  it — so toggling it takes effect on the next boundary and survives a
     *  restart through the ordinary settings store. */
    public static final String SMART_TRANSITION_KEY = "smartTransition";
    /** Which transition every boundary gets: 0 = 自动 (PlayerController's
     *  TransitionChooser decides, the local heuristic by default), otherwise
     *  index+1 into {@link dev.t1m3.qplayer.audio.TransitionKind#CHOICES}. */
    public static final String TRANSITION_KIND_KEY = "transitionKind";
    /** The gain curve the overlapping kinds ramp along: 0 = 线性 (FadeCurve.LINEAR,
     *  what P1 shipped), 1 = 等功率 (FadeCurve.EQUAL_POWER). A separate row rather
     *  than a fourth kind because it applies to two kinds at once. */
    public static final String TRANSITION_CURVE_KEY = "transitionCurve";
    /** Whether an overlapping transition is snapped onto the two tracks' beat grids
     *  (P4): the outgoing ramp starts on a beat of the outgoing track and the
     *  incoming track starts on a beat of its own, instead of wherever the file
     *  boundaries are. Only ever acts when both grids are measured and compatible —
     *  a wrong grid must not move a boundary — so leaving it on cannot make a
     *  boundary worse than not having it, only better. Persisted as a bool and
     *  pushed into PlayerController.setBeatAlignmentEnabled. */
    public static final String BEAT_ALIGN_KEY = "beatAlign";
    /** Whether the low end changes hands in the middle of an overlap (「低频互换」):
     *  the incoming track's bass is cut while the outgoing one still owns it, and the
     *  two swap on a beat. This is the one part of a blend that needs a platform audio
     *  effect to work, so it has its own switch: a device whose effect framework
     *  misbehaves plays the same overlap and loses only this. Persisted as a bool and
     *  pushed into PlayerController.setBassSwapEnabled. */
    public static final String BASS_SWAP_KEY = "bassSwap";
    /** How long an ordinary pair is blended over, in SECONDS — the one number the user
     *  sets about a transition's length. Stored in seconds (4–30, step 1) because that is
     *  what the row shows, and converted to ms where the controller wants it:
     *  {@code PlayerController.setBlendDurationMs}. Applied immediately and on every
     *  change — the controller keeps no copy, so a boundary decided after a change uses
     *  the new length, and the length appears in the boundary's own log line
     *  ({@code 重叠=long 20000ms}) plus in the reason a chooser's shorter answer was
     *  raised ({@code raised to the 过渡时长 (20s) blend}). The default is
     *  {@code TransitionPlan.OVERLAP_LONG_MS}, which is what every round before this row
     *  used as a constant. */
    public static final String TRANSITION_BLEND_KEY = "transitionBlendSeconds";

    private SettingsCatalog() {}

    public static List<SettingSpec> specs() {
        List<SettingSpec> out = new ArrayList<>();

        // ---- 外观 -----------------------------------------------------------
        out.add(SettingSpec.toggle("claudeDesign", APPEARANCE, "Claude 界面", true)
                .desc("暖纸色与橙色强调")
                .onlyOn(ANDROID)
                .build());
        out.add(SettingSpec.toggle("sonicDesign", APPEARANCE, "MD3E 界面", false)
                .desc("大卡片与 Material 3 配色")
                .onlyOn(ANDROID)
                .build());
        out.add(SettingSpec.toggle("sonicDisplayFonts", APPEARANCE, "艺术字", true)
                .desc("用于 MD3E 标题和歌手名称")
                .onlyOn(ANDROID)
                .build());
        out.add(SettingSpec.toggle("iosDesign", APPEARANCE, "ios design", false)
                .desc("液态玻璃界面，完整折射需 Android 13+")
                .onlyOn(ANDROID)
                .build());
        out.add(SettingSpec.toggle(LOW_SPEC_MODE_KEY, APPEARANCE, "最低配模式", false)
                .desc("使用 MD3，关闭重特效和后台音频预处理")
                .onlyOn(ANDROID)
                .build());
        out.add(SettingSpec.toggle(MV_BACKGROUND_KEY, APPEARANCE, "MV 动态背景（实验性）", false)
                .desc("播放页循环静音 MV，随音乐暂停。消耗流量，最低配模式停用")
                .onlyOn(ANDROID)
                .build());
        out.add(SettingSpec.toggle("iosGlassRefraction", APPEARANCE, "玻璃折射", true)
                .desc("关闭后仅保留模糊玻璃")
                .onlyOn(ANDROID)
                .build());
        out.add(SettingSpec.segmented("darkMode", APPEARANCE, "深色模式", MODE_SYSTEM,
                        "跟随系统", "浅色", "深色")
                .build());
        out.add(SettingSpec.dropdown(PAGE_TRANSITION_KEY, APPEARANCE, "页面切换动画",
                        PAGE_TRANSITION_ZOOM, "Zoom In / Out", "淡入淡出", "水平滑动", "垂直滑动", "无动画")
                .build());
        out.add(SettingSpec.toggle("monet", APPEARANCE, "莫奈取色", true)
                .desc("从封面提取主题色")
                .accessory("swatch")
                .build());
        out.add(SettingSpec.action("pickFont", APPEARANCE, "字体", "选择")
                .provider("fontName")
                .desc("歌词立即生效，其他页面需重启")
                .build());
        out.add(SettingSpec.radio("graphicsBackend", APPEARANCE, "图形后端", 0,
                        "OpenGL", "Vulkan")
                .desc("重启生效；Vulkan 失败时恢复 OpenGL")
                .onlyOn(DESKTOP)
                .build());
        out.add(SettingSpec.toggle("windowDecorated", APPEARANCE, "使用系统标题栏", false)
                .desc("仅 Windows，重启生效")
                .onlyOn(DESKTOP)
                .build());
        out.add(SettingSpec.toggle("showLocalTab", APPEARANCE, "显示本地标签", true)
                .desc("显示本地音乐入口")
                .build());

        // ---- 播放 -----------------------------------------------------------
        out.add(SettingSpec.toggle("unblock", PLAYBACK, "音源解锁", true)
                .desc("受限歌曲尝试其他音源")
                .build());
        // Default follows the UI locale: the mirror only helps from mainland China.
        out.add(SettingSpec.toggle("mirror", PLAYBACK, "下载加速镜像", isSimplifiedChinese())
                .desc("通过镜像下载更新")
                .build());
        out.add(SettingSpec.toggle("fade", PLAYBACK, "淡入淡出", false)
                .desc("切歌或结束时渐变音量")
                .build());
        // Keep the old master key so an existing on/off preference migrates unchanged.
        out.add(SettingSpec.toggle(SMART_TRANSITION_KEY, PLAYBACK, "AutoMix", true)
                .desc("本地分析并融合下一首；视频不混音")
                .build());
        out.add(SettingSpec.dropdown("autoMixMode", PLAYBACK, "AutoMix 模式", 2,
                "交叉混音", "和声混音", "智能混音（本地 DSP）", "连续切换")
                .dependsOn(SMART_TRANSITION_KEY).build());
        out.add(SettingSpec.toggle("autoMixAutoCue", PLAYBACK, "自动点位", true)
                .desc("有可能吃掉下一首歌一分钟")
                .dependsOn(SMART_TRANSITION_KEY).build());
        out.add(SettingSpec.toggle("autoMixPitchEnabled", PLAYBACK, "过渡降调", true)
                .desc("混音时调整音高，关闭则保持原调")
                .dependsOn(SMART_TRANSITION_KEY).build());
        out.add(SettingSpec.toggle("precacheNextTrack", PLAYBACK, "提前缓存下一首", true)
                .desc("预下载下一首，供播放和 AutoMix 使用")
                .build());
        String[] styles = new String[dev.t1m3.qplayer.audio.mix.MixStyle.UI_ORDER.length];
        for (int i = 0; i < styles.length; i++)
            styles[i] = dev.t1m3.qplayer.audio.mix.MixStyle.UI_ORDER[i].label();
        out.add(SettingSpec.dropdown("autoMixStyle", PLAYBACK, "智能混音风格", 0, styles)
                .desc("仅智能混音；auto 自动选择，无需 AI")
                .dependsOn(SMART_TRANSITION_KEY).build());
        out.add(SettingSpec.slider("autoMixWindow", PLAYBACK, "过渡时长", 6, 1, 30, 1)
                .desc("调整后启用固定窗口；前曲至少播放 5/7")
                .unit(" 秒").dependsOn(SMART_TRANSITION_KEY).build());
        out.add(SettingSpec.toggle("autoMixForceWindow", PLAYBACK, "固定过渡窗口", true)
                .desc("按设定时长融合；关闭后由算法调整")
                .dependsOn(SMART_TRANSITION_KEY).build());
        out.add(SettingSpec.slider("autoMixWidth", PLAYBACK, "混合宽度", 50, 0, 100, 5)
                .unit("%").dependsOn(SMART_TRANSITION_KEY).build());
        out.add(SettingSpec.slider("autoMixScale", PLAYBACK, "窗口缩放", 100, 50, 200, 10)
                .desc("缩放自动窗口；固定窗口下无效")
                .unit("%").dependsOn(SMART_TRANSITION_KEY).build());
        out.add(SettingSpec.toggle("autoMixStretch", PLAYBACK, "节奏伸缩", true)
                .desc("让过渡节奏更接近")
                .dependsOn(SMART_TRANSITION_KEY).build());
        out.add(SettingSpec.text("autoMixTuning", PLAYBACK, "AutoMix 高级参数", "")
                .desc("逗号分隔，如 max_shift=2；上方选项优先")
                .dependsOn(SMART_TRANSITION_KEY).build());
        out.add(SettingSpec.toggle("highQuality", PLAYBACK, "高音质播放", true)
                .desc("关闭后节省流量")
                .build());
        // 保后台：请求系统把本应用排除在电池优化之外；没有白名单时，部分 ROM 会在
        // 息屏后冻结进程或直接清理正在播放的前台服务。状态文本读自 PowerManager，
        // 回到应用（onVisible / onStart）时刷新。
        out.add(SettingSpec.action("backgroundKeepAlive", PLAYBACK, "保后台运行", "去申请")
                .provider("batteryStatus").inlineProvider()
                .desc("关闭电池优化；部分机型还需允许自启动")
                .build());

        // Custom API source: one card, the switch plus the field block it gates.
        out.add(SettingSpec.toggle("customApiEnabled", PLAYBACK, "启用自定义 API 源", false)
                .desc("使用第三方搜索和播放接口")
                .group("customApi")
                .build());

        // ---- AI 音乐助手 ---------------------------------------------------
        out.add(SettingSpec.dropdown("aiProvider", AI, "AI 服务商", 0,
                "自定义 OpenAI 兼容", "DeepSeek", "OpenAI", "Gemini")
                .build());
        out.add(SettingSpec.text("aiBaseUrl", AI, "API 地址 *", "https://api.openai.com/v1")
                .desc("兼容 OpenAI 的接口地址")
                .build());
        out.add(SettingSpec.text("aiApiKey", AI, "API Key *", "")
                .desc("仅保存在本机")
                .build());
        out.add(SettingSpec.text("aiModel", AI, "模型名称 *", "gpt-5.6-luna")
                .build());
        out.add(SettingSpec.slider("aiTimeoutMs", AI, "请求超时", 60000, 10000, 180000, 5000)
                .unit(" ms").build());
        // 调试入口（用户 2026-10-10：「单独给我开一个接入api的聊天ai接口，输入问题然后看到
        // api 的答复，我要调试」）：一次请求、不重试、显示原始答复与耗时，用来分辨
        // 模型名写错 / 网关拒绝 / 单纯很慢。它不参与 AI DJ 的生成路径。
        out.add(SettingSpec.action("aiChatProbe", AI, "AI 对话测试", "打开")
                .desc("查看原始答复与耗时")
                .build());
        out.add(SettingSpec.toggle("aiDailyPhraseEnabled", AI, "每日音乐短句", true)
                .desc("每天生成首页短句，离线使用本地内容")
                .onlyOn(ANDROID)
                .build());
        out.add(SettingSpec.toggle("aiExcludeLiked", AI, "排除已收藏歌曲", false)
                .desc("参考收藏风格，不重复推荐收藏歌曲")
                .build());
        out.add(SettingSpec.toggle("aiGeminiKnowledgeOnly", AI, "Gemini 强制使用知识库", false)
                .desc("不联网，使用模型知识推荐")
                .build());
        out.add(SettingSpec.text("aiWebSearchUrl", AI, "搜索 API 地址", "https://api.tavily.com/search")
                .desc("默认 Tavily，支持兼容接口")
                .build());
        out.add(SettingSpec.text("aiWebSearchKey", AI, "搜索 API Key", "")
                .desc("填写自己的 Key，仅本机保存")
                .build());

        out.add(SettingSpec.toggle("aiForceKnowledge", AI, "强制使用知识库", false)
                .desc("搜索失败时使用模型知识")
                .build());
        out.add(SettingSpec.toggle("aiShowOutput", AI, "显示输出结果", false)
                .desc("显示原始答复，便于排错")
                .build());
        addCustomApiFields(out);

        // ---- 专辑封面调色板调试 -------------------------------------------
        out.add(SettingSpec.toggle("paletteEnabled", PALETTE, "启用专辑封面调色板", true)
                .desc("从专辑封面生成主题色")
                .build());
        out.add(SettingSpec.slider("paletteChroma", PALETTE, "调色板鲜艳度", 1, 0, 2, 1)
                .desc("0 柔和 · 1 标准 · 2 鲜艳")
                .build());
        out.add(SettingSpec.segmented("paletteStyle", PALETTE, "调色板风格", 0,
                "Tonal Spot", "Vibrant", "Expressive", "Fruit Salad")
                .desc("选择 Material 3 配色方案")
                .build());

        // ---- 歌词 -----------------------------------------------------------
        // One card per control, like every other tab: no group() here, so each
        // row is its own card and the wide-window grid can pair them up.
        out.add(SettingSpec.slider("lyricFontSize", LYRIC, "字号", 28, 14, 40, 1)
                .unit(" px").dots()
                .build());
        out.add(SettingSpec.segmented("lyricFontWeight", LYRIC, "字重", 2,
                        "极细", "细", "常规", "中等")
                .build());
        out.add(SettingSpec.slider("lyricLineSpacing", LYRIC, "行间距", 200, 100, 250, 5)
                .scale(100).unit("×").dots()
                .build());
        out.add(SettingSpec.toggle("lyricSpring", LYRIC, "弹簧动效", true)
                .desc("歌词弹簧滚动，无逐字时间则整行上抬")
                .build());
        out.add(SettingSpec.toggle("lyricScale", LYRIC, "放大缩放", true)
                .desc("放大当前歌词行")
                .build());
        out.add(SettingSpec.toggle("lyricGlow", LYRIC, "单词发光", true)
                .desc("逐字辉光，较耗电；开启阴影时仅长音发光")
                .build());
        out.add(SettingSpec.toggle("lyricShadow", LYRIC, "歌词阴影", true)
                .desc("为歌词和翻译添加投影")
                .build());
        out.add(SettingSpec.toggle("lyricLinearAnim", LYRIC, "非逐字歌词线性动画", false)
                .desc("关闭时整行一起点亮")
                .build());
        out.add(SettingSpec.toggle("lyricEdgeBlur", LYRIC, "边缘模糊", false)
                .desc("模糊非当前歌词，较耗电")
                .build());
        out.add(SettingSpec.toggle("lyricMd3Color", LYRIC, "MD3 歌词颜色", false)
                .desc("歌词使用主题色")
                .build());
        out.add(SettingSpec.toggle("lyricParticles", LYRIC, "粒子歌词", false)
                .desc("光点随歌词进度移动")
                .build());
        out.add(SettingSpec.toggle("desktopLyricEnabled", LYRIC, "桌面歌词", false)
                .desc("置顶显示歌词浮窗")
                .onlyOn(DESKTOP)
                .build());
        out.add(SettingSpec.segmented("lyricProgressStyle", LYRIC, "进度条样式", 1,
                        "波浪", "直线")
                .build());
        out.add(SettingSpec.toggle(COVER_BACKGROUND_KEY, LYRIC, "封面背景", true)
                .desc("开启使用模糊封面，关闭使用主题色")
                .build());
        // ---- 本地 -----------------------------------------------------------
        out.add(SettingSpec.slider("maxCacheSizeMB", LOCAL, "最大缓存", 200, 50, 1024, 1)
                .unit(" MB").group("cache")
                .build());
        out.add(SettingSpec.action("clearCache", LOCAL, "当前占用", "清除缓存")
                .provider("cacheUsage").inlineProvider().buttonType("outlined")
                .group("cache")
                .build());
        out.add(SettingSpec.path("cacheFolder", LOCAL, "缓存目录", "")
                .desc("修改后重建缓存，旧文件不搬移")
                .hint("目录路径")
                .group("cache")
                .onlyOn(DESKTOP)
                .build());
        out.add(SettingSpec.path("musicFolder", LOCAL, "本地音乐目录", "")
                .desc("修改后自动重新扫描")
                .hint("目录路径")
                .onlyOn(DESKTOP)
                .build());

        // ---- 音效 -----------------------------------------------------------
        // Ⓜ The equalizer: a device Equalizer on the playing audio session, with
        // per-band levels stored as a CSV of millibels (see Md3eEqualizer).
        // The editor owns these values; hidden entries still participate in persistence.
        out.add(SettingSpec.hidden("eqBands", SettingSpec.TEXT, "").build());
        out.add(SettingSpec.hidden("eqBass", SettingSpec.TEXT, "0").build());
        out.add(SettingSpec.toggle("eqEnabled", SOUND, "均衡器", false)
                .desc("调节播放音效")
                .build());
        out.add(SettingSpec.action("eqEditor", SOUND, "均衡器调节", "")
                .icon("tune")
                .desc("调节频段与低音，立即生效")
                .build());

        // ---- 关于 -----------------------------------------------------------
        // The MD3E log system: one session file per enabled run with every level
        // (warnings and errors included), plus an export to the downloads folder.
        out.add(SettingSpec.toggle("logCaptureEnabled", ABOUT, "记录运行日志", false)
                .desc("在本机保存运行日志")
                .build());
        out.add(SettingSpec.action("logExport", ABOUT, "导出日志", "")
                .icon("description")
                .desc("导出最近日志到下载目录")
                .build());
        out.add(SettingSpec.action("openRepo", ABOUT, "QPlayer", "")
                .icon("link")
                .provider("version").inlineProvider()
                // Hard-coded breaks: qml4j's auto-wrap mis-measures this width.
                .desc("网易云音乐第三方客户端")
                .build());
        out.add(SettingSpec.action("checkUpdate", ABOUT, "检查更新", "")
                .icon("system_update")
                .build());

        return out;
    }

    /** The custom-API adapter's field block — all in its card, all gated on the
     *  switch above them. */
    private static void addCustomApiFields(List<SettingSpec> out) {
        addApiField(out, "customApiSearchUrl", "搜索接口 URL 模板", "https://host/search?key={keyword}");
        addApiField(out, "customApiSearchListPath", "搜索结果列表路径", "如 data.list");
        addApiField(out, "customApiIdPath", "id 字段路径", "如 id");
        addApiField(out, "customApiNamePath", "歌名字段路径", "如 name");
        addApiField(out, "customApiArtistPath", "歌手字段路径（可选）", "如 artists[].name");
        addApiField(out, "customApiAlbumPath", "专辑字段路径（可选）", "如 album.name");
        addApiField(out, "customApiCoverPath", "封面字段路径（可选）", "如 pic");
        addApiField(out, "customApiDurationPath", "时长字段路径（可选，单位：秒）", "如 duration");
        addApiField(out, "customApiUrlUrl", "播放地址 URL 模板", "https://host/url?id={id}");
        addApiField(out, "customApiUrlResultPath", "播放地址结果路径", "如 data.url");
        addApiField(out, "customApiLyricUrl", "歌词接口 URL 模板（可选）", "https://host/lyric?id={id}");
        addApiField(out, "customApiLyricResultPath", "歌词结果路径（可选，纯 LRC 文本）", "如 data.lyric");
        addApiField(out, "customApiHeaders", "请求头（可选，多个用 ; 分隔）",
                "如 Authorization: Bearer xxx; X-Custom: 1");
    }

    private static void addApiField(List<SettingSpec> out, String key, String title, String hint) {
        out.add(SettingSpec.text(key, PLAYBACK, title, "")
                .hint(hint)
                .dependsOn("customApiEnabled")
                .group("customApi")
                .build());
    }

    /** Mainland-Chinese UI locale (zh, not Traditional, not TW/HK/MO) — the one
     *  place that test lives now; both platform Settings classes used to carry
     *  their own copy of it. */
    public static boolean isSimplifiedChinese() {
        java.util.Locale l = java.util.Locale.getDefault();
        if (!"zh".equalsIgnoreCase(l.getLanguage())) return false;
        if ("Hant".equalsIgnoreCase(l.getScript())) return false;
        String country = l.getCountry();
        return !("TW".equalsIgnoreCase(country) || "HK".equalsIgnoreCase(country)
                || "MO".equalsIgnoreCase(country));
    }
}
