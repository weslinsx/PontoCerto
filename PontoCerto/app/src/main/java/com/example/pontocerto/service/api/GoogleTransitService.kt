package com.example.pontocerto.service.api

import android.content.Context
import android.util.Log
import com.example.pontocerto.data.model.BusLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class GoogleTransitOption(
    val busLine: BusLine,
    val departureStopName: String,
    val arrivalStopName: String,
    val headsign: String,
    val stopCount: Int,
    val durationMinutes: Int
)

class GoogleTransitService(private val context: Context) {

    private val apiKey = "AIzaSyCRvbiLfMzELJ-5V3xY0r9EAirwDxVGOBE"
    private val packageName = "com.example.pontocerto"
    private val sha1Fingerprint = "A22D285F6E97C29A14559EEC931A9B3C4B24765D"

    /**
     * Busca coordenadas de um endereço em Belém usando a Google Geocoding API.
     */
    suspend fun geocodeAddress(address: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        try {
            val queryWithCity = if (!address.lowercase().contains("belem") && !address.lowercase().contains("belém")) {
                "$address, Belém, PA"
            } else {
                address
            }
            val encoded = URLEncoder.encode(queryWithCity, "UTF-8")
            val url = URL("https://maps.googleapis.com/maps/api/geocode/json?address=$encoded&key=$apiKey")

            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("X-Android-Package", packageName)
                setRequestProperty("X-Android-Cert", sha1Fingerprint)
            }

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.readText()
                reader.close()

                val json = JSONObject(response)
                if (json.optString("status") == "OK") {
                    val results = json.getJSONArray("results")
                    if (results.length() > 0) {
                        val location = results.getJSONObject(0)
                            .getJSONObject("geometry")
                            .getJSONObject("location")
                        val lat = location.getDouble("lat")
                        val lng = location.getDouble("lng")
                        return@withContext Pair(lat, lng)
                    }
                } else {
                    Log.w("GoogleTransit", "Geocoding status: ${json.optString("status")}")
                }
            } else {
                Log.e("GoogleTransit", "Geocoding HTTP error: $responseCode")
            }
        } catch (e: Exception) {
            Log.e("GoogleTransit", "Erro ao geocodificar endereço", e)
        }
        null
    }

    /**
     * Consulta a Google Routes API (v2) para calcular trajetos com transporte público (mode=TRANSIT).
     */
    suspend fun fetchTransitRoutes(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double
    ): List<GoogleTransitOption> = withContext(Dispatchers.IO) {
        val resultList = mutableListOf<GoogleTransitOption>()
        try {
            val url = URL("https://routes.googleapis.com/directions/v2:computeRoutes")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Goog-Api-Key", apiKey)
                setRequestProperty("X-Android-Package", packageName)
                setRequestProperty("X-Android-Cert", sha1Fingerprint)
                setRequestProperty(
                    "X-Goog-FieldMask",
                    "routes.legs.steps.transitDetails,routes.legs.steps.travelMode,routes.duration,routes.distanceMeters"
                )
            }

            val requestJson = JSONObject().apply {
                put("origin", JSONObject().apply {
                    put("location", JSONObject().apply {
                        put("latLng", JSONObject().apply {
                            put("latitude", originLat)
                            put("longitude", originLng)
                        })
                    })
                })
                put("destination", JSONObject().apply {
                    put("location", JSONObject().apply {
                        put("latLng", JSONObject().apply {
                            put("latitude", destLat)
                            put("longitude", destLng)
                        })
                    })
                })
                put("travelMode", "TRANSIT")
                put("computeAlternativeRoutes", true)
            }

            val writer = OutputStreamWriter(conn.outputStream)
            writer.write(requestJson.toString())
            writer.flush()
            writer.close()

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.readText()
                reader.close()

                val root = JSONObject(response)
                val routes = root.optJSONArray("routes")
                if (routes != null) {
                    for (i in 0 until routes.length()) {
                        val route = routes.getJSONObject(i)
                        val durationSeconds = route.optString("duration", "0s").replace("s", "").toIntOrNull() ?: 0
                        val durationMinutes = durationSeconds / 60

                        val legs = route.optJSONArray("legs") ?: continue
                        for (j in 0 until legs.length()) {
                            val leg = legs.getJSONObject(j)
                            val steps = leg.optJSONArray("steps") ?: continue

                            for (k in 0 until steps.length()) {
                                val step = steps.getJSONObject(k)
                                val travelMode = step.optString("travelMode")
                                if (travelMode == "TRANSIT") {
                                    val transitDetails = step.optJSONObject("transitDetails") ?: continue
                                    val transitLine = transitDetails.optJSONObject("transitLine") ?: continue
                                    val stopDetails = transitDetails.optJSONObject("stopDetails") ?: continue

                                    val lineCode = transitLine.optString("nameShort").ifBlank {
                                        transitLine.optString("name")
                                    }
                                    val lineName = transitLine.optString("name", "Linha Urbana")
                                    val headsign = transitDetails.optString("headsign", "")
                                    val stopCount = transitDetails.optInt("stopCount", 0)

                                    val departureStop = stopDetails.optJSONObject("departureStop")?.optString("name", "Parada mais próxima") ?: "Parada mais próxima"
                                    val arrivalStop = stopDetails.optJSONObject("arrivalStop")?.optString("name", "Parada de destino") ?: "Parada de destino"

                                    val busLine = BusLine(
                                        id = "google_${lineCode.replace(" ", "_")}_$k",
                                        code = lineCode,
                                        name = lineName,
                                        origin = departureStop,
                                        destination = arrivalStop,
                                        description = if (headsign.isNotBlank()) "Sentido: $headsign • $stopCount paradas" else "$stopCount paradas até o destino",
                                        keyDestinations = listOf(arrivalStop, headsign)
                                    )

                                    val option = GoogleTransitOption(
                                        busLine = busLine,
                                        departureStopName = departureStop,
                                        arrivalStopName = arrivalStop,
                                        headsign = headsign,
                                        stopCount = stopCount,
                                        durationMinutes = durationMinutes
                                    )

                                    // Evita duplicar a mesma linha no resultado
                                    if (resultList.none { it.busLine.code == busLine.code }) {
                                        resultList.add(option)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                val errorStream = conn.errorStream
                val errText = if (errorStream != null) BufferedReader(InputStreamReader(errorStream)).readText() else ""
                Log.e("GoogleTransit", "Routes API error ($responseCode): $errText")
            }
        } catch (e: Exception) {
            Log.e("GoogleTransit", "Erro ao consultar Google Routes API", e)
        }
        resultList
    }
}
