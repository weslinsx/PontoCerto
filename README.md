# Ponto Certo BRT

Aplicativo Android de acessibilidade para o BRT Metropolitano de Belém (Marituba → Belém), feito para pessoas com deficiência visual que usam TalkBack.

## Problema

O corredor BRT Metropolitano tem 19 estações distribuídas entre Marituba, Ananindeua e Belém. Estações-ilha da BR-316 atendem ida e volta no mesmo ponto físico, o que torna fácil embarcar no sentido errado. Sem referência sonora, o passageiro cego depende de terceiros para saber onde está, qual estação vem depois e quando descer.

## Arquitetura TalkBack-First

- Nenhuma voz do app compete com o leitor de tela: com TalkBack ativo (`AccessibilityManager.isTouchExplorationEnabled`) o `TextToSpeech` interno nunca é inicializado e toda fala sai por `announceForAccessibility`.
- TTS é apenas fallback para quando o TalkBack está desligado.
- Abertura silenciosa: só fala se GPS ou TalkBack estiverem desligados, oferecendo atalho para as configurações.
- Apenas 3 nós recebem foco do TalkBack; cabeçalho, selos, rótulos e rodapés são decorativos (`clearAndSetSemantics`).
- Gestos: deslize lateral troca de card; deslize vertical não altera estado (checagem de `touchSlop`); toque longo (3 s) favorita/substitui ação.

## Os 3 botões

| Botão | Toque duplo | Toque longo (3 s) |
| --- | --- | --- |
| 1. Onde estou | Revalida GPS/TalkBack e anuncia rua (Geocoder), estação mais próxima e distância | — |
| 2. Destino & favorito | Próxima estação da lista (favorito primeiro, depois da mais próxima para a mais distante) | Salva o destino como único favorito |
| 3. Viagem | Inicia/para o GPS real | Inicia/para a simulação |

## Lógica de sentido com dupla validação

1. Sentido planejado: comparando o índice da estação atual com o do destino (`destino > atual` = sentido Belém; `destino < atual` = sentido Marituba).
2. Sentido de movimento: velocidade acima de 12 km/h e rumo (`bearing`) coerente com o azimute da via.
3. Dupla confirmação: só trava e anuncia o sentido após **duas leituras consecutivas** coerentes. Parada ou oscilação de sinal mantém o último sentido confirmado — nunca inverte com uma leitura isolada.

Avisos de aproximação, próxima estação e "falta 1 estação para desembarcar" usam esse sentido, com vibração no alerta final.

## Precisão de localização

- `PRIORITY_HIGH_ACCURACY`, intervalo de 2 s, deslocamento mínimo 1 m.
- `getCurrentLocation` no início (evita posição em cache) e descarte de leituras com erro acima de 80 m.
- Distância por Haversine, corredor por distância ponto-segmento entre estações consecutivas.

## Acessibilidade visual

- Fundo `#0D1117`, cartões `#1D4ED8` (cobalto), `#000000` e `#FFD60A` (âmbar), texto branco/preto — contraste ≥ 7:1 (WCAG AAA).
- 3 botões ocupando toda a altura útil, texto escalável em `sp`, bordas de 2 dp e alvos de toque muito acima de 48 dp.

## Estrutura

```
PontoCerto/
  app/src/main/java/com/example/pontocerto/
    data/        modelos, repositório das 19 estações, preferências
    service/     GPS, TTS fallback, anúncios e status de acessibilidade
    ui/          MainViewModel e tela principal (3 cards)
  server/        painel Python de telemetria (ESP32 / BLE)
  esp32_beacon/  firmware do beacon
Arquivos/        dados de referência das estações
```

## Build

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requisitos: JDK 21, Android SDK 37.

## Autoria

- **wlx** ([@weslinsx](https://github.com/weslinsx)) — criação do aplicativo, arquitetura TalkBack-First, detecção de sentido e geolocalização.

## Versão

`v0.1.0` — primeira entrega funcional. Relatório final da disciplina fica para a próxima etapa.
