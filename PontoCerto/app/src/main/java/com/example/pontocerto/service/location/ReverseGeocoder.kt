package com.example.pontocerto.service.location

import android.content.Context
import android.location.Geocoder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Nome da rua/avenida do ponto atual (Geocoder do sistema, com fallback silencioso). */
class ReverseGeocoder(context: Context) {

    private val appContext = context.applicationContext
    private val geocoder: Geocoder? =
        if (Geocoder.isPresent()) Geocoder(appContext, Locale.forLanguageTag("pt-BR")) else null

    suspend fun nomeDaRua(latitude: Double, longitude: Double): String? = withContext(Dispatchers.IO) {
        val gc = geocoder ?: return@withContext null
        try {
            @Suppress("DEPRECATION")
            val enderecos = gc.getFromLocation(latitude, longitude, 1)
            enderecos?.firstOrNull()?.let { endereco ->
                val rua = endereco.thoroughfare?.takeIf { it.isNotBlank() }
                val numero = endereco.subThoroughfare
                    ?.substringBefore(" -")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                when {
                    rua != null && numero != null -> "$rua, $numero"
                    rua != null -> rua
                    else -> null
                }
            }
        } catch (e: Exception) {
            Log.w("ReverseGeocoder", "Falha ao geocodificar: ${e.message}")
            null
        }
    }
}
