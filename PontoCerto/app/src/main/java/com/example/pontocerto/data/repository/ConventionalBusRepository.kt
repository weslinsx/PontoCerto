package com.example.pontocerto.data.repository

import com.example.pontocerto.data.model.BusLine
import com.example.pontocerto.data.model.BusRadioSignalStrength
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

class ConventionalBusRepository {

    val popularDestinations: List<String> = listOf(
        "Ver-o-Peso",
        "Shopping Pátio Belém",
        "Praça da República",
        "UFPA",
        "São Brás",
        "Castanheira",
        "Basílica de Nazaré",
        "Icoaraci"
    )

    val lines: List<BusLine> = listOf(
        BusLine(
            id = "639_arsenal",
            code = "639",
            name = "Arsenal • Pres. Vargas",
            origin = "Arsenal (Cidade Velha)",
            destination = "Presidente Vargas / Ver-o-Peso",
            description = "Passa por Rua dos Mundurucus, Av. Nazaré, Praça da República e Ver-o-Peso",
            keyDestinations = listOf(
                "Arsenal",
                "Cidade Velha",
                "Mundurucus",
                "Nazaré",
                "Praça da República",
                "Presidente Vargas",
                "Ver-o-Peso"
            )
        ),
        BusLine(
            id = "638_marex",
            code = "638",
            name = "Marex • Arsenal",
            origin = "Terminal Marex",
            destination = "Presidente Vargas / Ver-o-Peso",
            description = "Passa por Av. Júlio César, Alm. Barroso, Av. Nazaré, Shopping Pátio Belém e Presidente Vargas",
            keyDestinations = listOf(
                "Ver-o-Peso",
                "Presidente Vargas",
                "Shopping Pátio Belém",
                "Shopping Boulevard",
                "Basílica de Nazaré",
                "Nazaré",
                "Júlio César",
                "Arsenal",
                "São Brás",
                "Comércio"
            )
        ),
        BusLine(
            id = "548_marambaia",
            code = "548",
            name = "Marambaia • Ver-o-Peso",
            origin = "Marambaia",
            destination = "Ver-o-Peso",
            description = "Passa por Tavares Bastos, Alm. Barroso, São Brás, Centro Comercial e Ver-o-Peso",
            keyDestinations = listOf(
                "Ver-o-Peso",
                "Centro Comercial",
                "Almirante Barroso",
                "São Brás",
                "Tavares Bastos",
                "Marambaia",
                "Mercado de São Brás"
            )
        ),
        BusLine(
            id = "226_canudos",
            code = "226",
            name = "Canudos • Presidente Vargas",
            origin = "Canudos",
            destination = "Praça da República",
            description = "Passa por Av. Ceará, Alm. Barroso, São Brás, Av. Gov. José Malcher e Praça da República",
            keyDestinations = listOf(
                "Praça da República",
                "Theatro da Paz",
                "Presidente Vargas",
                "José Malcher",
                "São Brás",
                "Canudos",
                "Ceará"
            )
        ),
        BusLine(
            id = "306_ufpa",
            code = "306",
            name = "UFPA • Padre Eutíquio",
            origin = "Terminal Universitário UFPA",
            destination = "Comércio / Padre Eutíquio",
            description = "Passa por Campus UFPA, Perimetral, Conselheiro Furtado, Shopping Pátio Belém e Batista Campos",
            keyDestinations = listOf(
                "UFPA",
                "Universidade Federal",
                "Shopping Pátio Belém",
                "Padre Eutíquio",
                "Conselheiro Furtado",
                "Perimetral",
                "Batista Campos",
                "Comércio"
            )
        ),
        BusLine(
            id = "758_maguari",
            code = "758",
            name = "Conjunto Maguari • Ver-o-Peso",
            origin = "Conjunto Maguari",
            destination = "Ver-o-Peso",
            description = "Passa por Rod. Augusto Montenegro, Parque Shopping, Entroncamento, Castanheira, Alm. Barroso e Ver-o-Peso",
            keyDestinations = listOf(
                "Ver-o-Peso",
                "Augusto Montenegro",
                "Parque Shopping",
                "Entroncamento",
                "Castanheira",
                "Almirante Barroso",
                "São Brás",
                "Maguari"
            )
        ),
        BusLine(
            id = "872_icoaraci",
            code = "872",
            name = "Icoaraci • Almirante Barroso",
            origin = "Terminal de Icoaraci",
            destination = "São Brás",
            description = "Passa por Icoaraci, Rodovia Augusto Montenegro, Mangueirão, Tapanã, Castanheira e São Brás",
            keyDestinations = listOf(
                "Icoaraci",
                "Orla de Icoaraci",
                "Augusto Montenegro",
                "Mangueirão",
                "Estádio Mangueirão",
                "Tapanã",
                "Castanheira",
                "São Brás",
                "Almirante Barroso"
            )
        )
    )

    fun findLineById(id: String): BusLine? = lines.find { it.id == id }

    fun findLinesForDestination(query: String): List<BusLine> {
        if (query.isBlank()) return emptyList()
        return lines.filter { it.matchesDestination(query) }
    }

    /**
     * Calcula o RSSI (dBm) a partir da distância em metros usando o modelo de propagação log-distance.
     * TxPower calibrado em -45 dBm a 1 metro.
     * Coeficiente n = 2.4 (ambiente urbano com obstáculos leves).
     */
    fun calculateRssiFromDistance(distanceMeters: Float): Int {
        val dist = distanceMeters.coerceAtLeast(1.0f)
        val txPower = -45.0
        val n = 2.4
        val rssi = txPower - (10 * n * log10(dist.toDouble()))
        return rssi.roundToInt().coerceIn(-95, -35)
    }

    /**
     * Calcula a distância aproximada em metros a partir do RSSI em dBm.
     */
    fun calculateDistanceFromRssi(rssi: Int): Float {
        val txPower = -45.0
        val n = 2.4
        val exponent = (txPower - rssi) / (10 * n)
        return (10.0.pow(exponent)).toFloat()
    }

    fun getSignalStrength(rssi: Int): BusRadioSignalStrength {
        return when {
            rssi >= -68 -> BusRadioSignalStrength.STRONG   // Menos de 35 metros (No Ponto)
            rssi >= -82 -> BusRadioSignalStrength.MEDIUM   // Entre 35 e 140 metros (Aproximando)
            rssi >= -95 -> BusRadioSignalStrength.WEAK     // Mais de 140 metros (Longe)
            else -> BusRadioSignalStrength.NONE
        }
    }
}
