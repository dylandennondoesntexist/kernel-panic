package app.kernelpanic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import app.kernelpanic.ui.KernelPanicApp

class MainActivity : ComponentActivity() {
    private val viewModel: KernelPanicViewModel by viewModels()
    private lateinit var alerts: AlertController
    private var permissionCallback: ((Boolean, Boolean) -> Unit)? = null
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val permanentlyDenied = !granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        permissionCallback?.invoke(granted, permanentlyDenied)
        permissionCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        alerts = AlertController(this)
        setContent {
            KernelPanicApp(
                viewModel = viewModel,
                alertController = alerts,
                hasMicrophonePermission = { ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
                requestMicrophonePermission = { callback ->
                    permissionCallback = callback
                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                openAppSettings = {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
                },
                openDebugAudioLab = {
                    if (BuildConfig.DEBUG) {
                        startActivity(Intent().setClassName(packageName, "app.kernelpanic.debug.DebugAudioLabActivity"))
                    }
                },
            )
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && viewModel.state.value.listening) {
            viewModel.interruptListening("Listening stopped because Kernel Panic left the foreground.")
        }
    }

    override fun onDestroy() {
        alerts.release()
        super.onDestroy()
    }
}
