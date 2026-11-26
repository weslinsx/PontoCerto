package com.example.pontocerto.ui.screens

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.pontocerto.ui.MainViewModel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

private val CorFundo = Color(0xFF0D1117)
private val CorTexto = Color(0xFFFFFFFF)
private val CorTextoSuave = Color(0xFFE6EDF3)
private val CorRodape = Color(0xFFE6EDF3)
private val Ciano = Color(0xFFFFD60A)
private val Amarelo = Color(0xFFFFD54F)
private val Verde = Color(0xFF00E676)
private val CardAzul = Color(0xFF1D4ED8)
private val CardRoxo = Color(0xFF000000)
private val CardVerde = Color(0xFFFFD60A)

@Composable
fun HomeScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissoes ->
        if (permissoes.values.any { it }) viewModel.startRealTracking(silencioso = true)
    }

    LaunchedEffect(view) {
        viewModel.anuncios.collect { texto -> view.announceForAccessibility(texto) }
    }

    // Tela acesa durante a viagem: tela apagando reinicia o foco do TalkBack.
    DisposableEffect(uiState.isTrackingActive) {
        view.keepScreenOn = uiState.isTrackingActive
        onDispose { view.keepScreenOn = false }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onResume()
                if (!viewModel.hasLocationPermission()) {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val distanciaKm = if (uiState.distanceToNearestMeters < 1000f) {
        "${uiState.distanceToNearestMeters.toInt()}m"
    } else {
        String.format(Locale.US, "%.1f", uiState.distanceToNearestMeters / 1000f).replace('.', ',') + "km"
    }
    val paradas = viewModel.paradasRestantes()
    val posicaoDestino = uiState.destinosOrdenados
        .indexOfFirst { it.id == uiState.selectedDestination?.id }
        .let { if (it < 0) 1 else it + 1 }
    val origem = uiState.nearestStation?.name ?: "Sua posição"
    val destino = uiState.selectedDestination?.name ?: "Nenhum destino"
    val rotaInfo = viewModel.textoRota()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CorFundo)
            .systemBarsPadding()
            .padding(horizontal = 15.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(start = 6.dp, bottom = 4.dp)
                .clearAndSetSemantics { },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.DirectionsBus,
                contentDescription = null,
                tint = Amarelo,
                modifier = Modifier.size(30.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "Ponto Certo BRT",
                    color = Color(0xFFE4E4E7),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 22.sp
                )
                Text(
                    text = "Marituba a São Brás • ${uiState.stations.size} Terminais",
                    color = Color(0xFFCCCCCE),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        CartaoAcao(
            modifier = Modifier.weight(1f),
            cor = CardAzul,
            destaque = Ciano,
            icone = Icons.Default.DirectionsBus,
            titulo = "1. ONDE ESTOU?",
            subtitulo = "Toque para checar GPS e TalkBack",
            selo = distanciaKm,
            rotulo = "TERMINAL MAIS PRÓXIMO",
            valor = uiState.nearestStation?.name ?: "Aguardando GPS",
            rodapeIcone = Icons.Default.RecordVoiceOver,
            rodapeTexto = "Diz localização e distância por voz",
            acaoAcessivel = "Onde estou. Terminal mais próximo: " +
                "${uiState.nearestStation?.name ?: "aguardando GPS"}, a $distanciaKm. " +
                "Toque duas vezes para ouvir localização e distância.",
            aoClicar = { viewModel.announceCurrentStatus() },
            aoSegurar = null
        )

        CartaoAcao(
            modifier = Modifier.weight(1f),
            cor = CardRoxo,
            destaque = Amarelo,
            icone = Icons.Default.Place,
            titulo = "2. DESTINO & FAVORITO",
            subtitulo = "1 toque: próxima estação • 3s: favoritar",
            selo = paradas?.let { "Faltam $it" } ?: "Sem GPS",
            rotulo = "DESTINO SELECIONADO",
            valor = destino,
            acaoAcessivel = buildString {
                append(destino)
                append(". ${posicaoDestino} de ${uiState.destinosOrdenados.size}. ")
                append("Toque duas vezes para a próxima estação. ")
                append("Toque longo para favoritar.")
            },
            aoClicar = { viewModel.ciclarDestino() },
            aoSegurar = { viewModel.salvarDestinoFavorito() },
            acoesExtras = listOf(
                CustomAccessibilityAction("Abrir lista de destinos") {
                    viewModel.abrirListaDestinos(); true
                },
                CustomAccessibilityAction("Favoritar estação") {
                    viewModel.salvarDestinoFavorito(); true
                }
            ),
            rodapeConteudo = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CaixaFavorito(
                        modifier = Modifier.weight(1f),
                        texto = "★: ${uiState.favorito1?.name ?: "Sem favorito"}",
                        destaque = Amarelo
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            tint = CorTextoSuave,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "3s favorita",
                            color = CorTextoSuave,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        )

        CartaoAcao(
            modifier = Modifier.weight(1f),
            cor = CardVerde,
            destaque = Verde,
            icone = Icons.Default.PlayArrow,
            titulo = "3. INICIAR VIAGEM REAL",
            subtitulo = "1 toque: Iniciar (GPS) • 3s: Simulação Wyden",
            selo = when {
                uiState.isSimulating -> "SIMULANDO"
                uiState.isTrackingActive -> "GPS ATIVO"
                else -> "PRONTO P/ INICIAR"
            },
            rotulo = "ROTA SELECIONADA",
            valor = "$origem ➔ $destino",
            claro = true,
            rodapeIcone = Icons.Default.PlayArrow,
            rodapeTexto = rotaInfo.replaceFirstChar { it.uppercase() },
            acaoAcessivel = "Viagem. Rota $origem até $destino, $rotaInfo. " +
                "Toque duas vezes para ${if (uiState.isTrackingActive) "parar" else "iniciar"} o GPS real. " +
                "Toque longo para simulação.",
            aoClicar = {
                if (uiState.isTrackingActive) viewModel.stopRealTracking()
                else viewModel.startRealTracking()
            },
            aoSegurar = {
                if (uiState.isSimulating) viewModel.stopSimulation()
                else viewModel.startSimulation()
            }
        )
    }

    if (uiState.mostrarListaDestinos) {
        ListaDestinosDialog(
            destinos = uiState.destinosOrdenados,
            selecionadoId = uiState.selectedDestination?.id,
            distanciaMetros = { estacao ->
                val lat = uiState.userLatitude
                val lon = uiState.userLongitude
                if (lat == null || lon == null) null
                else haversine(lat, lon, estacao.latitude, estacao.longitude)
            },
            aoSelecionar = { viewModel.selectDestination(it) },
            aoFechar = { viewModel.fecharListaDestinos() }
        )
    }

    if (uiState.avisoGps) {
        AvisoConfiguracaoDialog(
            titulo = "GPS desligado",
            mensagem = "Sem a localização o Ponto Certo não consegue identificar a estação mais próxima.",
            textoBotao = "Abrir localização",
            aoAbrir = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
            aoFechar = { viewModel.descartarAvisosConfiguracao() }
        )
    }

    if (uiState.avisoTalkBack) {
        AvisoConfiguracaoDialog(
            titulo = "TalkBack desligado",
            mensagem = "Ative o leitor de tela para navegar por deslize e ouvir os avisos sem vozes sobrepostas.",
            textoBotao = "Abrir acessibilidade",
            aoAbrir = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            aoFechar = { viewModel.descartarAvisosConfiguracao() }
        )
    }
}

@Composable
private fun CartaoAcao(
    modifier: Modifier = Modifier,
    cor: Color,
    destaque: Color,
    icone: ImageVector,
    titulo: String,
    subtitulo: String,
    selo: String,
    rotulo: String,
    valor: String,
    acaoAcessivel: String,
    aoClicar: () -> Unit,
    aoSegurar: (() -> Unit)?,
    rodapeIcone: ImageVector? = null,
    rodapeTexto: String? = null,
    claro: Boolean = false,
    acoesExtras: List<CustomAccessibilityAction> = emptyList(),
    rodapeConteudo: (@Composable () -> Unit)? = null
) {
    val corTitulo = if (claro) Color(0xFF000000) else Color(0xFFFFFFFF)
    val corTexto2 = if (claro) Color(0xFF1F2937) else CorTextoSuave
    val corDestaque = if (claro) Color(0xFF000000) else destaque
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = acaoAcessivel
                onClick(label = if (aoSegurar != null) "Próximo" else "Ouvir") {
                    aoClicar(); true
                }
                if (aoSegurar != null) {
                    onLongClick(label = "Toque longo") { aoSegurar(); true }
                }
                if (acoesExtras.isNotEmpty()) customActions = acoesExtras
            }
            .pointerInput(aoClicar, aoSegurar) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val soltou = withTimeoutOrNull(PRESSIONAR_LONGO_MS) {
                        waitForUpOrCancellation()
                    }
                    when {
                        soltou == null -> aoSegurar?.invoke()
                        (soltou.position - down.position).getDistance() <= viewConfiguration.touchSlop -> aoClicar()
                        // Arrasto (deslize do TalkBack ou rolagem): ignora para não ativar o card.
                    }
                }
            },
        color = cor,
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(2.dp, destaque)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .clearAndSetSemantics { }
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = icone,
                    contentDescription = null,
                    tint = corDestaque,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(26.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = titulo,
                        color = corTitulo,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 19.sp
                    )
                    Text(
                        text = subtitulo,
                        color = corTexto2,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = if (claro) Color(0x33000000) else destaque.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = selo,
                        color = corDestaque,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = rotulo,
                color = corTexto2,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )
            Text(
                text = valor,
                color = corTitulo,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold
            )

            Spacer(modifier = Modifier.weight(1f))

            if (rodapeConteudo != null) {
                rodapeConteudo()
            } else if (rodapeTexto != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (rodapeIcone != null) {
                        Icon(
                            imageVector = rodapeIcone,
                            contentDescription = null,
                            tint = if (claro) Color(0xFF1F2937) else CorRodape,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = rodapeTexto,
                        color = if (claro) Color(0xFF1F2937) else CorRodape,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun CaixaFavorito(modifier: Modifier = Modifier, texto: String, destaque: Color) {
    Surface(
        modifier = modifier,
        color = destaque.copy(alpha = 0.10f),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = null,
                tint = CorTextoSuave,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = texto,
                color = CorTextoSuave,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ListaDestinosDialog(
    destinos: List<com.example.pontocerto.data.model.BrtStation>,
    selecionadoId: String?,
    distanciaMetros: (com.example.pontocerto.data.model.BrtStation) -> Float?,
    aoSelecionar: (com.example.pontocerto.data.model.BrtStation) -> Unit,
    aoFechar: () -> Unit
) {
    Dialog(
        onDismissRequest = aoFechar,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = CorFundo
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Escolha o destino",
                    color = CorTexto,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier
                        .padding(bottom = 12.dp)
                        .clearAndSetSemantics { }
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(destinos) { indice, estacao ->
                        val selecionado = estacao.id == selecionadoId
                        val distancia = distanciaMetros(estacao)?.let {
                            if (it >= 1000f) String.format(Locale.US, "%.1f", it / 1000f).replace('.', ',') + " quilômetros"
                            else "${it.toInt()} metros"
                        }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics(mergeDescendants = true) {
                                    contentDescription = buildString {
                                        append("${estacao.name}. ")
                                        if (distancia != null) append("$distancia. ")
                                        append("${indice + 1} de ${destinos.size}.")
                                        if (selecionado) append(" Selecionado.")
                                    }
                                    onClick(label = "Selecionar destino") {
                                        aoSelecionar(estacao); true
                                    }
                                }
                                .pointerInput(estacao.id) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        waitForUpOrCancellation()
                                        aoSelecionar(estacao)
                                    }
                                },
                            color = if (selecionado) CorCardSelecionado else CorCardLista,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                                    .clearAndSetSemantics { },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DirectionsBus,
                                    contentDescription = null,
                                    tint = Ciano,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = estacao.name,
                                        color = CorTexto,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 17.sp
                                    )
                                    Text(
                                        text = "${estacao.corridor}${if (distancia != null) " • $distancia" else ""}",
                                        color = CorTextoSuave,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Fechar lista de destinos"
                            onClick(label = "Fechar") { aoFechar(); true }
                        }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                waitForUpOrCancellation()
                                aoFechar()
                            }
                        },
                    color = CorCardLista,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = "Fechar lista de destinos",
                        color = CorTexto,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(16.dp)
                            .clearAndSetSemantics { }
                    )
                }
            }
        }
    }
}

private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
        Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return (r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))).toFloat()
}

private val CorCardLista = Color(0xFF161B22)
private val CorCardSelecionado = Color(0xFF1D4ED8)

@Composable
private fun AvisoConfiguracaoDialog(
    titulo: String,
    mensagem: String,
    textoBotao: String,
    aoAbrir: () -> Unit,
    aoFechar: () -> Unit
) {
    AlertDialog(
        onDismissRequest = aoFechar,
        title = { Text(text = titulo, fontWeight = FontWeight.Bold) },
        text = { Text(text = mensagem) },
        confirmButton = {
            TextButton(onClick = {
                aoAbrir()
                aoFechar()
            }) {
                Text(text = textoBotao, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = aoFechar) { Text(text = "Agora não") }
        }
    )
}

private const val PRESSIONAR_LONGO_MS = 3000L
