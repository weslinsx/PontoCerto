package com.example.pontocerto.data.model

import java.text.Normalizer

data class BusLine(
    val id: String,
    val code: String,
    val name: String,
    val origin: String,
    val destination: String,
    val description: String = "",
    val keyDestinations: List<String> = emptyList()
) {
    val fullDisplayName: String
        get() = "$code - $name"

    fun matchesDestination(query: String): Boolean {
        if (query.isBlank()) return false
        val cleanQuery = query.normalizeForSearch()

        val textToSearch = buildString {
            append(code)
            append(" ")
            append(name)
            append(" ")
            append(origin)
            append(" ")
            append(destination)
            append(" ")
            append(description)
            keyDestinations.forEach { dest ->
                append(" ")
                append(dest)
            }
        }.normalizeForSearch()

        // 1. Correspondência direta da frase completa
        if (textToSearch.contains(cleanQuery)) return true

        // 2. Termos significativos (remove pontuação, stop words e palavras com <= 2 letras)
        val stopWords = setOf("de", "do", "da", "dos", "das", "em", "para", "no", "na", "ao", "aos", "um", "uma")
        val queryTokens = cleanQuery
            .split(Regex("[\\s\\-_/]+"))
            .filter { it.length > 2 && it !in stopWords }

        if (queryTokens.isEmpty()) {
            return textToSearch.contains(cleanQuery)
        }

        // Todos os termos relevantes da busca devem estar presentes no itinerário da linha
        return queryTokens.all { token -> textToSearch.contains(token) }
    }


    private fun String.normalizeForSearch(): String {
        return Normalizer.normalize(this, Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .lowercase()
            .trim()
    }
}

enum class BusRadioSignalStrength {
    NONE,
    WEAK,     // < -80 dBm (> 150m)
    MEDIUM,   // -65 dBm a -80 dBm (50m a 150m - APROXIMANDO)
    STRONG    // > -65 dBm (< 50m - NO PONTO)
}

data class ActiveBusStatus(
    val line: BusLine,
    val distanceMeters: Float,
    val rssiRdioDbm: Int, // Ex: -45 dBm a -90 dBm
    val proximityStatus: ProximityStatus,
    val signalStrength: BusRadioSignalStrength
)
