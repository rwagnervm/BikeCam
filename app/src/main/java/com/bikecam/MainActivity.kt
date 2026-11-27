package com.bikecam

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.bikecam.recording.RecordingService
import com.bikecam.settings.MountOrientation
import com.bikecam.ui.BikeCamRoot
import com.bikecam.ui.HardwareKeys
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val graph by lazy { (application as BikeCamApp).graph }
    private var service by mutableStateOf<RecordingService?>(null)
    private var bound = false
    private val hardwareKeys = HardwareKeys()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as RecordingService.LocalBinder).service
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        bindIfAllowed()
        service?.startSensors()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Trava a orientação conforme a montagem no guidão.
        lifecycleScope.launch {
            graph.settings.settings.map { it.orientation }.distinctUntilChanged().collect { o ->
                requestedOrientation = if (o == MountOrientation.LANDSCAPE) {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }
        }
        setContent {
            BikeCamRoot(
                graph = graph,
                service = service,
                hardwareKeys = hardwareKeys,
                onSetBrightness = ::setBrightness,
                onKeepScreenOn = ::setKeepScreenOn,
            )
        }

        val missing = Permissions.required().filterNot { Permissions.has(this, it) }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    /** Vol+/Vol− na tela de gravação (ver [HardwareKeys]); nas demais telas, comportamento normal. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        hardwareKeys.onKeyEvent(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        hardwareKeys.onKeyEvent(event) || super.onKeyUp(keyCode, event)

    private fun setKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** level em 0..1, ou null para voltar ao brilho do sistema. */
    private fun setBrightness(level: Float?) {
        window.attributes = window.attributes.apply {
            screenBrightness = level ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    override fun onStart() {
        super.onStart()
        bindIfAllowed()
    }

    private fun bindIfAllowed() {
        if (bound || !Permissions.camera(this)) return
        bound = bindService(Intent(this, RecordingService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        service?.setSurfaceProvider(null)
        if (bound) {
            unbindService(connection)
            bound = false
        }
        service = null
        setBrightness(null)
        super.onStop()
    }
}
