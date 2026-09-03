package app.kernelpanic

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import java.util.Locale

class AlertController(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private var speechReady = false
    private val tts = TextToSpeech(appContext, this)
    private val tone = ToneGenerator(AudioManager.STREAM_ALARM, 90)

    override fun onInit(status: Int) {
        speechReady = status == TextToSpeech.SUCCESS
        if (speechReady) {
            speechReady = tts.setLanguage(Locale.getDefault()) >= TextToSpeech.LANG_AVAILABLE
        }
    }

    fun alert(words: String, critical: Boolean = false) {
        vibrate(critical)
        if (speechReady) {
            tts.speak(words, TextToSpeech.QUEUE_FLUSH, null, "kernel-panic-alert")
        } else {
            tone.startTone(if (critical) ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD else ToneGenerator.TONE_PROP_BEEP2, 900)
        }
    }

    fun stop() {
        tts.stop()
        tone.stopTone()
    }

    fun release() {
        stop()
        tts.shutdown()
        tone.release()
    }

    private fun vibrate(critical: Boolean) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        val pattern = if (critical) longArrayOf(0, 350, 120, 350, 120, 600) else longArrayOf(0, 250, 100, 450)
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }
}
