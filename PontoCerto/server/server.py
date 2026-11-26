#!/usr/bin/env python3
"""
=============================================================================
PROJETO PONTO CERTO - FACI WYDEN (Belém/PA)
Disciplina: Aplic. de Cloud, IoT e Indústria 4.0 em Python
Prof. Msc. Jeffson Celeiro Sousa

Servidor Python de Telemetria IoT + Interface Web Interativa
Comunicação 100% Sem Fio:
  1. Microcontrolador ESP32 via Bluetooth Low Energy (BLE)
  2. Painel Web de Controle para Apresentação (http://localhost:8765)
  3. Aplicativo Android "Ponto Certo" via REST API JSON (/api/telemetry)
=============================================================================
"""

import asyncio
import collections
import json
import math
import os
import queue
import statistics
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

try:
    from bleak import BleakScanner, BleakClient
    HAS_BLEAK = True
except ImportError:
    HAS_BLEAK = False

HOST = "0.0.0.0"
PORT = 8765

SERVICE_UUID           = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
CHARACTERISTIC_UUID_RX = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
CHARACTERISTIC_UUID_TX = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

# Constantes do Modelo de Propagação RF Bluetooth
BLE_TX_REF_POWER = -42.0  # RSSI a ~0.5m
BLE_PATH_LOSS_N  = 2.0    # Coeficiente urbano / ambiente fechado

PRESET_LINES = [
    {
        "id": "bus_639_arsenal",
        "bus_type": "URBAN",
        "bus_code": "639",
        "bus_name": "Arsenal / Pres. Vargas",
        "corridor": "Rua dos Mundurucus / Av. Nazaré / Ver-o-Peso",
        "badge": "Ônibus Urbano",
    },
    {
        "id": "bus_638_marex",
        "bus_type": "URBAN",
        "bus_code": "638",
        "bus_name": "Marex / Felipe Patroni",
        "corridor": "Av. Júlio César / Almirante Barroso / Ver-o-Peso",
        "badge": "Ônibus Urbano",
    },
    {
        "id": "bus_548_marambaia",
        "bus_type": "URBAN",
        "bus_code": "548",
        "bus_name": "Marambaia / Ver-o-Peso",
        "corridor": "Av. Tavares Bastos / Pedro Álvares Cabral / Doca",
        "badge": "Ônibus Urbano",
    },
    {
        "id": "bus_226_canudos",
        "bus_type": "URBAN",
        "bus_code": "226",
        "bus_name": "Canudos / Praça Amazonas",
        "corridor": "Av. Ceará / São Brás / Gentil Bittencourt",
        "badge": "Ônibus Urbano",
    },
    {
        "id": "bus_306_ufpa",
        "bus_type": "URBAN",
        "bus_code": "306",
        "bus_name": "UFPA / Cidade Nova",
        "corridor": "Av. Perimetral / Almirante Barroso / Entroncamento",
        "badge": "Ônibus Urbano",
    },
    {
        "id": "bus_872_icoaraci",
        "bus_type": "URBAN",
        "bus_code": "872",
        "bus_name": "Icoaraci / São Brás",
        "corridor": "Av. Augusto Montenegro / Entroncamento / Almirante Barroso",
        "badge": "Ônibus Urbano",
    },
    {
        "id": "brt_sao_bras",
        "bus_type": "BRT",
        "bus_code": "BRT-01",
        "bus_name": "Terminal São Brás",
        "corridor": "Corredor Almirante Barroso",
        "badge": "Terminal BRT",
    },
    {
        "id": "brt_bosque",
        "bus_type": "BRT",
        "bus_code": "BRT-05",
        "bus_name": "Estação Bosque Rodrigues Alves",
        "corridor": "Corredor Almirante Barroso",
        "badge": "Estação BRT",
    },
    {
        "id": "brt_humaita",
        "bus_type": "BRT",
        "bus_code": "BRT-03",
        "bus_name": "Estação Humaitá",
        "corridor": "Corredor Almirante Barroso",
        "badge": "Estação BRT",
    },
    {
        "id": "brt_marambaia",
        "bus_type": "BRT",
        "bus_code": "BRT-07",
        "bus_name": "Terminal Marambaia",
        "corridor": "Corredor Augusto Montenegro",
        "badge": "Terminal BRT",
    },
    {
        "id": "brt_mangueirao",
        "bus_type": "BRT",
        "bus_code": "BRT-11",
        "bus_name": "Terminal Mangueirão",
        "corridor": "Corredor Augusto Montenegro",
        "badge": "Terminal BRT",
    },
]


class BleKalmanFilter:
    """Filtro de Kalman 1D calibrado para atenuação de RF Bluetooth (2.4 GHz).
    
    Elimina as flutuações e nulos de onda estacionária causados por multipercurso 
    (multipath fading) e salto de canal (BLE canais 37, 38 e 39).
    """
    def __init__(self, q: float = 0.08, r: float = 3.5):
        self.q = q      # Process noise covariance (acompanha deslocamento físico real)
        self.r = r      # Measurement noise covariance (rejeita ruídos e saltos espúrios de RF)
        self.x = None   # Estado estimado (RSSI suavizado em dBm)
        self.p = 1.0    # Incerteza da estimativa
        self.k = 0.0    # Ganho de Kalman

    def reset(self, initial_val: float = None):
        self.x = initial_val
        self.p = 1.0

    def update(self, measurement: float) -> float:
        if self.x is None:
            self.x = float(measurement)
            self.p = 1.0
            return self.x
        
        # Etapa de Predição
        self.p = self.p + self.q
        
        # Etapa de Atualização (Ganho de Kalman)
        self.k = self.p / (self.p + self.r)
        self.x = self.x + self.k * (measurement - self.x)
        self.p = (1.0 - self.k) * self.p
        return self.x


