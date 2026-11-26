package com.example.pontocerto.data.repository

import com.example.pontocerto.data.model.BrtStation
import com.example.pontocerto.data.model.SentidoRota
import com.example.pontocerto.data.model.TipoParada
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Corredor BRT Metropolitano: Terminal Marituba (1) -> Terminal São Brás (19).
 * As estações-ilha da BR-316 atendem ida e volta no mesmo ponto físico.
 */
class BrtStationsRepository {

    val stations: List<BrtStation> = listOf(
        BrtStation("brt_01_marituba", "Terminal BRT Marituba", -1.364510, -48.358610, 1, "Rodovia BR-316", "TERM_MARITUBA", "Terminal B R T Marituba", TipoParada.TERMINAL_INTEGRACAO, false, 246.0),
        BrtStation("brt_02_uriboca", "Estação Uriboca", -1.367950, -48.366420, 2, "Rodovia BR-316", "E1", "Estação Uriboca, plataforma E 1", TipoParada.ESTACAO_ILHA, false, 245.0),
        BrtStation("brt_03_ananindeua_centro", "Estação Ananindeua Centro", -1.371840, -48.374890, 3, "Rodovia BR-316", "E2", "Estação Ananindeua Centro, plataforma E 2", TipoParada.ESTACAO_ILHA, false, 245.0),
        BrtStation("brt_04_aguas_brancas", "Estação Águas Brancas", -1.375620, -48.383150, 4, "Rodovia BR-316", "E3", "Estação Águas Brancas, plataforma E 3", TipoParada.ESTACAO_ILHA, false, 245.0),
        BrtStation("brt_05_evandro_chagas", "Estação Evandro Chagas", -1.379410, -48.391380, 5, "Rodovia BR-316", "E4", "Estação Evandro Chagas, plataforma E 4", TipoParada.ESTACAO_ILHA, false, 240.0),
        BrtStation("brt_06_terminal_ananindeua", "Terminal BRT Ananindeua", -1.384920, -48.400850, 6, "Rodovia BR-316", "TERM_ANANINDEUA", "Terminal B R T Ananindeua", TipoParada.TERMINAL_INTEGRACAO, false, 244.0),
        BrtStation("brt_07_julia_seffer", "Estação Júlia Seffer", -1.388740, -48.408610, 7, "Rodovia BR-316", "E5", "Estação Júlia Seffer, plataforma E 5", TipoParada.ESTACAO_ILHA, false, 244.0),
        BrtStation("brt_08_aguas_lindas", "Estação Águas Lindas", -1.392480, -48.416290, 8, "Rodovia BR-316", "E6", "Estação Águas Lindas, plataforma E 6", TipoParada.ESTACAO_ILHA, false, 244.0),
        BrtStation("brt_09_parque_ambiental", "Estação Parque Ambiental", -1.395820, -48.423140, 9, "Rodovia BR-316", "E7", "Estação Parque Ambiental, plataforma E 7", TipoParada.ESTACAO_ILHA, false, 244.0),
        BrtStation("brt_10_mario_covas", "Estação Mário Covas", -1.398650, -48.428920, 10, "Rodovia BR-316", "E8", "Estação Mário Covas, plataforma E 8", TipoParada.ESTACAO_ILHA, false, 244.0),
        BrtStation("brt_11_jardim_tropical", "Estação Jardim Tropical", -1.400980, -48.433650, 11, "Rodovia BR-316", "E9", "Estação Jardim Tropical, plataforma E 9", TipoParada.ESTACAO_ILHA, false, 244.0),
        BrtStation("brt_12_metropolitano", "Estação Metropolitano", -1.402410, -48.436580, 12, "Rodovia BR-316", "E10", "Estação Hospital Metropolitano, plataforma E 10", TipoParada.ESTACAO_ILHA, false, 244.0),
        BrtStation("brt_13_guanabara", "Estação Guanabara", -1.403650, -48.439120, 13, "Rodovia BR-316", "E11", "Estação Guanabara, plataforma E 11", TipoParada.ESTACAO_ILHA, false, 244.0),
        BrtStation("brt_14_castanheira", "Estação Castanheira", -1.404680, -48.441250, 14, "Rodovia BR-316", "E12", "Estação Shopping Castanheira, plataforma E 12", TipoParada.ESTACAO_ILHA, false, 244.0),
        BrtStation("brt_15_entroncamento", "Estação Entroncamento", -1.406320, -48.444580, 15, "Complexo Viário do Entroncamento", "E13", "Estação Entroncamento, plataforma E 13", TipoParada.ESTACAO_ILHA, false, 216.0),
        BrtStation("brt_16_tavares_bastos", "Estação Tavares Bastos", -1.412890, -48.449420, 16, "Av. Almirante Barroso", "EXP_TAVARES", "Estação Tavares Bastos", TipoParada.ESTACAO_EXPRESSA_ALMIRANTE, true, 212.0),
        BrtStation("brt_17_bosque", "Estação Bosque", -1.428150, -48.458960, 17, "Av. Almirante Barroso", "EXP_BOSQUE", "Estação Bosque Rodrigues Alves", TipoParada.ESTACAO_EXPRESSA_ALMIRANTE, true, 209.0),
        BrtStation("brt_18_humaita", "Estação Humaitá", -1.441820, -48.466410, 18, "Av. Almirante Barroso", "EXP_HUMAITA", "Estação Humaitá", TipoParada.ESTACAO_EXPRESSA_ALMIRANTE, true, 199.0),
        BrtStation("brt_19_sao_bras", "Terminal São Brás", -1.451680, -48.469450, 19, "Av. Almirante Barroso / São Brás", "TERM_SAO_BRAS", "Estação e Terminal São Brás", TipoParada.TERMINAL_INTEGRACAO, false, 199.0)
    )

