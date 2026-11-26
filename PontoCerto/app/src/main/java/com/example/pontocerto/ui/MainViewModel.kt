package com.example.pontocerto.ui

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.pontocerto.data.model.ActiveBusStatus
import com.example.pontocerto.data.model.BrtStation
import com.example.pontocerto.data.model.BusLine
import com.example.pontocerto.data.model.BusRadioSignalStrength
import com.example.pontocerto.data.model.ProximityStatus
import com.example.pontocerto.data.model.SentidoRota
import com.example.pontocerto.data.repository.BrtStationsRepository
import com.example.pontocerto.data.repository.ConventionalBusRepository
import com.example.pontocerto.service.accessibility.AccessibilityStatus
import com.example.pontocerto.service.accessibility.Announcer
import com.example.pontocerto.service.api.GoogleTransitOption
import com.example.pontocerto.service.api.GoogleTransitService
import com.example.pontocerto.service.api.IotTelemetryClient
import com.example.pontocerto.service.api.IotTelemetrySnapshot
import com.example.pontocerto.service.haptics.HapticManager
import com.example.pontocerto.service.location.LocationTracker
import com.example.pontocerto.service.tts.TtsManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
enum class AppTab {
    BRT_CORRIDOR,
    URBAN_BUSES
}

data class UiState(
    val currentTab: AppTab = AppTab.BRT_CORRIDOR,

    // ABA 1: BRT
    val stations: List<BrtStation> = emptyList(),
    val selectedDestination: BrtStation? = null,
    val userLatitude: Double? = null,
    val userLongitude: Double? = null,
    val nearestStation: BrtStation? = null,
    val distanceToNearestMeters: Float = 0f,
    val nextStation: BrtStation? = null,
    val distanceToNextMeters: Float = 0f,
    val distanceToDestinationMeters: Float? = null,
    val proximityStatus: ProximityStatus = ProximityStatus.FAR,
    val isTrackingActive: Boolean = false,
    val isSimulating: Boolean = false,
    val simulationIndex: Int = 0,

    // ABA 2: Ônibus Urbanos Convencionais
    val conventionalLines: List<BusLine> = emptyList(),
    val selectedWaitingLine: BusLine? = null,
    val activeBusStatus: ActiveBusStatus? = null,
    val isBusApproachingSimulationRunning: Boolean = false,

    // Busca e Planejador de Destino (Google Routes & Offline)
    val destinationSearchQuery: String = "",
    val popularDestinations: List<String> = emptyList(),
    val suggestedLinesForDestination: List<BusLine> = emptyList(),
    val onlineTransitOptions: List<GoogleTransitOption> = emptyList(),
    val isSearchingOnlineRoutes: Boolean = false,
    val hasSearchedDestination: Boolean = false,
    val isUsingGoogleRoutes: Boolean = false,

    // Configurações e Acessibilidade Geral
    val isTtsEnabled: Boolean = true,
    val isHapticsEnabled: Boolean = true,
    val lastAnnouncement: String = "Nenhum aviso ainda.",
    val eventLogs: List<String> = emptyList(),

    // Estado do GPS / TalkBack (verificação silenciosa)
    val avisoGps: Boolean = false,
    val avisoTalkBack: Boolean = false,
    val precisaoGpsMetros: Float? = null,
    val foraDoCorredor: Boolean = false,

    // Sentido da rota deduzido (origem -> destino)
    val sentido: SentidoRota = SentidoRota.INDEFINIDO,
    val proximaEstacaoNome: String? = null,

    // Destinos ordenados (mais próximo -> mais distante) e favoritos
    val destinosOrdenados: List<BrtStation> = emptyList(),
    val favorito1: BrtStation? = null,
    val mostrarListaDestinos: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val brtRepository = BrtStationsRepository()
    private val conventionalRepository = ConventionalBusRepository()
    private val googleTransitService = GoogleTransitService(application)
    private val iotTelemetryClient = IotTelemetryClient()
    private val locationTracker = LocationTracker(application)
    private val reverseGeocoder = com.example.pontocerto.service.location.ReverseGeocoder(application)
    private val ttsManager = TtsManager(application)
    private val hapticManager = HapticManager(application)
    private val accessibilityStatus = AccessibilityStatus(application)
    private val announcer = Announcer(application, accessibilityStatus, ttsManager)

    /** Canal de fala: com TalkBack ativo vira announceForAccessibility; sem ele, TTS interno. */
    val anuncios = announcer.anuncios

    private val prefs = application.getSharedPreferences("ponto_certo_prefs", android.content.Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        UiState(
            stations = brtRepository.stations,
            selectedDestination = brtRepository.stations.find { it.sequence == 1 },
            conventionalLines = conventionalRepository.lines,
            selectedWaitingLine = conventionalRepository.lines.find { it.id == "639_arsenal" }
                ?: conventionalRepository.lines.first(),
            popularDestinations = conventionalRepository.popularDestinations,
            favorito1 = brtRepository.findStationById(prefs.getString("favorito_1", null))
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()


    private var trackingJob: Job? = null
    private var simulationBrtJob: Job? = null
    private var simulationBusJob: Job? = null
    private var iotSyncJob: Job? = null
    private var lastSyncedBusCode: String? = null

    // Anti-spam para avisos
    private var lastAlertedStationId: String? = null
    private var lastAlertedBrtStatus: ProximityStatus? = null
    private var lastAlertedBusLineId: String? = null
    private var lastAlertedBusStatus: ProximityStatus? = null

    // Dupla validação do sentido (duas leituras coerentes com > 12 km/h)
    private var sentidoConfirmado: SentidoRota = SentidoRota.INDEFINIDO
    private var candidatoSentido: SentidoRota? = null
    private var candidatoLeituras = 0

    init {
        // Abertura silenciosa: nada é falado se GPS e TalkBack já estiverem ligados.
        atualizarFavoritos()
        pushCurrentSelectionToEsp32()
        startIotServerSync()
    }

    /**
     * Verificação silenciosa de GPS e TalkBack (chamada no onResume da tela).
     * Só fala quando algo está desligado.
     */
    fun onResume() {
        val gpsLigado = accessibilityStatus.gpsLigado()
        val talkBackLigado = accessibilityStatus.talkBackAtivo()
        _uiState.update { it.copy(avisoGps = !gpsLigado, avisoTalkBack = !talkBackLigado) }
        if (talkBackLigado) ttsManager.stop()
        when {
            !gpsLigado -> speakMessage(
                "GPS desligado. Abra as configurações para ativar a localização.",
                force = true
            )
            !talkBackLigado -> speakMessage(
                "TalkBack desligado. Ative o leitor de tela para navegar por deslize.",
                force = true
            )
        }
        if (hasLocationPermission() && !_uiState.value.isTrackingActive) startRealTracking(silencioso = true)
    }

    fun descartarAvisosConfiguracao() {
        _uiState.update { it.copy(avisoGps = false, avisoTalkBack = false) }
    }

    fun talkBackAtivo(): Boolean = accessibilityStatus.talkBackAtivo()

    fun gpsLigado(): Boolean = accessibilityStatus.gpsLigado()

    private fun pushCurrentSelectionToEsp32() {
        val state = _uiState.value
        viewModelScope.launch {
            if (state.currentTab == AppTab.URBAN_BUSES) {
                val line = state.selectedWaitingLine ?: state.conventionalLines.firstOrNull() ?: return@launch
                iotTelemetryClient.pushSelectedBusToEsp32(
                    busType = "URBAN",
                    busCode = line.code,
                    busName = line.name,
                    corridor = line.destination.ifBlank { line.description }
                )
            } else {
                val st = state.selectedDestination ?: state.stations.firstOrNull() ?: return@launch
                val stationCode = "BRT-${st.sequence.toString().padStart(2, '0')}"
                iotTelemetryClient.pushSelectedBusToEsp32(
                    busType = "BRT",
                    busCode = stationCode,
                    busName = st.name,
                    corridor = "Corredor ${st.corridor}"
                )
            }
        }
    }

    private fun startIotServerSync() {
        iotSyncJob?.cancel()
        iotSyncJob = viewModelScope.launch {
            while (true) {
                val snap = iotTelemetryClient.fetchTelemetry()
                if (snap != null) {
                    applyIotTelemetrySnapshot(snap)
                }
                delay(1000L)
            }
        }
    }

    private fun applyIotTelemetrySnapshot(snap: IotTelemetrySnapshot) {
        val state = _uiState.value
        val prox = when (snap.proximity) {
            "ARRIVED" -> ProximityStatus.ARRIVED
            "APPROACHING" -> ProximityStatus.APPROACHING
            else -> ProximityStatus.FAR
        }

        if (state.currentTab == AppTab.URBAN_BUSES) {
            val activeLine = state.selectedWaitingLine ?: state.conventionalLines.firstOrNull() ?: return

            // Se o ESP32 ainda não estiver moldado para o ônibus selecionado no APK, força a moldagem
            if (!snap.busCode.equals(activeLine.code, ignoreCase = true) || snap.busType != "URBAN") {
                pushCurrentSelectionToEsp32()
            }

            val signal = conventionalRepository.getSignalStrength(snap.rssiDbm)
            val busStatus = ActiveBusStatus(
                line = activeLine,
                distanceMeters = snap.distanceMeters,
                rssiRdioDbm = snap.rssiDbm,
                proximityStatus = prox,
                signalStrength = signal
            )

            _uiState.update { current ->
                current.copy(activeBusStatus = busStatus)
            }
            handleConventionalBusAlerts(busStatus)
        } else {
            // Aba Corredor BRT ativa no APK
            val targetStation = state.selectedDestination ?: state.stations.firstOrNull() ?: return
            val expectedCode = "BRT-${targetStation.sequence.toString().padStart(2, '0')}"

            if (!snap.busCode.equals(expectedCode, ignoreCase = true) || snap.busType != "BRT") {
                pushCurrentSelectionToEsp32()
            }

            val nextSt = brtRepository.getNextStation(targetStation.sequence)
            _uiState.update { current ->
                current.copy(
                    nearestStation = targetStation,
                    distanceToNearestMeters = snap.distanceMeters,
                    distanceToDestinationMeters = snap.distanceMeters,
                    nextStation = nextSt,
                    proximityStatus = prox
                )
            }
            handleProximityAlerts(targetStation, prox, nextSt, targetStation)
        }
    }

    fun hasLocationPermission(): Boolean = locationTracker.hasLocationPermission()

    fun selectTab(tab: AppTab) {
        _uiState.update { it.copy(currentTab = tab) }
        hapticManager.vibrateClick()
        pushCurrentSelectionToEsp32()
        val msg = when (tab) {
            AppTab.BRT_CORRIDOR -> "Aba Corredor BRT selecionada. ESP32 sincronizado com a estação."
            AppTab.URBAN_BUSES -> "Aba Ônibus Urbanos selecionada. ESP32 sincronizado com a linha."
        }
        speakMessage(msg)
        addLog(msg)
    }

    // ==========================================
    // BUSCA DE DESTINO E ITINERÁRIOS
    // ==========================================

    fun updateDestinationSearchQuery(query: String) {
        _uiState.update { it.copy(destinationSearchQuery = query) }
    }

    fun searchDestination(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            clearDestinationSearch()
            return
        }

        hapticManager.vibrateClick()
        _uiState.update {
            it.copy(
                destinationSearchQuery = trimmed,
                hasSearchedDestination = true,
                isSearchingOnlineRoutes = true
            )
        }

        val searchingFeedback = "Buscando rotas de ônibus para $trimmed..."
        speakMessage(searchingFeedback)

        viewModelScope.launch {
            // Localização real do GPS do celular (ou referência central de Belém se GPS sem sinal)
            val originLat = _uiState.value.userLatitude ?: -1.4328
            val originLng = _uiState.value.userLongitude ?: -48.4560

            var googleOptions: List<GoogleTransitOption> = emptyList()
            try {
                // 1. Geocodifica o endereço ou local em Belém
                val destCoords = googleTransitService.geocodeAddress(trimmed)
                if (destCoords != null) {
                    // 2. Consulta Google Routes API (v2) com modo TRANSIT
                    googleOptions = googleTransitService.fetchTransitRoutes(
                        originLat = originLat,
                        originLng = originLng,
                        destLat = destCoords.first,
                        destLng = destCoords.second
                    )
                }
            } catch (e: Exception) {
                // Em caso de falha de rede, o fallback local cuidará da resposta
            }

            if (googleOptions.isNotEmpty()) {
                val lines = googleOptions.map { it.busLine }
                _uiState.update {
                    it.copy(
                        suggestedLinesForDestination = lines,
                        onlineTransitOptions = googleOptions,
                        isUsingGoogleRoutes = true,
                        isSearchingOnlineRoutes = false
                    )
                }

                val bestOption = googleOptions.first()
                val voiceAnnouncement = "Pelo Google Rotas, para $trimmed, pegue o ônibus ${bestOption.busLine.code} ${bestOption.busLine.name} na parada ${bestOption.departureStopName}. ${googleOptions.size} opções encontradas."
                speakMessage(voiceAnnouncement, force = true)
                _uiState.update { it.copy(lastAnnouncement = voiceAnnouncement) }
                addLog("[Google Rotas API] $trimmed: ${googleOptions.size} linhas encontradas. Embarque em: ${bestOption.departureStopName}")
            } else {
                // FALLBACK OFFLINE: Catálogo local de linhas de Belém
                val fallbackMatches = conventionalRepository.findLinesForDestination(trimmed)
                _uiState.update {
                    it.copy(
                        suggestedLinesForDestination = fallbackMatches,
                        onlineTransitOptions = emptyList(),
                        isUsingGoogleRoutes = false,
                        isSearchingOnlineRoutes = false
                    )
                }

                val voiceAnnouncement = if (fallbackMatches.isNotEmpty()) {
                    val linesNames = fallbackMatches.joinToString(separator = ", ") { "${it.code} ${it.name}" }
                    "Para $trimmed, encontramos ${fallbackMatches.size} linhas na sua parada: $linesNames. Toque em uma linha para monitorar."
                } else {
                    "Nenhuma linha direta encontrada para $trimmed. Tente outro local ou verifique o Corredor BRT."
                }

                speakMessage(voiceAnnouncement, force = true)
                _uiState.update { it.copy(lastAnnouncement = voiceAnnouncement) }
                addLog("[Catálogo Local] $trimmed: ${fallbackMatches.size} linhas encontradas.")
            }
        }
    }

    fun selectSuggestedBusLine(line: BusLine) {
        _uiState.update {
            it.copy(
                currentTab = AppTab.URBAN_BUSES,
                selectedWaitingLine = line,
                activeBusStatus = null
            )
        }
        lastAlertedBusLineId = null
        lastAlertedBusStatus = null
        hapticManager.vibrateClick()
        pushCurrentSelectionToEsp32()
        val msg = "Linha ${line.code} ${line.name} selecionada no aplicativo. ESP32 moldado para este ônibus!"
        speakMessage(msg)
        addLog(msg)
    }

    fun clearDestinationSearch() {
        _uiState.update {
            it.copy(
                destinationSearchQuery = "",
                suggestedLinesForDestination = emptyList(),
                onlineTransitOptions = emptyList(),
                hasSearchedDestination = false,
                isUsingGoogleRoutes = false,
                isSearchingOnlineRoutes = false
            )
        }
    }


    // ==========================================
    // SEÇÃO: ÔNIBUS URBANOS CONVENCIONAIS (ABA 2)
    // ==========================================

    fun selectWaitingLine(line: BusLine) {

        _uiState.update {
            it.copy(
                selectedWaitingLine = line,
                activeBusStatus = null
            )
        }
        lastAlertedBusLineId = null
        lastAlertedBusStatus = null
        hapticManager.vibrateClick()
        pushCurrentSelectionToEsp32()

        val msg = "Você está aguardando a linha ${line.code}: ${line.name}. ESP32 sincronizado!"
        speakMessage(msg)
        addLog(msg)
    }

    /**
     * Simula a aproximação de uma linha de ônibus convencional (ex: ESP32 transmitindo via rádio BLE)
     */
    fun simulateBusApproach(busLine: BusLine? = _uiState.value.selectedWaitingLine) {
        val targetLine = busLine ?: _uiState.value.conventionalLines.first()
        simulationBusJob?.cancel()

        _uiState.update {
            it.copy(
                isBusApproachingSimulationRunning = true,
                selectedWaitingLine = targetLine
            )
        }
        pushCurrentSelectionToEsp32()

        val startMsg = "Iniciando detecção do ônibus ${targetLine.code} ${targetLine.name} via sinal de rádio IoT."
        speakMessage(startMsg)
        addLog(startMsg)

        simulationBusJob = viewModelScope.launch {
            // Estágio 1: Longe (350 metros / RSSI ~ -86 dBm)
            updateConventionalBusPosition(targetLine, 350f)
            delay(3500)

            // Estágio 2: Se aproximando (130 metros / RSSI ~ -73 dBm)
            updateConventionalBusPosition(targetLine, 130f)
            delay(4000)

            // Estágio 3: Muito perto (50 metros / RSSI ~ -62 dBm)
            updateConventionalBusPosition(targetLine, 50f)
            delay(3500)

            // Estágio 4: Chegou no ponto! (12 metros / RSSI ~ -45 dBm)
            updateConventionalBusPosition(targetLine, 12f)
            delay(5000)

            _uiState.update { it.copy(isBusApproachingSimulationRunning = false) }
        }
    }

    fun stopBusSimulation() {
        simulationBusJob?.cancel()
        simulationBusJob = null
        _uiState.update {
            it.copy(
                isBusApproachingSimulationRunning = false,
                activeBusStatus = null
            )
        }
    }

    private fun updateConventionalBusPosition(line: BusLine, distanceMeters: Float) {
        val rssi = conventionalRepository.calculateRssiFromDistance(distanceMeters)
        val strength = conventionalRepository.getSignalStrength(rssi)

        val proximity = when {
            distanceMeters <= 30f -> ProximityStatus.ARRIVED
            distanceMeters <= 160f -> ProximityStatus.APPROACHING
            else -> ProximityStatus.FAR
        }

        val busStatus = ActiveBusStatus(
            line = line,
            distanceMeters = distanceMeters,
            rssiRdioDbm = rssi,
            proximityStatus = proximity,
            signalStrength = strength
        )

        _uiState.update { it.copy(activeBusStatus = busStatus) }

        handleConventionalBusAlerts(busStatus)
    }

    private fun handleConventionalBusAlerts(busStatus: ActiveBusStatus) {
        val line = busStatus.line
        val proximity = busStatus.proximityStatus

        if (lastAlertedBusLineId == line.id && lastAlertedBusStatus == proximity) {
            return
        }

        when (proximity) {
            ProximityStatus.APPROACHING -> {
                lastAlertedBusLineId = line.id
                lastAlertedBusStatus = proximity

                val announcement = "Atenção: A linha ${line.code} ${line.name} está se aproximando! Distância de cerca de ${busStatus.distanceMeters.toInt()} metros."
                if (_uiState.value.isHapticsEnabled) {
                    hapticManager.vibrateApproaching()
                }
                speakMessage(announcement)
                _uiState.update { it.copy(lastAnnouncement = announcement) }
                addLog("[Rádio IoT] $announcement (Sinal: ${busStatus.rssiRdioDbm} dBm)")
            }

            ProximityStatus.ARRIVED -> {
                lastAlertedBusLineId = line.id
                lastAlertedBusStatus = proximity

                val announcement = "O ônibus ${line.code} ${line.name} chegou no seu ponto! Pode embarcar com segurança!"
                if (_uiState.value.isHapticsEnabled) {
                    hapticManager.vibrateArrival()
                }
                speakMessage(announcement)
                _uiState.update { it.copy(lastAnnouncement = announcement) }
                addLog("[Chegada] $announcement (Sinal Máximo: ${busStatus.rssiRdioDbm} dBm)")
            }

            ProximityStatus.FAR -> {
                // Ônibus detectado à distância
            }
        }
    }

    // ==========================================
    // SEÇÃO: CORREDOR BRT (ABA 1)
    // ==========================================

    fun selectDestination(station: BrtStation, anunciar: Boolean = true) {
        sentidoConfirmado = SentidoRota.INDEFINIDO
        candidatoSentido = null
        candidatoLeituras = 0
        _uiState.update { it.copy(selectedDestination = station, mostrarListaDestinos = false) }
        hapticManager.vibrateClick()
        pushCurrentSelectionToEsp32()
        if (anunciar) {
            val msg = "Destino: ${station.name}."
            speakMessage(msg)
            addLog(msg)
        }
        recalculateDistances()
        recalcularRota()
    }

    // ==========================================
    // DESTINOS ORDENADOS E FAVORITOS
    // ==========================================

    /** Card 2, toque duplo: próxima estação da lista ordenada. */
    fun ciclarDestino() {
        val estado = _uiState.value
        val lista = estado.destinosOrdenados.ifEmpty { estado.stations }
        if (lista.isEmpty()) return
        val indice = lista.indexOfFirst { it.id == estado.selectedDestination?.id }
        val proximo = if (indice < 0) lista.first() else lista[(indice + 1) % lista.size]
        // Sem fala aqui: o TalkBack lê o nome novo pelo contentDescription do card.
        selectDestination(proximo, anunciar = false)
    }

    /** Toque longo no card 2: guarda um único favorito. */
    fun salvarDestinoFavorito() {
        val destino = _uiState.value.selectedDestination ?: return
        prefs.edit().putString("favorito_1", destino.id).apply()
        hapticManager.vibrateClick()
        atualizarFavoritos()
        speakMessage("Favoritado: ${destino.name}.")
    }

    private fun atualizarFavoritos() {
        val f1 = brtRepository.findStationById(prefs.getString("favorito_1", null))
        _uiState.update {
            it.copy(
                favorito1 = f1,
                destinosOrdenados = brtRepository.destinosOrdenados(
                    it.userLatitude, it.userLongitude, f1?.id
                )
            )
        }
    }

    /** Abre a lista acessível com todas as estações do corredor. */
    fun abrirListaDestinos() {
        hapticManager.vibrateClick()
        _uiState.update { it.copy(mostrarListaDestinos = true) }
        speakMessage("Lista de destinos. ${_uiState.value.destinosOrdenados.size} estações.")
    }

    fun fecharListaDestinos() {
        _uiState.update { it.copy(mostrarListaDestinos = false) }
    }

    /** Quantas estações faltam até o destino a partir da estação mais próxima. */
    fun paradasRestantes(): Int? {
        val estado = _uiState.value
        val destino = estado.selectedDestination ?: return null
        val atual = estado.nearestStation ?: return null
        return brtRepository.paradasAte(destino, atual)
    }

    /**
     * Deduz o sentido (Belém ou Marituba) comparando a estação onde o usuário está
     * com o destino escolhido — ex.: em Humaitá indo ao Jardim Tropical = sentido Marituba.
     */
    private fun recalcularRota() {
        val estado = _uiState.value
        val origem = estado.nearestStation
        val destino = estado.selectedDestination
        if (origem == null || destino == null || origem.id == destino.id) {
            _uiState.update { it.copy(sentido = SentidoRota.INDEFINIDO, proximaEstacaoNome = null) }
            return
        }
        val sentido = sentidoConfirmado.takeIf { it != SentidoRota.INDEFINIDO } ?: if (destino.sequence > origem.sequence) {
            SentidoRota.IDA_BELEM
        } else {
            SentidoRota.VOLTA_MARITUBA
        }
        val proxima = brtRepository.proximaParada(origem, sentido)
        _uiState.update { it.copy(sentido = sentido, proximaEstacaoNome = proxima?.name) }
    }

    fun textoRota(): String {
        val estado = _uiState.value
        val sentido = when (estado.sentido) {
            SentidoRota.IDA_BELEM -> "sentido Belém"
            SentidoRota.VOLTA_MARITUBA -> "sentido Marituba"
            SentidoRota.INDEFINIDO -> "sentido indefinido"
        }
        val restantes = paradasRestantes()
        return buildString {
            append(sentido)
            if (restantes != null) {
                append(if (restantes == 1) ", falta 1 estação" else ", faltam $restantes estações")
            }
            estado.proximaEstacaoNome?.let { append(", próxima $it") }
        }
    }

    private fun sentidoTexto(): String = when (_uiState.value.sentido) {
        SentidoRota.IDA_BELEM -> "Belém"
        SentidoRota.VOLTA_MARITUBA -> "Marituba"
        SentidoRota.INDEFINIDO -> "indefinido"
    }

    /**
     * Dupla validação do sentido: uma leitura isolada nunca trava a direção.
     * Exige duas leituras consecutivas coerentes, acima de 12 km/h, e mantém o
     * último sentido confirmado quando o sinal oscila ou o usuário para.
     */
    private fun validarSentidoPorMovimento(
        estacaoAtual: BrtStation?,
        velocidadeMs: Float,
        bearing: Float?
    ) {
        if (estacaoAtual == null) return
        if (velocidadeMs * 3.6f <= VELOCIDADE_MINIMA_KMH) return

        val leitura = brtRepository.inferirSentido(
            estacaoAnterior = null,
            estacaoAtual = estacaoAtual,
            destino = _uiState.value.selectedDestination,
            gpsBearing = bearing,
            velocidadeMs = velocidadeMs
        )
        if (leitura == SentidoRota.INDEFINIDO) return

        if (leitura == sentidoConfirmado) {
            candidatoSentido = null
            candidatoLeituras = 0
            return
        }
        if (leitura == candidatoSentido) {
            candidatoLeituras++
        } else {
            candidatoSentido = leitura
            candidatoLeituras = 1
        }
        if (candidatoLeituras < 2) return

        sentidoConfirmado = leitura
        candidatoSentido = null
        candidatoLeituras = 0
        _uiState.update { it.copy(sentido = leitura) }
        recalcularRota()
        if (_uiState.value.isTrackingActive) {
            speakMessage("Sentido confirmado: ${if (leitura == SentidoRota.IDA_BELEM) "Belém" else "Marituba"}.")
        }
    }

    private companion object {
        const val VELOCIDADE_MINIMA_KMH = 12f
    }

    fun startRealTracking(silencioso: Boolean = false) {
        stopSimulation()
        trackingJob?.cancel()

        if (!accessibilityStatus.gpsLigado()) {
            _uiState.update { it.copy(avisoGps = true) }
            speakMessage("GPS desligado. Abra as configurações para ativar a localização.", force = true)
            return
        }

        _uiState.update { it.copy(isTrackingActive = true) }
        recalcularRota()
        val destino = _uiState.value.selectedDestination
        val restantes = paradasRestantes()
        val msg = when {
            destino == null -> "Viagem iniciada. Nenhum destino selecionado. Vou avisar cada estação."
            restantes == null -> "Viagem iniciada até ${destino.name}. Sentido ${sentidoTexto()}."
            restantes == 1 -> "Viagem iniciada até ${destino.name}. Sentido ${sentidoTexto()}. Falta 1 estação."
            else -> "Viagem iniciada até ${destino.name}. Sentido ${sentidoTexto()}. Faltam $restantes estações."
        }
        if (!silencioso) speakMessage(msg)
        if (!silencioso) addLog(msg)

        trackingJob = viewModelScope.launch {
            locationTracker.getLocationUpdates(intervalMs = 2000L)
                .catch { e ->
                    val errorMsg = "Erro no GPS: ${e.message}"
                    speakMessage(errorMsg)
                    addLog(errorMsg)
                    _uiState.update { it.copy(isTrackingActive = false) }
                }
                .collect { location ->
                    onLocationUpdated(
                        location.latitude,
                        location.longitude,
                        location.accuracy,
                        location.speed,
                        if (location.hasBearing()) location.bearing else null
                    )
                }
        }
    }

    fun stopRealTracking() {
        trackingJob?.cancel()
        trackingJob = null
        _uiState.update { it.copy(isTrackingActive = false) }
        val msg = "Rastreamento GPS parado."
        speakMessage(msg)
        addLog(msg)
    }

    fun startSimulation() {
        stopRealTracking()
        simulationBrtJob?.cancel()

        _uiState.update {
            it.copy(
                isSimulating = true,
                simulationIndex = 0
            )
        }

        val msg = "Iniciando simulação de viagem pelo BRT."
        speakMessage(msg)
        addLog(msg)

        simulationBrtJob = viewModelScope.launch {
            val stations = brtRepository.stations
            var currentIndex = 0

            while (currentIndex < stations.size) {
                val currentSt = stations[currentIndex]

                // 1. Fase de aproximação
                val approxLat = currentSt.latitude + 0.0018
                val approxLon = currentSt.longitude + 0.0018
                onLocationUpdated(approxLat, approxLon)
                delay(4000)

                // 2. Fase de chegada
                onLocationUpdated(currentSt.latitude, currentSt.longitude)
                delay(6000)

                if (_uiState.value.selectedDestination?.id == currentSt.id) {
                    val finishMsg = "Simulação concluída! Destino alcançado."
                    addLog(finishMsg)
                    break
                }

                currentIndex++
                _uiState.update { it.copy(simulationIndex = currentIndex) }
            }

            _uiState.update { it.copy(isSimulating = false) }
        }
    }

    fun stopSimulation() {
        simulationBrtJob?.cancel()
        simulationBrtJob = null
        _uiState.update { it.copy(isSimulating = false) }
    }

    fun simulateStepTo(station: BrtStation) {
        onLocationUpdated(station.latitude, station.longitude)
    }

    private fun onLocationUpdated(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float? = null,
        velocidadeMs: Float = 0f,
        bearing: Float? = null
    ) {
        if (accuracyMeters != null && accuracyMeters > 150f) {
            // Fix muito impreciso (torre/Wi-Fi): ignora para não deslocar o usuário de estação.
            addLog("[GPS] Precisão baixa (${accuracyMeters.toInt()} m), leitura descartada.")
            return
        }

        val nearestPair = brtRepository.findNearestStation(latitude, longitude) ?: return
        val nearestStation = nearestPair.first
        val distanceToNearest = nearestPair.second
        val foraDoCorredor = !brtRepository.dentroDoCorredor(latitude, longitude)

        val nextStation = brtRepository.getNextStation(nearestStation.sequence)

        val proximityStatus = when {
            distanceToNearest <= 90f -> ProximityStatus.ARRIVED
            distanceToNearest <= 400f -> ProximityStatus.APPROACHING
            else -> ProximityStatus.FAR
        }

        val destStation = _uiState.value.selectedDestination
        val distanceToDestination = if (destStation != null) {
            brtRepository.calculateDistanceMeters(latitude, longitude, destStation.latitude, destStation.longitude)
        } else null

        val distanceToNext = if (nextStation != null) {
            brtRepository.calculateDistanceMeters(latitude, longitude, nextStation.latitude, nextStation.longitude)
        } else 0f

        _uiState.update { current ->
            current.copy(
                userLatitude = latitude,
                userLongitude = longitude,
                nearestStation = nearestStation,
                distanceToNearestMeters = distanceToNearest,
                nextStation = nextStation,
                distanceToNextMeters = distanceToNext,
                distanceToDestinationMeters = distanceToDestination,
                proximityStatus = proximityStatus,
                precisaoGpsMetros = accuracyMeters,
                foraDoCorredor = foraDoCorredor,
                destinosOrdenados = brtRepository.destinosOrdenados(
                    latitude, longitude,
                    _uiState.value.favorito1?.id
                )
            )
        }

        handleProximityAlerts(nearestStation, proximityStatus, nextStation, destStation)
        validarSentidoPorMovimento(
            brtRepository.estacaoAtual(latitude, longitude),
            velocidadeMs,
            bearing
        )
        recalcularRota()
    }

    private fun handleProximityAlerts(
        nearestStation: BrtStation,
        proximityStatus: ProximityStatus,
        nextStation: BrtStation?,
        destination: BrtStation?
    ) {
        if (lastAlertedStationId == nearestStation.id && lastAlertedBrtStatus == proximityStatus) {
            return
        }

        when (proximityStatus) {
            ProximityStatus.APPROACHING -> {
                lastAlertedStationId = nearestStation.id
                lastAlertedBrtStatus = proximityStatus

                val isDest = destination?.id == nearestStation.id
                val announcement = if (isDest) {
                    "Atenção: aproximando-se do seu destino final: ${nearestStation.name}!"
                } else {
                    "Aproximando-se da ${nearestStation.name}."
                }

                if (_uiState.value.isHapticsEnabled) {
                    hapticManager.vibrateApproaching()
                }
                speakMessage(announcement)
                _uiState.update { it.copy(lastAnnouncement = announcement) }
                addLog("[Aproximação] $announcement")
            }

            ProximityStatus.ARRIVED -> {
                lastAlertedStationId = nearestStation.id
                lastAlertedBrtStatus = proximityStatus

                val isDest = destination?.id == nearestStation.id
                val announcement = if (isDest) {
                    "Você chegou ao seu destino: ${nearestStation.name}. Desembarque agora!"
                } else {
                    val nextName = nextStation?.name ?: "Final da Linha"
                    "Parada atual: ${nearestStation.name}. Próxima parada: $nextName."
                }

                if (_uiState.value.isHapticsEnabled) {
                    hapticManager.vibrateArrival()
                }
                speakMessage(announcement)
                _uiState.update { it.copy(lastAnnouncement = announcement) }
                addLog("[Chegada] $announcement")
            }

            ProximityStatus.FAR -> {
                if (lastAlertedStationId != nearestStation.id) {
                    lastAlertedStationId = null
                    lastAlertedBrtStatus = null
                }
            }
        }
    }

    // ==========================================
    // RECURSOS GERAIS DE ACESSIBILIDADE
    // ==========================================

    fun announceCurrentStatus() {
        hapticManager.vibrateClick()
        val state = _uiState.value

        if (state.currentTab == AppTab.URBAN_BUSES) {
            val message = run {
                val waiting = state.selectedWaitingLine
                val active = state.activeBusStatus
                when {
                    waiting == null -> "Nenhum ônibus selecionado para aguardar."
                    active == null ->
                        "Aguardando a linha ${waiting.code} ${waiting.name}. Nenhum sinal de rádio detectado no momento."
                    else -> when (active.proximityStatus) {
                        ProximityStatus.ARRIVED ->
                            "Atenção! O ônibus ${waiting.code} ${waiting.name} acabou de encostar na sua parada! Embarque agora."
                        ProximityStatus.APPROACHING ->
                            "O ônibus ${waiting.code} ${waiting.name} está se aproximando! Distância de cerca de ${active.distanceMeters.toInt()} metros."
                        ProximityStatus.FAR ->
                            "A linha ${waiting.code} ${waiting.name} foi detectada a ${active.distanceMeters.toInt()} metros."
                    }
                }
            }
            publicarAnuncio(message)
            return
        }

        val nearest = state.nearestStation
        val lat = state.userLatitude
        val lon = state.userLongitude
        if (nearest == null || lat == null || lon == null) {
            publicarAnuncio("Aguardando sinal de localização. Ative o GPS e toque novamente.")
            if (!accessibilityStatus.gpsLigado()) _uiState.update { it.copy(avisoGps = true) }
            return
        }

        viewModelScope.launch {
            val rua = reverseGeocoder.nomeDaRua(lat, lon)
            val distancia = state.distanceToNearestMeters.toInt()
            val precisao = state.precisaoGpsMetros?.toInt()
            val nomeCurto = nearest.name
                .removePrefix("Estação ")
                .removePrefix("Terminal BRT ")
                .removePrefix("Terminal ")

            val mensagem = buildString {
                if (rua != null) {
                    append("Você está na $rua. ")
                    append("Estação mais próxima: $nomeCurto, ")
                    if (distancia >= 1000) {
                        append("a ${String.format(Locale.US, "%.1f", distancia / 1000f)} quilômetros.")
                    } else {
                        append("a $distancia metros.")
                    }
                } else {
                    append("Estação mais próxima: $nomeCurto, a $distancia metros.")
                }
                if (precisao != null && precisao > 50) append(" Precisão do GPS: $precisao metros.")
            }
            publicarAnuncio(mensagem)
        }
    }

    private fun publicarAnuncio(message: String) {
        speakMessage(message, force = true)
        _uiState.update { it.copy(lastAnnouncement = message) }
        addLog("[Consulta Acessível] $message")
    }

    fun toggleTts() {
        val newState = !_uiState.value.isTtsEnabled
        _uiState.update { it.copy(isTtsEnabled = newState) }
        hapticManager.vibrateClick()
        if (newState) {
            speakMessage("Voz ativada.")
        }
    }

    fun toggleHaptics() {
        val newState = !_uiState.value.isHapticsEnabled
        _uiState.update { it.copy(isHapticsEnabled = newState) }
        if (newState) {
            hapticManager.vibrateApproaching()
        }
    }

    private fun recalculateDistances() {
        val lat = _uiState.value.userLatitude
        val lon = _uiState.value.userLongitude
        if (lat != null && lon != null) {
            onLocationUpdated(lat, lon)
        }
    }

    private fun speakMessage(text: String, force: Boolean = false) {
        if (_uiState.value.isTtsEnabled || force) {
            announcer.anunciar(text)
        }
    }

    private fun addLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _uiState.update { current ->
            val newLogs = listOf("[$timestamp] $message") + current.eventLogs.take(40)
            current.copy(eventLogs = newLogs)
        }
    }

    override fun onCleared() {
        super.onCleared()
        trackingJob?.cancel()
        simulationBrtJob?.cancel()
        simulationBusJob?.cancel()
        iotSyncJob?.cancel()
        ttsManager.shutdown()
    }
}
