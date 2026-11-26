package com.example.pontocerto.data.model

enum class TipoParada(val raioDeteccaoMetros: Float) {
    TERMINAL_INTEGRACAO(100f),
    ESTACAO_ILHA(80f),
    ESTACAO_EXPRESSA_ALMIRANTE(80f)
}

enum class SentidoRota {
    IDA_BELEM,       // Marituba (1) -> São Brás (19)
    VOLTA_MARITUBA,  // São Brás (19) -> Marituba (1)
    INDEFINIDO
}

data class BrtStation(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val sequence: Int,
    val corridor: String = "BRT Metropolitano",
    val codigoOperacional: String = "",
    val nomeAcessivel: String = name,
    val tipo: TipoParada = TipoParada.ESTACAO_ILHA,
    val apenasExpressa: Boolean = false,
    val azimuteBelem: Double = 0.0
) {
    val indiceBelem: Int get() = sequence
    val via: String get() = corridor
}

enum class ProximityStatus {
    FAR,
    APPROACHING,
    ARRIVED
}

data class TripNavigationState(
    val currentStation: BrtStation? = null,
    val nextStation: BrtStation? = null,
    val destinationStation: BrtStation? = null,
    val distanceToNextMeters: Float = 0f,
    val distanceToDestinationMeters: Float = 0f,
    val proximityStatus: ProximityStatus = ProximityStatus.FAR,
    val lastAnnouncement: String = ""
)
