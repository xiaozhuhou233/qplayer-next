param(
    [string]$Apk = (Join-Path $PSScriptRoot 'app/build/outputs/apk/debug/app-debug.apk'),
    [string]$Sdk = $env:ANDROID_HOME
)
$ErrorActionPreference = 'Stop'
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$dexdump = Join-Path $Sdk 'build-tools/35.0.0/dexdump.exe'
if (-not (Test-Path -LiteralPath $dexdump)) { throw "Missing SDK dexdump: $dexdump" }
$auditRoot = Join-Path $PSScriptRoot 'app/build/apk-audit'
New-Item -ItemType Directory -Force $auditRoot | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($apkPath)
try {
    foreach ($name in @('assets/grok-mascot/index.html', 'assets/grok-mascot/engine.js', 'assets/licenses/musicone-LICENSE.txt', 'assets/licenses/Backdrop-Apache-2.0.txt', 'assets/licenses/BiliPai-GPL-3.0.txt', 'assets/licenses/Material-Color-Utilities-Apache-2.0.txt',
        'assets/licenses/PixelPlayer-LICENSE.txt', 'assets/licenses/Kablammo-OFL.txt', 'assets/licenses/Playpen_Sans-OFL.txt',
        'assets/licenses/Cherry_Bomb_One-OFL.txt', 'assets/licenses/Arizonia-OFL.txt', 'assets/licenses/Lavishly_Yours-OFL.txt', 'assets/licenses/Unkempt-LICENSE.txt',
        'assets/licenses/LyricsBlossom-NOTICE.txt')) {
        $entry = $zip.GetEntry($name)
        if (-not $entry -or $entry.Length -eq 0) { throw "Missing mascot asset: $name" }
        $reader = [IO.StreamReader]::new($entry.Open(), [Text.Encoding]::UTF8)
        try { $packaged = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $source = Get-Content -LiteralPath (Join-Path $PSScriptRoot "app/src/main/$name") -Raw -Encoding UTF8
        if ($packaged -cne $source) { throw "Stale mascot asset in APK: $name" }
    }
    'Mascot assets passed: original HTML and engine.js match the workspace.'
    $definitions = @()
    foreach ($entry in $zip.Entries) {
        if ($entry.FullName -match '(?i)(\.onnx$|onnxruntime)') { throw "Unexpected AI asset: $($entry.FullName)" }
        if ($entry.FullName -notmatch '^classes[0-9]*\.dex$') { continue }
        $dexPath = Join-Path $auditRoot $entry.FullName
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $dexPath, $true)
        $definitions += @(& $dexdump $dexPath | Select-String '^\s*Class descriptor\s*:' | ForEach-Object { $_.Line })
        if ($LASTEXITCODE -ne 0) { throw "dexdump failed for $($entry.FullName)" }
    }
    foreach ($required in @(
        'dev/t1m3/qplayer/lyric/ReferenceLyricMotion',
        'dev/t1m3/qplayer/lyric/ReferenceLyricTimeline',
        'dev/t1m3/qplayer/android/md3eui/Md3eLyricsKt',
        'dev/t1m3/qplayer/android/md3eui/Md3eActivity',
        'dev/t1m3/qplayer/android/md3eui/Md3eSonicChromeKt',
        'dev/t1m3/qplayer/android/md3eui/Md3eSonicPagesKt',
        'dev/t1m3/qplayer/android/md3eui/Md3eSonicArtistKt',
        'dev/t1m3/qplayer/android/md3eui/Md3eSonicPaletteKt',
        'dev/t1m3/qplayer/android/md3eui/Md3eSonicGenresKt',
        'dev/t1m3/qplayer/android/md3eui/SonicGenreBrowser',
        'dev/t1m3/qplayer/android/md3eui/Md3eSonicCardStyleKt',
        'dev/t1m3/qplayer/android/md3eui/Md3eSonicVibeAccordionKt',
        'dev/t1m3/qplayer/android/md3eui/Md3eSonicPlayerKt',
        'dev/t1m3/qplayer/android/md3eui/Md3ePixelPlayerMotionKt',
        'dev/t1m3/qplayer/android/md3eui/Md3eSonicDisplayFontsKt',
        'dev/t1m3/qplayer/android/md3eui/SonicDisplayFonts',
        'dev/t1m3/qplayer/android/transfer/AccountMigrationKt',
        'dev/t1m3/qplayer/store/AccountTransfer',
        'dev/t1m3/qplayer/netease/NeteaseDiscovery',
        'dev/t1m3/qplayer/netease/NeteaseListening',
        'dev/t1m3/qplayer/android/md3eui/Md3eListeningPageKt',
        'dev/t1m3/qplayer/color/MaterialExpressivePalette',
        'dev/t1m3/qplayer/color/MaterialArtworkPalette',
        'dev/t1m3/qplayer/color/material/scheme/SchemeExpressive',
        'dev/t1m3/qplayer/color/material/scheme/SchemeTonalSpot',
        'dev/t1m3/qplayer/color/material/scheme/SchemeVibrant',
        'dev/t1m3/qplayer/color/material/scheme/SchemeNeutral',
        'dev/t1m3/qplayer/color/material/scheme/SchemeRainbow',
        'dev/t1m3/qplayer/color/material/scheme/SchemeFruitSalad',
        'dev/t1m3/qplayer/android/md3eui/Md3ePaletteSettingsKt',
        'dev/t1m3/qplayer/android/md3eui/Md3eAiMascotKt',
        'dev/t1m3/qplayer/android/md3eui/Md3eMvBackdropKt',
        'dev/t1m3/qplayer/android/md3eui/MvWarmPlayer',
        'dev/t1m3/qplayer/android/md3eui/MvBackdropState',
        'dev/t1m3/qplayer/android/md3eui/Md3eMvClipKt',
        'dev/t1m3/qplayer/android/md3eui/Md3eIosThemeKt',
        'dev/t1m3/qplayer/android/md3eui/Md3eIosPagesKt',
        'dev/t1m3/qplayer/android/md3eui/Md3eIosChromeKt',
        'dev/t1m3/qplayer/android/md3eui/PageMotion',
        'dev/t1m3/qplayer/android/ui/IosPlayerDockKt',
        'dev/t1m3/qplayer/android/ui/IosDialogsKt',
        'com/kyant/backdrop/catalog/components/LiquidBottomTabsKt',
        'dev/t1m3/qplayer/android/md3eui/Md3ePlayerControlShadowKt',
        'dev/t1m3/qplayer/android/md3eui/Md3eDailyVibes',
        'dev/t1m3/qplayer/android/md3eui/Md3eDailyVibeShelfKt',
        'dev/t1m3/qplayer/android/md3eui/Md3eSongQueueMenuKt',
        'dev/t1m3/qplayer/android/md3eui/Md3eBiliKt',
        'dev/t1m3/qplayer/android/md3eui/Md3eNeteaseLoginKt',
        'dev/t1m3/qplayer/android/playback/AndroidAudioBackend',
        'dev/t1m3/qplayer/android/playback/PlaybackService',
        'dev/t1m3/qplayer/android/graphics/AndroidColorExtractor',
        'dev/t1m3/qplayer/bridge/PlayerController',
        'dev/t1m3/qplayer/ai/DailyVibeCuration',
        'dev/t1m3/qplayer/netease/NeteaseClient',
        'dev/t1m3/qplayer/bili/BiliClient',
        'dev/t1m3/qplayer/unblock/SongUnblocker',
        'dev/t1m3/qplayer/unblock/NeteaseSource',
        'dev/t1m3/qplayer/unblock/BodianSource',
        'dev/t1m3/qplayer/unblock/KuwoSource'
    )) {
        if (-not ($definitions | Select-String -SimpleMatch "L$required;")) { throw "Missing required class: $required" }
    }
    $forbidden = @($definitions | Where-Object {
        ($_ -match 'Ldev/t1m3/qplayer/android/ui/' -and
            $_ -notmatch 'Ldev/t1m3/qplayer/android/ui/(Ios|ComposableSingletons\$Ios|RestoredIos|RestoredDialogGlassSampler|DialogBackdropState|Glass|AndroidGlass|BiliPai|DeviceTilt|SystemBar|motion/IosDock)') -or
        $_ -match 'Ldev/t1m3/qplayer/android/stem/'
    })
    if ($forbidden.Count) { throw "Unexpected legacy UI/AI classes: $($forbidden -join ', ')" }
    "APK isolation passed: $($definitions.Count) class definitions; MD3E root and existing playback present; restored iOS chrome allowed; legacy app, engine and ONNX absent."
} finally {
    $zip.Dispose()
}

