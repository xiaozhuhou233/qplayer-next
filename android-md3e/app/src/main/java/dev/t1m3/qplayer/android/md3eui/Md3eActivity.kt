package dev.t1m3.qplayer.android.md3eui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat

class Md3eActivity : ComponentActivity() {
    private lateinit var runtime: Md3eRuntime
    private lateinit var frameMonitor: Md3eFrameMonitor
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        runtime.setLocalPermission(granted)
        if (granted) runtime.scanLocal()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SonicDisplayFonts.warmUp(applicationContext)
        enableEdgeToEdge()
        // Draw the background through the cutout; only controls consume safe insets.
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isStatusBarContrastEnforced = false
        runtime = Md3eRuntime.get(applicationContext)
        frameMonitor = Md3eFrameMonitor(this, runtime)
        runtime.setLocalPermission(checkSelfPermission(audioPermissionName()) == PackageManager.PERMISSION_GRANTED)
        setContent {
            Md3eApp(runtime, onDarkAppearance = { dark ->
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }, onPlay = { action ->
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                runtime.play(action)
            }, onRequestAudioPermission = {
                if (checkSelfPermission(audioPermissionName()) == PackageManager.PERMISSION_GRANTED) {
                    runtime.setLocalPermission(true)
                    runtime.scanLocal()
                } else {
                    audioPermission.launch(audioPermissionName())
                }
            })
        }
        window.decorView.post { runtime.controller.notifyUiInteractive() }
    }

    override fun onStart() {
        super.onStart()
        runtime.onVisible()
        frameMonitor.start()
    }

    override fun onStop() {
        frameMonitor.stop()
        runtime.onHidden()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun audioPermissionName(): String = if (Build.VERSION.SDK_INT >= 33)
        Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
}
