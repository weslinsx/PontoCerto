package br.com.brtacessivel.data

import kotlin.math.*

enum class TipoParada(val raioDeteccaoMetros: Double) {
    TERMINAL_INTEGRACAO(100.0),
    ESTACAO_ILHA(80.0),
    ESTACAO_EXPRESSA_ALMIRANTE(80.0)
}

enum class SentidoRota {
    IDA_BELEM,       // Marituba (1) -> São Brás (19)
    VOLTA_MARITUBA,  // São Brás (19) -> Marituba (1)
    INDEFINIDO
}

data class EstacaoBrt(
    val id: String,
    val indiceBelem: Int,
    val codigoOperacional: String,
    val nome: String,
    val nomeAcessivel: String,
    val via: String,
    val tipo: TipoParada,
    val apenasExpressa: Boolean,
    val azimuteBelem: Double,
    val latitude: Double,
    val longitude: Double
)

object BrtEstacoesRepository {

    val estacoes: List<EstacaoBrt> = listOf(
        EstacaoBrt("brt_01_marituba", 1, "TERM_MARITUBA", "Terminal BRT Marituba", "Terminal B R T Marituba", "Rodovia BR-316", TipoParada.TERMINAL_INTEGRACAO, false, 246.0, -1.364510, -48.358610),
        EstacaoBrt("brt_02_uriboca", 2, "E1", "Estação Uriboca", "Estação Uriboca, plataforma E 1", "Rodovia BR-316", TipoParada.ESTACAO_ILHA, false, 245.0, -1.367950, -48.366420),
        EstacaoBrt("brt_03_ananindeua_centro", 3, "E2", "Estação Ananindeua Centro", "Estação Ananindeua Centro, plataforma E 2", "Rodovia BR-316", TipoParada.ESTACAO_ILHA, false, 245.0, -1.371840, -48.374890),
        EstacaoBrt("brt_04_aguas_brancas", 4, "E3", "Estação Águas Brancas", "Estação Águas Brancas, plataforma E 3", "Rodovia BR-316", TipoParada.ESTACAO_ILHA, false, 245.0, -1.375620, -48.383150),
        EstacaoBrt("brt_05_evandro_chagas", 5, "E4", "Estação Evandro Chagas", "Estação Evandro Chagas, plataforma E 4", "Rodovia BR-316", TipoParada.ESTACAO_ILHA, false, 240.0, -1.379410, -48.391380),
        EstacaoBrt("brt_06_terminal_ananindeua", 6, "TERM_ANANINDEUA", "Terminal BRT Ananindeua", "Terminal B R T Ananindeua", "Rodovia BR-316", TipoParada.TERMINAL_INTEGRACAO, false, 244.0, -1.384920, -48.400850),
        EstacaoBrt("brt_07_julia_seffer", 7, "E5", "Estação Coqueiro", "Estação Coqueiro, plataforma E 5", "Rodovia BR-316", TipoParada.ESTACAO_ILHA, false, 244.0, -1.388740, -48.408610),
        EstacaoBrt("brt_08_aguas_lindas", 8, "E6", "Estação Mário Covas", "Estação Mário Covas, plataforma E 6", "Rodovia BR-316", TipoParada.ESTACAO_ILHA, false, 244.0, -1.392480, -48.416290),
        EstacaoBrt("brt_09_parque_ambiental", 9, "E7", "Estação Parque Ambiental", "Estação Parque Ambiental, plataforma E 7", "Rodovia BR-316", TipoParada.ESTACAO_ILHA, false, 244.0, -1.395820, -48.423140),
        EstacaoBrt("brt_10_mario_covas", 10, "E8", "Estação Independência", "Estação Independência, plataforma E 8", "Rodovia BR-316", TipoParada.ESTACAO_ILHA, false, 244.0, -1.398650, -48.428920),
        EstacaoBrt("brt_11_jardim_tropical", 11, "E9", "Estação Guanabara", "Estação Guanabara, plataforma E 9", "Rodovia BR-316", TipoParada.ESTACAO_ILHA, false, 244.0, -1.400980, -48.433650),
        EstacaoBrt("brt_12_metropolitano", 12, "E10", "Estação Metropolitano", "Estação Hospital Metropolitano, plataforma E 10", "Rodovia BR-316", TipoParada.ESTACAO_ILHA, false, 244.0, -1.402410, -48.436580),
        EstacaoBrt("brt_13_guanabara", 13, "E11", "Estação Elevado Castanheira", "Estação antes do Elevado Castanheira, plataforma E 11", "Rodovia BR-316", TipoParada.ESTACAO_ILHA, false, 244.0, -1.403650, -48.439120),
        EstacaoBrt("brt_14_castanheira", 14, "E12", "Estação Castanheira", "Estação Shopping Castanheira, plataforma E 12", "Rodovia BR-316", TipoParada.ESTACAO_ILHA, false, 244.0, -1.404680, -48.441250),
        EstacaoBrt("brt_15_entroncamento", 15, "E13", "Estação Entroncamento", "Estação Entroncamento, plataforma E 13", "Complexo Viário do Entroncamento", TipoParada.ESTACAO_ILHA, false, 216.0, -1.406320, -48.444580),
        EstacaoBrt("brt_16_tavares_bastos", 16, "EXP_TAVARES", "Estação Tavares Bastos", "Estação Tavares Bastos", "Av. Almirante Barroso", TipoParada.ESTACAO_EXPRESSA_ALMIRANTE, true, 212.0, -1.412890, -48.449420),
        EstacaoBrt("brt_17_bosque", 17, "EXP_BOSQUE", "Estação Bosque", "Estação Bosque Rodrigues Alves", "Av. Almirante Barroso", TipoParada.ESTACAO_EXPRESSA_ALMIRANTE, true, 209.0, -1.428150, -48.458960),
        EstacaoBrt("brt_18_humaita", 18, "EXP_HUMAITA", "Estação Humaitá", "Estação Humaitá", "Av. Almirante Barroso", TipoParada.ESTACAO_EXPRESSA_ALMIRANTE, true, 199.0, -1.441820, -48.466410),
        EstacaoBrt("brt_19_sao_bras", 19, "TERM_SAO_BRAS", "Terminal São Brás", "Estação e Terminal São Brás", "Av. Almirante Barroso / São Brás", TipoParada.TERMINAL_INTEGRACAO, false, 199.0, -1.451680, -48.469450)
    )

