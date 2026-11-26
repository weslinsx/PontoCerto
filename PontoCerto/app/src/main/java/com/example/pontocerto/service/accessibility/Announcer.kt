package com.example.pontocerto.service.accessibility

import android.content.Context
import com.example.pontocerto.service.tts.TtsManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Canal único de fala. Com TalkBack ativo nada é enviado ao TextToSpeech:
 * o texto vira evento de acessibilidade (announceForAccessibility) na tela.
 * Sem TalkBack, o TTS interno assume como fallback.
 */
class Announcer(
    context: Context,
    private val accessibilityStatus: AccessibilityStatus,
    private val ttsManager: TtsManager
) {
    private val _anuncios = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val anuncios: SharedFlow<String> = _anuncios.asSharedFlow()

    fun anunciar(texto: String) {
        if (texto.isBlank()) return
        android.util.Log.i("PontoCerto", "anuncio: $texto")
        if (accessibilityStatus.talkBackAtivo()) {
            _anuncios.tryEmit(texto)
        } else {
            ttsManager.speak(texto)
        }
    }

    fun interromper() {
        if (!accessibilityStatus.talkBackAtivo()) ttsManager.stop()
    }

    fun encerrar() = ttsManager.shutdown()
}