class TelemetryState:
    def __init__(self):
        self.lock = threading.Lock()
        self.command_queue = queue.Queue()
        self.esp32_connected = False
        self.protocol = "Bluetooth Low Energy (Beacon RF)"
        self.last_esp32_rx_time = 0.0

        # Estado atual
        self.device = "ESP32-D0WDQ6"
        self.mac = "EC:64:C9:98:85:66"
        self.bus_id = "apk_urban_639"
        self.bus_type = "URBAN"
        self.bus_code = "639"
        self.bus_name = "Arsenal / Pres. Vargas"
        self.corridor = "Rua dos Mundurucus / Av. Nazaré / Ver-o-Peso"
        
        # Modo padrão: RÁDIO REAL (RSSI Bluetooth ao vivo da antena)
        self.mode = "REAL_RF"  
        self.raw_bt_rssi = -42
        self.smoothed_rssi = -42.0
        self.rssi_dbm = -42
        self.tx_power_dbm = int(BLE_TX_REF_POWER)
        self.distance_m = 1.5
        self.proximity = "ARRIVED"
        self.ssid_beacon = "PontoCerto_ESP32 (BLE)"
        self.uptime_s = 0
        self.seq = 0
        self.auto_approach_active = False
        self.recent_logs = []

        # Pipeline de Estabilização Anti-Jitter: Mediana Deslizante (N=11) + Kalman 1D + Histerese
        self.rssi_window = collections.deque(maxlen=11)
        self.kalman = BleKalmanFilter(q=0.08, r=3.5)
        self.filter_info = "Mediana Móvel (N=11) + Kalman 1D + Histerese"

    def add_log(self, msg: str):
        ts = time.strftime("%H:%M:%S")
        entry = f"[{ts}] {msg}"
        self.recent_logs.insert(0, entry)
        if len(self.recent_logs) > 25:
            self.recent_logs.pop()

    def calc_rssi_from_dist(self, dist_m: float) -> int:
        d = max(1.0, float(dist_m))
        return int(round(BLE_TX_REF_POWER - (10.0 * BLE_PATH_LOSS_N * math.log10(d / 12.0 + 0.1))))

    def calc_dist_from_rssi(self, rssi_dbm: float) -> float:
        diff = BLE_TX_REF_POWER - float(rssi_dbm)
        if diff <= 0:
            raw_dist = 1.5
        else:
            # Modelo Log-Distância calibrado: curva monotônica contínua e suave
            raw_dist = 1.5 + (math.pow(10.0, diff / 22.0) - 1.0) * 4.0
        return max(1.5, min(250.0, round(raw_dist, 1)))

    def calc_proximity(self, dist_m: float) -> str:
        # Histerese de Schmitt Trigger para prevenir oscilação de estados nas fronteiras
        curr = getattr(self, "proximity", "ARRIVED")
        if curr == "ARRIVED":
            if dist_m > 38.0:
                return "APPROACHING"
            return "ARRIVED"
        elif curr == "APPROACHING":
            if dist_m <= 32.0:
                return "ARRIVED"
            elif dist_m > 145.0:
                return "FAR"
            return "APPROACHING"
        else:  # FAR
            if dist_m <= 135.0:
                return "APPROACHING"
            return "FAR"

    def send_to_esp32(self, cmd: str):
        self.command_queue.put(cmd.strip())
        return True

    def select_preset(self, preset_id: str):
        preset = next((p for p in PRESET_LINES if p["id"] == preset_id), None)
        if not preset:
            return False
        return self.set_apk_bus(
            bus_type=preset["bus_type"],
            bus_code=preset["bus_code"],
            bus_name=preset["bus_name"],
            corridor=preset["corridor"],
            source="Painel Web",
        )

    def set_apk_bus(
        self,
        bus_type: str,
        bus_code: str,
        bus_name: str,
        corridor: str,
        source: str = "App Android (APK)",
    ):
        clean_type = (bus_type or "URBAN").strip().upper()
        clean_code = (bus_code or "638").strip()
        clean_name = (bus_name or "Linha selecionada").strip()
        clean_corridor = (corridor or "Rota monitorada pelo APK").strip()
        with self.lock:
            self.bus_id = f"apk_{clean_type.lower()}_{clean_code.lower()}"
            self.bus_type = clean_type
            self.bus_code = clean_code
            self.bus_name = clean_name
            self.corridor = clean_corridor
            self.add_log(
                f"[{source}] ESP32 moldado via Bluetooth para -> [{self.bus_type}] {self.bus_code} - {self.bus_name}"
            )
        cmd = f"BUS:{clean_type}|{clean_code}|{clean_name}|{clean_corridor}"
        self.send_to_esp32(cmd)
        return True

    def set_distance(self, dist_m: float, stop_auto: bool = True):
        dist_m = max(1.0, min(350.0, float(dist_m)))
        with self.lock:
            if stop_auto:
                self.auto_approach_active = False
            self.mode = "SIM"
            self.distance_m = round(dist_m, 1)
            self.rssi_dbm = self.calc_rssi_from_dist(self.distance_m)
            self.proximity = self.calc_proximity(self.distance_m)

    def set_mode(self, mode: str):
        if mode not in ("SIM", "REAL_RF"):
            return
        with self.lock:
            self.mode = mode
            if mode == "REAL_RF":
                self.auto_approach_active = False
                if self.smoothed_rssi is not None:
                    self.rssi_dbm = int(round(self.smoothed_rssi))
                    self.distance_m = self.calc_dist_from_rssi(self.smoothed_rssi)
                    self.proximity = self.calc_proximity(self.distance_m)
            self.add_log(f"Modo alterado para: {mode}")

    def start_auto_approach(self):
        with self.lock:
            self.mode = "SIM"
            self.distance_m = 210.0
            self.auto_approach_active = True
            self.add_log(
                f"Simulação automática iniciada para {self.bus_code} - {self.bus_name} (210m -> 8m)"
            )

    def to_dict(self):
        with self.lock:
            esp_alive = (time.time() - self.last_esp32_rx_time) < 5.0 and self.esp32_connected
            return {
                "esp32_connected": esp_alive,
                "protocol": self.protocol,
                "device": self.device,
                "mac": self.mac,
                "bus_id": self.bus_id,
                "bus_type": self.bus_type,
                "bus_code": self.bus_code,
                "bus_name": self.bus_name,
                "corridor": self.corridor,
                "mode": self.mode,
                "rssi_dbm": self.rssi_dbm,
                "raw_bt_rssi": self.raw_bt_rssi,
                "filter_info": self.filter_info,
                "tx_power_dbm": self.tx_power_dbm,
                "distance_m": self.distance_m,
                "proximity": self.proximity,
                "ssid_beacon": self.ssid_beacon,
                "uptime_s": self.uptime_s,
                "seq": self.seq,
                "auto_approach_active": self.auto_approach_active,
                "logs": list(self.recent_logs[:12]),
                "presets": PRESET_LINES,
                "timestamp": int(time.time() * 1000),
            }