    /** Calcula distância em metros entre duas coordenadas (Fórmula de Haversine) */
    fun calcularDistanciaMetros(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val raioTerra = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return raioTerra * c
    }

    /** Retorna a estação mais próxima e a distância em metros */
    fun encontrarMaisProxima(latUsuario: Double, lonUsuario: Double): Pair<EstacaoBrt, Int> {
        val maisProxima = estacoes.minBy {
            calcularDistanciaMetros(latUsuario, lonUsuario, it.latitude, it.longitude)
        }
        val distancia = calcularDistanciaMetros(latUsuario, lonUsuario, maisProxima.latitude, maisProxima.longitude).roundToInt()
        return Pair(maisProxima, distancia)
    }

    /**
     * Ordena os destinos para o Botão 2:
     * 1º: Slot Favorito 1 (se existir)
     * 2º: Slot Favorito 2 (se existir)
     * Demais: Da mais próxima para a mais distante, excluindo onde o usuário já está (<= raioDeteccaoMetros)
     */
    fun listarDestinosOrdenados(
        latUsuario: Double,
        lonUsuario: Double,
        favorito1Id: String?,
        favorito2Id: String?
    ): List<EstacaoBrt> {
        val favoritosIds = listOfNotNull(favorito1Id, favorito2Id).distinct()
        val favoritos = favoritosIds.mapNotNull { id -> estacoes.find { it.id == id } }

        val restantesPorDistancia = estacoes
            .filter { estacao ->
                estacao.id !in favoritosIds &&
                calcularDistanciaMetros(latUsuario, lonUsuario, estacao.latitude, estacao.longitude) > estacao.tipo.raioDeteccaoMetros
            }
            .sortedBy { estacao ->
                calcularDistanciaMetros(latUsuario, lonUsuario, estacao.latitude, estacao.longitude)
            }

        return favoritos + restantesPorDistancia
    }

    /** Calcula a menor diferença angular entre dois azimutes (0 a 180 graus) */
    private fun diferencaAngular(angulo1: Double, angulo2: Double): Double {
        val diff = abs(angulo1 - angulo2) % 360.0
        return if (diff > 180.0) 360.0 - diff else diff
    }

    /**
     * Infere o sentido combinando:
     * 1. Sequência entre duas estações visitadas (mais confiável)
     * 2. Rumo do GPS (bearing) comparado ao azimute da via (funciona já no 1º trecho)
     * 3. Índice do destino escolhido pelo usuário
     */
    fun inferirSentido(
        estacaoAnterior: EstacaoBrt?,
        estacaoAtual: EstacaoBrt,
        destinoSelecionado: EstacaoBrt?,
        gpsBearing: Float? = null,
        velocidadeMs: Float = 0f
    ): SentidoRota {
        if (estacaoAnterior != null && estacaoAnterior.id != estacaoAtual.id) {
            return if (estacaoAtual.indiceBelem > estacaoAnterior.indiceBelem) {
                SentidoRota.IDA_BELEM
            } else {
                SentidoRota.VOLTA_MARITUBA
            }
        }

        // Se o ônibus já está em movimento (> 12 km/h = 3.33 m/s) e há bearing válido
        if (gpsBearing != null && velocidadeMs > 3.33f) {
            val diffIda = diferencaAngular(gpsBearing.toDouble(), estacaoAtual.azimuteBelem)
            val azimuteVolta = (estacaoAtual.azimuteBelem + 180.0) % 360.0
            val diffVolta = diferencaAngular(gpsBearing.toDouble(), azimuteVolta)

            if (diffIda <= 40.0) return SentidoRota.IDA_BELEM
            if (diffVolta <= 40.0) return SentidoRota.VOLTA_MARITUBA
        }

        if (destinoSelecionado != null && destinoSelecionado.id != estacaoAtual.id) {
            return if (destinoSelecionado.indiceBelem > estacaoAtual.indiceBelem) {
                SentidoRota.IDA_BELEM
            } else {
                SentidoRota.VOLTA_MARITUBA
            }
        }

        return SentidoRota.INDEFINIDO
    }

    /** Calcula próxima parada e quantas estações faltam até o destino */
    fun calcularProgresso(
        estacaoAtual: EstacaoBrt,
        sentido: SentidoRota,
        destino: EstacaoBrt?
    ): Pair<EstacaoBrt?, Int?> {
        val proxima = when (sentido) {
            SentidoRota.IDA_BELEM -> estacoes.find { it.indiceBelem == estacaoAtual.indiceBelem + 1 }
            SentidoRota.VOLTA_MARITUBA -> estacoes.find { it.indiceBelem == estacaoAtual.indiceBelem - 1 }
            SentidoRota.INDEFINIDO -> null
        }

        val paradasRestantes = if (destino != null) {
            abs(destino.indiceBelem - estacaoAtual.indiceBelem)
        } else {
            null
        }

        return Pair(proxima, paradasRestantes)
    }
}