    // ==========================================
    // API original (mantida para a UI existente)
    // ==========================================

    fun findStationById(id: String?): BrtStation? = if (id == null) null else stations.find { it.id == id }

    fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float =
        distanciaMetros(lat1, lon1, lat2, lon2)

    fun findNearestStation(latitude: Double, longitude: Double): Pair<BrtStation, Float>? {
        if (stations.isEmpty()) return null
        val maisProxima = maisProxima(latitude, longitude)
        return maisProxima
    }

    fun getNextStation(currentSequence: Int, directionForward: Boolean = true): BrtStation? {
        val alvo = if (directionForward) currentSequence + 1 else currentSequence - 1
        return stations.find { it.sequence == alvo }
    }

    // ==========================================
    // Geofencing / rotas
    // ==========================================

    fun stationByIndex(indice: Int): BrtStation? = stations.find { it.sequence == indice }

    fun stationById(id: String?): BrtStation? = if (id == null) null else findStationById(id)

    /** Distância em metros entre duas coordenadas (Haversine). */
    fun distanciaMetros(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val raioTerra = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return (raioTerra * 2 * atan2(sqrt(a), sqrt(1 - a))).toFloat()
    }

    fun maisProxima(latitude: Double, longitude: Double): Pair<BrtStation, Float> =
        stations.minBy { distanciaMetros(latitude, longitude, it.latitude, it.longitude) }
            .let { it to distanciaMetros(latitude, longitude, it.latitude, it.longitude) }

    /** Menor distância (metros) do ponto até a linha formada pelas estações consecutivas. */
    fun distanciaAoCorredor(latitude: Double, longitude: Double): Float =
        stations.zipWithNext().minOf { (a, b) ->
            distanciaPontoSegmento(latitude, longitude, a.latitude, a.longitude, b.latitude, b.longitude)
        }

    fun dentroDoCorredor(latitude: Double, longitude: Double, toleranciaMetros: Float = 250f): Boolean =
        distanciaAoCorredor(latitude, longitude) <= toleranciaMetros

    fun estacaoAtual(latitude: Double, longitude: Double): BrtStation? {
        val (estacao, distancia) = maisProxima(latitude, longitude)
        return if (distancia <= estacao.tipo.raioDeteccaoMetros) estacao else null
    }

    fun inferirSentido(
        estacaoAnterior: BrtStation?,
        estacaoAtual: BrtStation,
        destino: BrtStation?,
        gpsBearing: Float?,
        velocidadeMs: Float
    ): SentidoRota {
        if (estacaoAnterior != null && estacaoAnterior.id != estacaoAtual.id) {
            return if (estacaoAtual.sequence > estacaoAnterior.sequence) {
                SentidoRota.IDA_BELEM
            } else {
                SentidoRota.VOLTA_MARITUBA
            }
        }
        if (gpsBearing != null && velocidadeMs > 3.33f) {
            val diffIda = diferencaAngular(gpsBearing.toDouble(), estacaoAtual.azimuteBelem)
            val diffVolta = diferencaAngular(gpsBearing.toDouble(), (estacaoAtual.azimuteBelem + 180.0) % 360.0)
            if (diffIda <= 40.0) return SentidoRota.IDA_BELEM
            if (diffVolta <= 40.0) return SentidoRota.VOLTA_MARITUBA
        }
        if (destino != null && destino.id != estacaoAtual.id) {
            return if (destino.sequence > estacaoAtual.sequence) SentidoRota.IDA_BELEM else SentidoRota.VOLTA_MARITUBA
        }
        return SentidoRota.INDEFINIDO
    }

    fun proximaParada(atual: BrtStation, sentido: SentidoRota): BrtStation? = when (sentido) {
        SentidoRota.IDA_BELEM -> stationByIndex(atual.sequence + 1)
        SentidoRota.VOLTA_MARITUBA -> stationByIndex(atual.sequence - 1)
        SentidoRota.INDEFINIDO -> null
    }

    fun paradasAte(destino: BrtStation, atual: BrtStation): Int =
        abs(destino.sequence - atual.sequence)

    fun destinosOrdenados(
        latitude: Double?,
        longitude: Double?,
        favorito1Id: String?
    ): List<BrtStation> {
        val favorito = stationById(favorito1Id)
        val restantes = stations.filter { it.id != favorito?.id }
        if (latitude == null || longitude == null) return listOfNotNull(favorito) + restantes
        return listOfNotNull(favorito) + restantes.sortedBy {
            distanciaMetros(latitude, longitude, it.latitude, it.longitude)
        }
    }

    private fun diferencaAngular(a: Double, b: Double): Double {
        val diff = abs(a - b) % 360.0
        return if (diff > 180.0) 360.0 - diff else diff
    }

    private fun distanciaPontoSegmento(
        latP: Double, lonP: Double,
        latA: Double, lonA: Double,
        latB: Double, lonB: Double
    ): Float {
        val metrosPorGrau = 111320.0
        val cosLat = cos(Math.toRadians(latP))
        val px = (lonP - lonA) * metrosPorGrau * cosLat
        val py = (latP - latA) * metrosPorGrau
        val bx = (lonB - lonA) * metrosPorGrau * cosLat
        val by = (latB - latA) * metrosPorGrau
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val dx = px - bx * t
        val dy = py - by * t
        return sqrt(dx * dx + dy * dy).toFloat()
    }
}