STATE = TelemetryState()


def ble_scanner_worker():
    """Escuta contínua de pacotes de rádio Bluetooth BLE do ESP32 para medir RSSI real."""
    if not HAS_BLEAK:
        STATE.add_log("Aviso: Bleak não encontrado. Usando modo simulado.")
        return

    async def run_scanner():
        STATE.add_log("Monitor de Rádio Bluetooth BLE ativo (Filtro Anti-Jitter Kalman + Mediana N=11)!")

        def on_packet(device, adv):
            name = device.name or adv.local_name or ""
            if "PontoCerto" in name or "PC_" in name or (device.address and device.address.upper().startswith("EC:64:C9")):
                raw = float(adv.rssi)
                with STATE.lock:
                    STATE.last_esp32_rx_time = time.time()
                    STATE.esp32_connected = True
                    STATE.mac = device.address
                    STATE.raw_bt_rssi = int(raw)
                    STATE.seq += 1

                    # Estágio 1: Filtro Mediano Deslizante (N=11)
                    # Rejeita instantaneamente os nulos de multipercurso e salto de canal (canais 37, 38, 39)
                    STATE.rssi_window.append(raw)
                    median_rssi = statistics.median(STATE.rssi_window)

                    # Estágio 2: Filtro de Kalman 1D
                    # Rastreia suavemente a locomoção física real sem oscilação
                    smoothed = STATE.kalman.update(median_rssi)
                    STATE.smoothed_rssi = smoothed

                    # Estágio 3: Mapeamento de Distância e Histerese Anti-Jitter
                    if STATE.mode == "REAL_RF":
                        STATE.rssi_dbm = int(round(smoothed))
                        raw_dist = STATE.calc_dist_from_rssi(smoothed)
                        # Zona morta de 0.4m para travar oscilações do último decimal
                        if abs(raw_dist - STATE.distance_m) >= 0.4:
                            STATE.distance_m = raw_dist
                        STATE.proximity = STATE.calc_proximity(STATE.distance_m)

        while True:
            try:
                scanner = BleakScanner(detection_callback=on_packet)
                await scanner.start()
                # Mantém o scanner escutando os pacotes continuamente
                while True:
                    await asyncio.sleep(2.0)
                    # Verifica se o ESP32 parou de emitir rádio (> 5 segundos)
                    with STATE.lock:
                        if (time.time() - STATE.last_esp32_rx_time) >= 5.0 and STATE.esp32_connected:
                            STATE.esp32_connected = False
                            STATE.add_log("Sinal de rádio do ESP32 fora de alcance / desconectado.")
            except Exception as e:
                with STATE.lock:
                    STATE.esp32_connected = False
                await asyncio.sleep(2.0)

    asyncio.run(run_scanner())


def ble_command_worker():
    """Thread dedicada para enviar comandos de reprogramação do ônibus para o ESP32 via BLE."""
    if not HAS_BLEAK:
        return

    async def run_cmd_loop():
        while True:
            try:
                # Aguarda um comando na fila
                cmd = await asyncio.to_thread(STATE.command_queue.get)
                target = STATE.mac or "EC:64:C9:98:85:66"
                
                # Conecta brevemente via GATT, escreve o comando e desconecta
                async with BleakClient(target, timeout=4.0) as client:
                    await client.write_gatt_char(
                        CHARACTERISTIC_UUID_RX,
                        (cmd + "\n").encode("utf-8")
                    )
                    with STATE.lock:
                        STATE.add_log(f"Comando sem fio '{cmd}' entregue ao ESP32 via BLE!")
            except Exception as e:
                with STATE.lock:
                    STATE.add_log(f"Envio de comando BLE: {e}")
            await asyncio.sleep(0.5)

    asyncio.run(run_cmd_loop())


def simulation_worker():
    """Thread para simulação suave quando o usuário ativa o botão 'Simular Viagem'."""
    while True:
        time.sleep(0.6)
        with STATE.lock:
            auto = STATE.auto_approach_active
            curr_dist = STATE.distance_m
            STATE.uptime_s += 1

        if auto:
            if curr_dist > 10.0:
                step = 10.0 if curr_dist > 90.0 else 5.0
                new_dist = max(8.0, curr_dist - step)
                STATE.set_distance(new_dist, stop_auto=False)
                if new_dist <= 8.0:
                    with STATE.lock:
                        STATE.auto_approach_active = False
                        STATE.add_log(
                            f"Ônibus {STATE.bus_code} ({STATE.bus_name}) chegou ao ponto! (8.0m)"
                        )
            else:
                with STATE.lock:
                    STATE.auto_approach_active = False


