package com.example.pontocerto.service.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class IotTelemetrySnapshot(
    val esp32Connected: Boolean,
    val device: String,
    val busId: String,
    val busType: String,
    val busCode: String,
    val busName: String,
    val corridor: String,
    val rssiDbm: Int,
    val distanceMeters: Float,
    val proximity: String,
    val ssidBeacon: String,
    val seq: Int
)

class IotTelemetryClient {

    private val baseUrls = listOf(
        "http://192.168.240.1:8765",
        "http://127.0.0.1:8765",
        "http://192.168.1.10:8765"
    )

    suspend fun fetchTelemetry(): IotTelemetrySnapshot? = withContext(Dispatchers.IO) {
        for (base in baseUrls) {
            try {
                val conn = (URL("$base/api/telemetry").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 900
                    readTimeout = 900
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    return@withContext IotTelemetrySnapshot(
                        esp32Connected = json.optBoolean("esp32_connected", false),
                        device = json.optString("device", "ESP32"),
                        busId = json.optString("bus_id", ""),
                        busType = json.optString("bus_type", "URBAN"),
                        busCode = json.optString("bus_code", "639"),
                        busName = json.optString("bus_name", "Arsenal"),
                        corridor = json.optString("corridor", ""),
                        rssiDbm = json.optInt("rssi_dbm", -70),
                        distanceMeters = json.optDouble("distance_m", 50.0).toFloat(),
                        proximity = json.optString("proximity", "FAR"),
                        ssidBeacon = json.optString("ssid_beacon", ""),
                        seq = json.optInt("seq", 0)
                    )
                }
            } catch (_: Exception) {
                // Tenta o próximo endpoint local
            }
        }
        null
    }

    /**
     * Envia ao Servidor Python qual ônibus/estação o usuário selecionou no APK
     * para que o ESP32 se molde automaticamente a essa linha.
     */
    suspend fun pushSelectedBusToEsp32(
        busType: String,
        busCode: String,
        busName: String,
        corridor: String
    ): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("action", "set_apk_bus")
            put("bus_type", busType)
            put("bus_code", busCode)
            put("bus_name", busName)
            put("corridor", corridor)
        }.toString()

        for (base in baseUrls) {
            try {
                val conn = (URL("$base/api/control").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 900
                    readTimeout = 900
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                conn.outputStream.use { out ->
                    out.write(payload.toByteArray(Charsets.UTF_8))
                }
                if (conn.responseCode == 200) {
                    return@withContext true
                }
            } catch (_: Exception) {
                // Tenta o próximo endpoint local
            }
        }
        false
    }
}
