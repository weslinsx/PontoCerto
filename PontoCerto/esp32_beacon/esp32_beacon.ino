/*
 * ============================================================================
 * PROJETO PONTO CERTO - FACI WYDEN (Belém/PA)
 * Disciplina: Aplic. de Cloud, IoT e Indústria 4.0 em Python
 * Prof. Msc. Jeffson Celeiro Sousa
 *
 * Firmware IoT para ESP32 (ESP32-D0WDQ6)
 * Beacon Bluetooth Low Energy (BLE) com Potência Estabilizada (+9 dBm)
 * ============================================================================
 */

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <esp_bt.h>
#include <esp_gap_ble_api.h>

#define LED_PIN 2

#define SERVICE_UUID           "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_UUID_RX "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_UUID_TX "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

// Estado atual do Ônibus / Terminal simulado pelo ESP32
String busType     = "URBAN";
String busCode     = "639";
String busName     = "Arsenal / Pres. Vargas";
String busCorridor = "Rua dos Mundurucus / Av. Nazaré / Ver-o-Peso";

BLEServer *pServer = NULL;
BLECharacteristic *pTxCharacteristic = NULL;
bool deviceConnected = false;
bool oldDeviceConnected = false;
unsigned long lastLedToggleMs = 0;
bool ledState = false;

void updateAdvertisingPayload() {
  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->stop();

  BLEAdvertisementData advData;
  advData.setFlags(0x06); // General Discoverable Mode
  advData.setName("PontoCerto_ESP32");

  // Codifica os dados da linha no campo Manufacturer Data
  String mfg = busType + "|" + busCode + "|" + busName + "|" + busCorridor;
  advData.setManufacturerData(mfg.c_str());

  BLEAdvertisementData scanData;
  scanData.setName("PontoCerto_ESP32");
  scanData.setShortName(("PC_" + busCode).c_str());

  pAdvertising->setAdvertisementData(advData);
  pAdvertising->setScanResponseData(scanData);
  
  // Intervalo de transmissão acelerado (50ms a 80ms) para máxima densidade de pacotes e filtragem instantânea
  pAdvertising->setMinInterval(80);  // 50ms (80 * 0.625ms)
  pAdvertising->setMaxInterval(128); // 80ms (128 * 0.625ms)
  pAdvertising->start();

  Serial.println("[ESP32] Beacon BLE atualizado com potência máxima (+9dBm) -> " + mfg);
}

void processCommand(String line) {
  line.trim();
  if (line.length() == 0) return;

  if (line.startsWith("BUS:")) {
    String payload = line.substring(4);
    int p1 = payload.indexOf('|');
    int p2 = payload.indexOf('|', p1 + 1);
    int p3 = payload.indexOf('|', p2 + 1);

    if (p1 > 0 && p2 > p1 && p3 > p2) {
      busType     = payload.substring(0, p1);
      busCode     = payload.substring(p1 + 1, p2);
      busName     = payload.substring(p2 + 1, p3);
      busCorridor = payload.substring(p3 + 1);
      updateAdvertisingPayload();
    }
  }
}

class ServerCallbacks: public BLEServerCallbacks {
  void onConnect(BLEServer* pServer) {
    deviceConnected = true;
    Serial.println("[ESP32] Conexão GATT estabelecida.");
  }

  void onDisconnect(BLEServer* pServer) {
    deviceConnected = false;
    Serial.println("[ESP32] Conexão GATT finalizada. Beacon em transmissão contínua.");
  }
};

class RxCallbacks: public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *pCharacteristic) {
    String rxValue = pCharacteristic->getValue().c_str();
    if (rxValue.length() > 0) {
      processCommand(rxValue);
    }
  }
};

void setup() {
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, HIGH);

  Serial.begin(115200);
  Serial.println("[ESP32] Inicializando Beacon BLE com potência calibrada...");

  BLEDevice::init("PontoCerto_ESP32");

  // Fixa a potência de transmissão de rádio no nível máximo (+9 dBm)
  esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_ADV, ESP_PWR_LVL_P9);
  esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_DEFAULT, ESP_PWR_LVL_P9);

  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());

  BLEService *pService = pServer->createService(SERVICE_UUID);

  pTxCharacteristic = pService->createCharacteristic(
                        CHARACTERISTIC_UUID_TX,
                        BLECharacteristic::PROPERTY_NOTIFY
                      );
  pTxCharacteristic->addDescriptor(new BLE2902());

  BLECharacteristic *pRxCharacteristic = pService->createCharacteristic(
                                           CHARACTERISTIC_UUID_RX,
                                           BLECharacteristic::PROPERTY_WRITE |
                                           BLECharacteristic::PROPERTY_WRITE_NR
                                         );
  pRxCharacteristic->setCallbacks(new RxCallbacks());

  pService->start();

  // Inicia o Beacon contínuo
  updateAdvertisingPayload();

  digitalWrite(LED_PIN, LOW);
  Serial.println("[ESP32] Transmissão contínua a +9 dBm pronta!");
}

void loop() {
  if (!deviceConnected && oldDeviceConnected) {
    delay(200);
    BLEDevice::getAdvertising()->start();
    oldDeviceConnected = deviceConnected;
  }
  if (deviceConnected && !oldDeviceConnected) {
    oldDeviceConnected = deviceConnected;
  }

  // Pisca o LED da placa a cada 300ms indicando que o rádio está transmitindo
  unsigned long now = millis();
  if (now - lastLedToggleMs >= 300) {
    lastLedToggleMs = now;
    ledState = !ledState;
    digitalWrite(LED_PIN, ledState ? HIGH : LOW);
  }

  delay(50);
}