DASHBOARD_HTML = """<!DOCTYPE html>
<html lang="pt-BR">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>Ponto Certo • Telemetria IoT & Proximidade BLE</title>
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet">
  <style>
    :root {
      --bg: #07090e;
      --card-bg: rgba(18, 21, 28, 0.72);
      --card-border: rgba(255, 255, 255, 0.08);
      --border-subtle: rgba(255, 255, 255, 0.04);
      --text: #f5f5f7;
      --text-muted: #86868b;
      --text-tertiary: #545458;
      --green: #30d158;
      --green-glow: rgba(48, 209, 88, 0.25);
      --amber: #ff9f0a;
      --amber-glow: rgba(255, 159, 10, 0.25);
      --blue: #0a84ff;
      --blue-glow: rgba(10, 132, 255, 0.25);
      --radius-sm: 10px;
      --radius-md: 16px;
      --radius-lg: 24px;
      --radius-full: 9999px;
      --ease-apple: cubic-bezier(0.16, 1, 0.3, 1);
    }

    * { box-sizing: border-box; margin: 0; padding: 0; }

    body {
      font-family: -apple-system, BlinkMacSystemFont, "Plus Jakarta Sans", "SF Pro Display", "SF Pro Text", system-ui, sans-serif;
      background-color: var(--bg);
      background-image: 
        radial-gradient(circle 800px at 15% -10%, rgba(10, 132, 255, 0.09), transparent),
        radial-gradient(circle 600px at 85% 110%, rgba(48, 209, 88, 0.05), transparent);
      color: var(--text);
      min-height: 100vh;
      padding: 18px 24px 24px;
      -webkit-font-smoothing: antialiased;
      -moz-osx-font-smoothing: grayscale;
    }

    .container {
      max-width: 1240px;
      margin: 0 auto;
    }

    /* TOP HEADER: Apple Minimalist Nav */
    header {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: center;
      gap: 16px;
      padding: 0 4px 16px 4px;
    }

    .brand-group {
      display: flex;
      align-items: center;
      gap: 12px;
    }

    .brand-icon {
      width: 40px;
      height: 40px;
      border-radius: var(--radius-md);
      background: linear-gradient(135deg, rgba(255,255,255,0.12), rgba(255,255,255,0.02));
      border: 1px solid var(--card-border);
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 1.2rem;
      box-shadow: 0 8px 24px rgba(0,0,0,0.3);
    }

    .brand-titles h1 {
      font-size: 1.35rem;
      font-weight: 700;
      letter-spacing: -0.025em;
      color: var(--text);
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .brand-titles p {
      color: var(--text-muted);
      font-size: 0.8rem;
      margin-top: 1px;
      font-weight: 500;
      letter-spacing: -0.01em;
    }

    .dynamic-island-badge {
      display: inline-flex;
      align-items: center;
      gap: 12px;
      background: rgba(18, 21, 28, 0.75);
      backdrop-filter: blur(20px);
      -webkit-backdrop-filter: blur(20px);
      border: 1px solid var(--card-border);
      padding: 6px 16px;
      border-radius: var(--radius-full);
      box-shadow: 0 10px 30px rgba(0,0,0,0.35);
      transition: all 0.3s var(--ease-apple);
    }

    .status-dot {
      width: 9px;
      height: 9px;
      border-radius: 50%;
      background: var(--blue);
      box-shadow: 0 0 10px var(--blue);
      transition: all 0.3s ease;
    }

    .status-dot.online {
      background: var(--green);
      box-shadow: 0 0 12px var(--green);
    }

    .status-dot.offline {
      background: var(--amber);
      box-shadow: 0 0 12px var(--amber);
    }

    .badge-label-main {
      font-size: 0.8rem;
      font-weight: 600;
      color: var(--text);
    }

    .badge-label-sub {
      font-size: 0.7rem;
      color: var(--text-muted);
      font-family: 'JetBrains Mono', monospace;
    }

    /* GRID LAYOUT */
    .grid {
      display: grid;
      grid-template-columns: 1.15fr 0.85fr;
      gap: 20px;
    }

    @media (max-width: 960px) {
      .grid { grid-template-columns: 1fr; }
    }

    /* GLASS CARDS */
    .card {
      background: var(--card-bg);
      backdrop-filter: blur(30px) saturate(190%);
      -webkit-backdrop-filter: blur(30px) saturate(190%);
      border: 1px solid var(--card-border);
      border-radius: var(--radius-lg);
      padding: 22px;
      box-shadow: 0 20px 48px -12px rgba(0, 0, 0, 0.6);
      display: flex;
      flex-direction: column;
      gap: 16px;
    }

    .card-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .section-eyebrow {
      font-size: 0.7rem;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--text-muted);
    }

    .pill-counter {
      font-family: 'JetBrains Mono', monospace;
      font-size: 0.7rem;
      background: rgba(255, 255, 255, 0.05);
      border: 1px solid var(--border-subtle);
      padding: 2px 8px;
      border-radius: var(--radius-full);
      color: var(--text-muted);
    }

    /* HERO: AIRTAG PRECISION FINDING WIDGET */
    .proximity-hero {
      background: linear-gradient(180deg, rgba(255, 255, 255, 0.02) 0%, rgba(255, 255, 255, 0.005) 100%);
      border: 1px solid var(--border-subtle);
      border-radius: var(--radius-md);
      padding: 16px 20px;
      display: flex;
      flex-direction: column;
      align-items: center;
      text-align: center;
      position: relative;
      overflow: hidden;
    }

    .status-capsule {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      padding: 5px 14px;
      border-radius: var(--radius-full);
      font-size: 0.75rem;
      font-weight: 700;
      letter-spacing: 0.04em;
      margin-bottom: 12px;
      background: rgba(48, 209, 88, 0.12);
      border: 1px solid rgba(48, 209, 88, 0.25);
      color: var(--green);
      transition: all 0.3s var(--ease-apple);
    }

    /* AIRTAG RADAR VISUALIZER */
    .airtag-visualizer {
      position: relative;
      width: 130px;
      height: 130px;
      display: flex;
      align-items: center;
      justify-content: center;
      margin-bottom: 12px;
    }

    .airtag-ring {
      position: absolute;
      border-radius: 50%;
      border: 1px solid rgba(48, 209, 88, 0.2);
      transition: border-color 0.4s ease;
    }

    .airtag-ring.r1 {
      width: 100%;
      height: 100%;
      animation: pulse-ring 3s cubic-bezier(0.215, 0.61, 0.355, 1) infinite;
    }

    .airtag-ring.r2 {
      width: 72%;
      height: 72%;
      animation: pulse-ring 3s cubic-bezier(0.215, 0.61, 0.355, 1) infinite 0.75s;
    }

    .airtag-core {
      width: 64px;
      height: 64px;
      border-radius: 50%;
      background: radial-gradient(circle at 30% 30%, #222630, #0f1218);
      border: 1.5px solid rgba(48, 209, 88, 0.5);
      color: #fff;
      font-size: 1.15rem;
      font-weight: 800;
      display: flex;
      align-items: center;
      justify-content: center;
      box-shadow: 0 0 24px var(--green-glow);
      z-index: 3;
      transition: all 0.4s var(--ease-apple);
    }

    @keyframes pulse-ring {
      0% { transform: scale(0.5); opacity: 0.9; }
      80% { transform: scale(1.15); opacity: 0; }
      100% { transform: scale(1.15); opacity: 0; }
    }

    .hero-number-wrap {
      display: flex;
      align-items: baseline;
      justify-content: center;
      gap: 4px;
      margin-bottom: 2px;
    }

    .hero-number {
      font-size: 2.8rem;
      font-weight: 800;
      letter-spacing: -0.04em;
      line-height: 1;
      color: var(--text);
    }

    .hero-unit {
      font-size: 1.2rem;
      font-weight: 500;
      color: var(--text-muted);
    }

    .hero-headline {
      font-size: 1.05rem;
      font-weight: 700;
      color: var(--text);
      letter-spacing: -0.015em;
      margin-bottom: 2px;
    }

    .hero-sub {
      font-size: 0.8rem;
      color: var(--text-muted);
    }

    /* 3-COLUMN METRICS: Apple Control Center style */
    .metrics-grid {
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      gap: 10px;
    }

    .metric-card {
      background: rgba(255, 255, 255, 0.025);
      border: 1px solid var(--border-subtle);
      border-radius: var(--radius-md);
      padding: 12px 10px;
      text-align: center;
      transition: all 0.2s var(--ease-apple);
    }

    .metric-card:hover {
      background: rgba(255, 255, 255, 0.04);
      border-color: var(--card-border);
    }

    .metric-value {
      font-size: 1.15rem;
      font-weight: 700;
      letter-spacing: -0.02em;
      color: var(--text);
      margin-bottom: 2px;
    }

    .metric-label {
      font-size: 0.68rem;
      color: var(--text-muted);
      text-transform: uppercase;
      letter-spacing: 0.06em;
      font-weight: 600;
    }

    .metric-detail {
      font-size: 0.66rem;
      color: var(--text-tertiary);
      margin-top: 2px;
      font-family: 'JetBrains Mono', monospace;
    }

    /* SEGMENTED CONTROL (Apple iOS Settings style) */
    .segmented-control {
      display: flex;
      background: rgba(118, 118, 128, 0.16);
      border: 1px solid var(--border-subtle);
      border-radius: 12px;
      padding: 3px;
      gap: 3px;
    }

    .segment-btn {
      flex: 1;
      background: transparent;
      border: none;
      color: var(--text-muted);
      padding: 8px 10px;
      border-radius: 9px;
      font-size: 0.8rem;
      font-weight: 600;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 6px;
      transition: all 0.2s var(--ease-apple);
    }

    .segment-btn.active {
      background: rgba(255, 255, 255, 0.12);
      color: var(--text);
      box-shadow: 0 3px 10px rgba(0, 0, 0, 0.35);
    }

    /* ACTION PILLS */
    .action-grid {
      display: grid;
      grid-template-columns: repeat(2, 1fr);
      gap: 8px;
    }

    .action-btn {
      background: rgba(255, 255, 255, 0.035);
      border: 1px solid var(--card-border);
      color: var(--text);
      padding: 10px 12px;
      border-radius: var(--radius-md);
      font-size: 0.82rem;
      font-weight: 600;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 6px;
      transition: all 0.2s var(--ease-apple);
    }

    .action-btn:hover {
      background: rgba(255, 255, 255, 0.07);
      border-color: rgba(255, 255, 255, 0.18);
      transform: translateY(-1px);
    }

    .action-btn:active {
      transform: scale(0.98);
    }

    .action-btn.primary {
      background: linear-gradient(135deg, rgba(10, 132, 255, 0.25), rgba(191, 90, 242, 0.25));
      border: 1px solid rgba(10, 132, 255, 0.4);
      color: #fff;
    }

    .action-btn.primary:hover {
      background: linear-gradient(135deg, rgba(10, 132, 255, 0.38), rgba(191, 90, 242, 0.38));
      border-color: rgba(10, 132, 255, 0.6);
    }

    /* RANGE SLIDER: iOS style */
    .slider-box {
      background: rgba(255, 255, 255, 0.02);
      border: 1px solid var(--border-subtle);
      border-radius: var(--radius-md);
      padding: 12px 16px;
    }

    .slider-top {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: 0.8rem;
      margin-bottom: 8px;
    }

    .slider-label {
      color: var(--text-muted);
      font-weight: 500;
    }

    .slider-value {
      color: var(--blue);
      font-weight: 700;
      font-family: 'JetBrains Mono', monospace;
    }

    input[type="range"] {
      width: 100%;
      height: 5px;
      appearance: none;
      -webkit-appearance: none;
      background: rgba(255, 255, 255, 0.1);
      border-radius: var(--radius-full);
      outline: none;
      cursor: pointer;
    }

    input[type="range"]::-webkit-slider-thumb {
      -webkit-appearance: none;
      appearance: none;
      width: 16px;
      height: 16px;
      border-radius: 50%;
      background: #ffffff;
      box-shadow: 0 2px 8px rgba(0,0,0,0.4);
      cursor: pointer;
      transition: transform 0.15s ease;
    }

    input[type="range"]::-webkit-slider-thumb:hover {
      transform: scale(1.15);
    }

    /* RIGHT CARD: NOW PLAYING / LIVE ACTIVITY CARD */
    .live-activity-card {
      background: linear-gradient(145deg, rgba(255, 255, 255, 0.035), rgba(255, 255, 255, 0.01));
      border: 1px solid rgba(255, 255, 255, 0.09);
      border-radius: var(--radius-md);
      padding: 18px;
      position: relative;
    }

    .live-badge-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 10px;
    }

    .live-tag {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      font-size: 0.68rem;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--green);
    }

    .live-tag::before {
      content: '';
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: var(--green);
      box-shadow: 0 0 8px var(--green);
    }

    .activity-title {
      font-size: 1.3rem;
      font-weight: 800;
      letter-spacing: -0.025em;
      color: var(--text);
      margin-bottom: 3px;
    }

    .activity-route {
      font-size: 0.85rem;
      color: var(--text-muted);
      line-height: 1.4;
      margin-bottom: 14px;
    }

    .activity-chips {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
    }

    .chip {
      background: rgba(255, 255, 255, 0.05);
      border: 1px solid var(--border-subtle);
      padding: 4px 10px;
      border-radius: var(--radius-full);
      font-size: 0.72rem;
      font-weight: 600;
      color: var(--text-muted);
    }

    /* TIMELINE (Apple Maps style) */
    .timeline {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }

    .timeline-item {
      display: flex;
      gap: 12px;
      align-items: flex-start;
    }

    .timeline-dot {
      width: 24px;
      height: 24px;
      border-radius: 50%;
      background: rgba(255, 255, 255, 0.06);
      border: 1px solid var(--card-border);
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 0.72rem;
      font-weight: 700;
      color: var(--text-muted);
      flex-shrink: 0;
      margin-top: 1px;
    }

    .timeline-content {
      font-size: 0.82rem;
      color: var(--text-muted);
      line-height: 1.45;
    }

    .timeline-content strong {
      color: var(--text);
      font-weight: 600;
    }

    /* macOS CONSOLE TERMINAL */
    .console-wrap {
      background: #000000;
      border: 1px solid rgba(255, 255, 255, 0.07);
      border-radius: var(--radius-md);
      overflow: hidden;
    }

    .console-header {
      background: rgba(255, 255, 255, 0.03);
      padding: 6px 12px;
      display: flex;
      align-items: center;
      gap: 6px;
      border-bottom: 1px solid rgba(255, 255, 255, 0.04);
    }

    .traffic-light {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: #ff5f56;
    }
    .traffic-light.yellow { background: #ffbd2e; }
    .traffic-light.green { background: #27c93f; }

    .console-title {
      font-size: 0.68rem;
      color: var(--text-tertiary);
      font-family: 'JetBrains Mono', monospace;
      margin-left: 6px;
    }

    .console-body {
      padding: 10px 12px;
      height: 95px;
      overflow-y: auto;
      font-family: 'JetBrains Mono', Consolas, monospace;
      font-size: 0.72rem;
      color: #98c379;
      line-height: 1.5;
    }

    .console-body::-webkit-scrollbar {
      width: 4px;
    }
    .console-body::-webkit-scrollbar-thumb {
      background: rgba(255, 255, 255, 0.15);
      border-radius: 4px;
    }

    .log-line {
      margin-bottom: 2px;
      white-space: pre-wrap;
    }
  </style>
</head>
<body>
  <div class="container">
    <header>
      <div class="brand-group">
        <div class="brand-icon">📡</div>
        <div class="brand-titles">
          <h1>Ponto Certo</h1>
          <p>IoT Proximity Engine • FACI Wyden • Prof. Msc. Jeffson Celeiro Sousa</p>
        </div>
      </div>
      <div class="dynamic-island-badge">
        <div id="hwDot" class="status-dot"></div>
        <div>
          <div id="hwTitle" class="badge-label-main">Aguardando ESP32...</div>
          <div id="hwSub" class="badge-label-sub">Beacon BLE Contínuo (50ms)</div>
        </div>
      </div>
    </header>

    <div class="grid">
      <!-- COLUNA ESQUERDA: RADAR E TELEMETRIA -->
      <div class="card">
        <div class="card-header">
          <span class="section-eyebrow">Rastreamento de Proximidade BLE</span>
          <span id="seqBadge" class="pill-counter">Pacote #0</span>
        </div>

        <div class="proximity-hero">
          <div id="proxPill" class="status-capsule">
            ● NO PONTO — EMBARQUE IMEDIATO
          </div>

          <div class="airtag-visualizer">
            <div id="ring1" class="airtag-ring r1"></div>
            <div id="ring2" class="airtag-ring r2"></div>
            <div id="radarCenter" class="airtag-core">639</div>
          </div>

          <div class="hero-number-wrap">
            <div id="valDist" class="hero-number">1.8</div>
            <div class="hero-unit">m</div>
          </div>

          <div id="busHeadline" class="hero-headline">639 • Arsenal / Pres. Vargas</div>
          <div id="busCorridor" class="hero-sub">Rua dos Mundurucus / Av. Nazaré / Ver-o-Peso</div>
        </div>

        <div class="metrics-grid">
          <div class="metric-card">
            <div class="metric-label">Distância</div>
            <div id="valDistMetric" class="metric-value">1.8 m</div>
            <div class="metric-detail">Calibrada RF</div>
          </div>
          <div class="metric-card">
            <div class="metric-label">Sinal RSSI</div>
            <div id="valRssi" class="metric-value">-42 dBm</div>
            <div id="valRssiSub" class="metric-detail">Bruto: -42 dBm</div>
          </div>
          <div class="metric-card">
            <div class="metric-label">Filtro Ativo</div>
            <div id="valFilter" class="metric-value" style="color: var(--green);">Kalman 1D</div>
            <div class="metric-detail">Mediana N=11</div>
          </div>
        </div>

        <div class="segmented-control">
          <button id="btnModeRf" class="segment-btn active" onclick="setMode('REAL_RF')">
            📶 Rádio Bluetooth Ao Vivo
          </button>
          <button id="btnModeSim" class="segment-btn" onclick="setMode('SIM')">
            🎛️ Simulação Manual
          </button>
        </div>

        <div class="action-grid">
          <button class="action-btn" onclick="setDistance(1.8)">
            🟢 No Ponto (1.8m)
          </button>
          <button class="action-btn" onclick="setDistance(75)">
            🟡 Chegando (75m)
          </button>
          <button class="action-btn" onclick="setDistance(210)">
            🔵 Distante (210m)
          </button>
          <button class="action-btn primary" onclick="startAutoApproach()">
            ▶️ Simular Viagem
          </button>
        </div>

        <div class="slider-box">
          <div class="slider-top">
            <span class="slider-label">Ajuste de Distância (Modo Simulado):</span>
            <span id="sliderLabel" class="slider-value">1.8 metros</span>
          </div>
          <input id="distSlider" type="range" min="1" max="250" step="1" value="2"
                 oninput="onSliderInput(this.value)" onchange="setDistance(this.value)" />
        </div>

        <div class="console-wrap">
          <div class="console-header">
            <div class="traffic-light"></div>
            <div class="traffic-light yellow"></div>
            <div class="traffic-light green"></div>
            <span class="console-title">telemetry-daemon.log</span>
          </div>
          <div id="logBox" class="console-body"></div>
        </div>
      </div>

      <!-- COLUNA DIREITA: LIVE ACTIVITY & SINCRONIZAÇÃO -->
      <div class="card">
        <div class="card-header">
          <span class="section-eyebrow">Sincronização em Tempo Real</span>
          <span class="pill-counter">APK ➔ ESP32</span>
        </div>

        <div class="live-activity-card">
          <div class="live-badge-row">
            <span class="live-tag">Dispositivo Moldado via BLE</span>
            <span id="valMac" style="font-family: 'JetBrains Mono', monospace; font-size: 0.72rem; color: var(--text-muted);">EC:64:C9:98:85:66</span>
          </div>

          <div id="apkActiveLine" class="activity-title">639 • Arsenal / Pres. Vargas</div>
          <div id="apkActiveRoute" class="activity-route">Rua dos Mundurucus / Av. Nazaré / Ver-o-Peso</div>

          <div class="activity-chips">
            <span id="apkActiveType" class="chip">🚌 Ônibus Urbano</span>
            <span id="apkActiveProto" class="chip">📡 Beacon BLE Contínuo (50ms)</span>
            <span class="chip">⚡ +9 dBm Potência</span>
          </div>
        </div>

        <div class="timeline">
          <div class="timeline-item">
            <div class="timeline-dot">1</div>
            <div class="timeline-content">
              <strong>Seleção no App Android:</strong> O passageiro escolhe a linha desejada na interface acessível do aplicativo.
            </div>
          </div>
          <div class="timeline-item">
            <div class="timeline-dot">2</div>
            <div class="timeline-content">
              <strong>Reprogramação Sem Fio:</strong> O comando BLE GATT molda o microcontrolador ESP32 instantaneamente com a linha selecionada.
            </div>
          </div>
          <div class="timeline-item">
            <div class="timeline-dot">3</div>
            <div class="timeline-content">
              <strong>Transmissão RF Contínua:</strong> O ESP32 emite pacotes de beacon com +9 dBm de potência a cada 50ms–80ms.
            </div>
          </div>
          <div class="timeline-item">
            <div class="timeline-dot">4</div>
            <div class="timeline-content">
              <strong>Filtragem & Acessibilidade:</strong> O filtro Kalman 1D suaviza o sinal físico real, disparando voz TTS e vibração no celular ao se aproximar.
            </div>
          </div>
        </div>

        <div style="background: rgba(255,255,255,0.02); border: 1px solid var(--border-subtle); border-radius: var(--radius-md); padding: 16px 18px; margin-top: auto;">
          <div style="font-size: 0.7rem; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.08em; font-weight: 700; margin-bottom: 4px;">
            🎓 Projeto de Extensão Universitária
          </div>
          <div style="font-size: 0.86rem; color: var(--text); font-weight: 600;">
            FACI Wyden • Engenharia da Computação & CC
          </div>
          <div style="font-size: 0.78rem; color: var(--text-muted); margin-top: 3px; line-height: 1.4;">
            Aplic. de Cloud, IoT e Indústria 4.0 em Python • Prof. Msc. Jeffson Celeiro Sousa
          </div>
        </div>
      </div>
    </div>
  </div>

  <script>
    let sliderDragging = false;

    const sliderEl = document.getElementById('distSlider');
    sliderEl.addEventListener('mousedown', () => sliderDragging = true);
    sliderEl.addEventListener('mouseup', () => sliderDragging = false);
    sliderEl.addEventListener('touchstart', () => sliderDragging = true);
    sliderEl.addEventListener('touchend', () => sliderDragging = false);

    function onSliderInput(val) {
      document.getElementById('sliderLabel').innerText = val + " metros";
    }

    async function sendCommand(payload) {
      try {
        await fetch('/api/control', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload)
        });
        fetchTelemetry();
      } catch (e) {
        console.error(e);
      }
    }

    function setDistance(dist) {
      document.getElementById('sliderLabel').innerText = Math.round(dist) + " metros";
      sliderEl.value = Math.round(dist);
      sendCommand({ action: 'set_distance', distance_m: parseFloat(dist) });
    }

    function setMode(mode) {
      sendCommand({ action: 'set_mode', mode: mode });
    }

    function startAutoApproach() {
      sendCommand({ action: 'auto_approach' });
    }

    async function fetchTelemetry() {
      try {
        const res = await fetch('/api/telemetry');
        const data = await res.json();

        const hwDot = document.getElementById('hwDot');
        const hwTitle = document.getElementById('hwTitle');
        const hwSub = document.getElementById('hwSub');
        if (data.esp32_connected) {
          hwDot.className = 'status-dot online';
          hwTitle.innerText = `${data.device} Conectado (BLE)`;
          hwSub.innerText = `MAC: ${data.mac} • RSSI: ${data.rssi_dbm} dBm`;
        } else {
          hwDot.className = 'status-dot offline';
          hwTitle.innerText = `Aguardando sinal BLE...`;
          hwSub.innerText = `Beacon PontoCerto_ESP32`;
        }

        document.getElementById('seqBadge').innerText = `Pacote #${data.seq}`;
        document.getElementById('radarCenter').innerText = data.bus_code;
        document.getElementById('busHeadline').innerText = `${data.bus_code} • ${data.bus_name}`;
        document.getElementById('busCorridor').innerText = data.corridor;
        
        // Hero Distance Display (Apple Large Number)
        document.getElementById('valDist').innerText = data.distance_m.toFixed(1);
        
        const metricDist = document.getElementById('valDistMetric');
        if (metricDist) metricDist.innerText = `${data.distance_m.toFixed(1)} m`;

        document.getElementById('valRssi').innerText = `${data.rssi_dbm} dBm`;
        const rawTxt = data.raw_bt_rssi !== undefined ? data.raw_bt_rssi : data.rssi_dbm;
        const subEl = document.getElementById('valRssiSub');
        if (subEl) subEl.innerText = `Bruto: ${rawTxt} dBm`;

        const valMacEl = document.getElementById('valMac');
        if (valMacEl) valMacEl.innerText = data.mac;

        document.getElementById('apkActiveLine').innerText = `${data.bus_code} • ${data.bus_name}`;
        document.getElementById('apkActiveRoute').innerText = data.corridor;
        document.getElementById('apkActiveType').innerText = data.bus_type === 'BRT' ? '🚏 Corredor BRT' : '🚌 Ônibus Urbano';

        if (!sliderDragging && data.mode === 'SIM') {
          sliderEl.value = Math.round(data.distance_m);
          document.getElementById('sliderLabel').innerText = `${Math.round(data.distance_m)} metros`;
        }

        const pill = document.getElementById('proxPill');
        const center = document.getElementById('radarCenter');
        const r1 = document.getElementById('ring1');
        const r2 = document.getElementById('ring2');
        let color = 'var(--blue)';
        let glow = 'var(--blue-glow)';

        if (data.proximity === 'ARRIVED') {
          color = 'var(--green)';
          glow = 'var(--green-glow)';
          pill.innerText = '● NO PONTO — EMBARQUE IMEDIATO';
          pill.style.background = 'rgba(48, 209, 88, 0.12)';
          pill.style.borderColor = 'rgba(48, 209, 88, 0.3)';
          pill.style.color = 'var(--green)';
        } else if (data.proximity === 'APPROACHING') {
          color = 'var(--amber)';
          glow = 'var(--amber-glow)';
          pill.innerText = '● SE APROXIMANDO — PREPARE-SE (< 140m)';
          pill.style.background = 'rgba(255, 159, 10, 0.12)';
          pill.style.borderColor = 'rgba(255, 159, 10, 0.3)';
          pill.style.color = 'var(--amber)';
        } else {
          color = 'var(--blue)';
          glow = 'var(--blue-glow)';
          pill.innerText = '● EM ROTA — MONITORANDO SINAL';
          pill.style.background = 'rgba(10, 132, 255, 0.12)';
          pill.style.borderColor = 'rgba(10, 132, 255, 0.3)';
          pill.style.color = 'var(--blue)';
        }

        center.style.borderColor = color;
        center.style.boxShadow = `0 0 28px ${glow}`;
        r1.style.borderColor = color;
        r2.style.borderColor = color;

        document.getElementById('btnModeRf').className = 'segment-btn' + (data.mode === 'REAL_RF' ? ' active' : '');
        document.getElementById('btnModeSim').className = 'segment-btn' + (data.mode === 'SIM' ? ' active' : '');

        const logBox = document.getElementById('logBox');
        logBox.innerHTML = data.logs.map(l => `<div class="log-line">${l}</div>`).join('');
      } catch (e) {
        console.error(e);
      }
    }

    setInterval(fetchTelemetry, 300);
    fetchTelemetry();
  </script>
</body>
</html>
"""


class TelemetryHTTPHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def _send_json(self, obj, status=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path in ("/", "/index.html"):
            body = DASHBOARD_HTML.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif parsed.path == "/api/telemetry":
            self._send_json(STATE.to_dict())
        else:
            self._send_json({"error": "Not found"}, status=404)

    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path == "/api/control":
            length = int(self.headers.get("Content-Length", 0))
            raw = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
            try:
                payload = json.loads(raw)
            except json.JSONDecodeError:
                self._send_json({"error": "Invalid JSON"}, status=400)
                return

            action = payload.get("action")
            if action == "set_apk_bus":
                STATE.set_apk_bus(
                    bus_type=payload.get("bus_type", "URBAN"),
                    bus_code=payload.get("bus_code", "638"),
                    bus_name=payload.get("bus_name", ""),
                    corridor=payload.get("corridor", ""),
                    source="App Android (APK)",
                )
            elif action == "select_bus":
                STATE.select_preset(payload.get("preset_id", ""))
            elif action == "set_distance":
                STATE.set_distance(float(payload.get("distance_m", 50.0)))
            elif action == "set_mode":
                STATE.set_mode(payload.get("mode", "REAL_RF"))
            elif action == "auto_approach":
                STATE.start_auto_approach()

            self._send_json(STATE.to_dict())
        else:
            self._send_json({"error": "Not found"}, status=404)


def main():
    STATE.add_log("Servidor Python Ponto Certo iniciado (Modo Bluetooth BLE Contínuo).")

    # Inicia a thread de escuta contínua de pacotes de rádio Bluetooth (RSSI ao vivo)
    t_scanner = threading.Thread(target=ble_scanner_worker, daemon=True)
    t_scanner.start()

    # Inicia a thread para envio de comandos de reprogramação para o ESP32
    t_cmd = threading.Thread(target=ble_command_worker, daemon=True)
    t_cmd.start()

    # Inicia a thread de simulação manual
    t_sim = threading.Thread(target=simulation_worker, daemon=True)
    t_sim.start()

    ThreadingHTTPServer.allow_reuse_address = True
    server = ThreadingHTTPServer((HOST, PORT), TelemetryHTTPHandler)
    print(f"============================================================")
    print(f" 🚀 PONTO CERTO IoT - Servidor Python Bluetooth (BLE) Ativo!")
    print(f" 📡 Conexão ESP32:      Beacon BLE Contínuo (RSSI Dinâmico Ao Vivo)")
    print(f" 🌐 Painel Web Local:   http://localhost:{PORT}")
    print(f" 📱 API para Android:   http://192.168.240.1:{PORT}/api/telemetry")
    print(f"============================================================")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nEncerrando servidor...")
        server.server_close()


if __name__ == "__main__":
    main()
