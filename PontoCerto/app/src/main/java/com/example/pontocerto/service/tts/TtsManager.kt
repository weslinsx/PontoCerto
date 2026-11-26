package com.example.pontocerto.service.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Fallback de voz usado só quando o TalkBack está desligado.
 * O motor é criado sob demanda: com TalkBack ativo ele nunca é inicializado.
 */
class TtsManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var textToSpeech: TextToSpeech? = null
    private var isInitialized: Boolean = false
    private var pendingSpeech: String? = null

    private fun garantirMotor() {
        if (textToSpeech == null) {
            textToSpeech = TextToSpeech(context.applicationContext, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            if (textToSpeech?.setLanguage(Locale.forLanguageTag("pt-BR")) == TextToSpeech.LANG_MISSING_DATA) {
                textToSpeech?.setLanguage(Locale.forLanguageTag("pt"))
            }
            textToSpeech?.setSpeechRate(1.0f)
            textToSpeech?.setPitch(1.0f)
            isInitialized = true
            pendingSpeech?.let {
                speak(it)
                pendingSpeech = null
            }
        } else {
            Log.e("TtsManager", "Falha ao inicializar TTS (código $status)")
        }
    }

    fun speak(text: String, flush: Boolean = true) {
        garantirMotor()
        if (!isInitialized) {
            pendingSpeech = text
            return
        }
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        textToSpeech?.speak(text, queueMode, null, "PontoCerto_${System.currentTimeMillis()}")
    }

    fun stop() {
        if (isInitialized) textToSpeech?.stop()
    }

    fun shutdown() {
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (e: Exception) {
            Log.e("TtsManager", "Erro ao encerrar TTS", e)
        }
        textToSpeech = null
        isInitialized = false
    }
}
