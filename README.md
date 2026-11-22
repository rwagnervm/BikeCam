# BikeCam

App Android (Kotlin + Jetpack Compose + CameraX) que grava vídeo do guidão da bicicleta com um
OSD de telemetria queimado nos quadros (velocidade, média, FC, cadência, mini-mapa com a trilha,
distância, altitude, inclinação %, ganho de elevação, relógio) e salva a trilha bruta em GPX/FIT/JSONL.

## Exemplos

Quadro de um vídeo gravado pelo app, com o OSD queimado na imagem (hora, inclinação, ganho,
altitude, mini-mapa, FC, cadência, distância, velocidade GPS e média):

![Exemplo de quadro com OSD do BikeCam](docs/exemplo-osd.jpg)

▶️ [Assistir exemplo no YouTube](https://www.youtube.com/watch?v=ljy5OcVNnTk)

## Build
```bash
./gradlew assembleDebug          # APK em app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # parsers BLE, velocidade, GPX/FIT, elevação/inclinação, trilha, layout do HUD
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Requer Android 8.0+ (minSdk 26). Para testar sem sensores: Ajustes → "Dados simulados".

## Como funciona
- **OSD no vídeo**: `OverlayEffect` do CameraX aplicado só ao `VideoCapture`. A telemetria (5 Hz)
  vira um `Picture` em background; o callback por quadro só reproduz a última `Picture` pronta,
  então sensores lentos nunca derrubam quadros (`recording/OsdOverlay.kt`).
- **HUD na tela**: o preview é limpo e o mesmo `HudRenderer` desenha o HUD por cima em Compose.
- **Velocidade**: sensor BLE CSC se conectado e com dado recente; senão GPS (`telemetry/SpeedSelector.kt`).
- **BLE**: FC (0x180D) e CSC (0x1816), vários sensores ao mesmo tempo, reconexão com backoff
  (`telemetry/ble/BleSensorManager.kt`).
- **Foreground service** durante a gravação; a tela escurece após 5 s (toque devolve o brilho).
- **Mini-mapa** (`hud/HudRenderer.kt`, `hud/MapTiles.kt`, `hud/TrackPath.kt`): norte para cima,
  centrado na posição, ~800 m de lado, com a trilha desde o início da gravação e uma seta no rumo.
  Fundo com tiles do OpenStreetMap baixados em segundo plano e guardados em cache (até 150 MB);
  sem internet, mostra só a trilha. Pode ser desligado em Ajustes.
- **Inclinação e ganho de elevação** (`telemetry/ElevationEstimator.kt`): barômetro (altitude
  relativa) calibrado devagar pelo GPS; inclinação = Δaltitude/Δdistância em janela de 40 m,
  combinada por filtro complementar com a variação do pitch do acelerômetro (descontando dv/dt).
  Ganho com histerese de 2 m, só em movimento. Sem barômetro, usa GPS (janela 120 m, histerese 5 m).
- **Editor do HUD** (botão "HUD"): arrastar para mover, tocar para selecionar, −/+ para o tamanho,
  "Mostradores" para ligar/desligar. Um layout por orientação, salvo no DataStore.
- **Fotos durante a gravação**: "FOTO" (limpa, com GPS no EXIF) e "FOTO +HUD" (carimbada com o
  mesmo HudRenderer). Se o aparelho não aceitar foto + vídeo na resolução escolhida, os botões somem.
- **Modo economia** (botão "ECO"): tira a imagem da câmera da tela e mostra só números grandes em
  fundo preto; o vídeo continua sendo gravado com o OSD completo.
- **Gravação em partes** (Ajustes): divide o vídeo a cada 5/10/15/30 min. Cada parte é um MP4
  completo; a trilha GPX/FIT/JSONL continua única para o passeio inteiro.

## Arquivos gerados
- `Movies/BikeCam/ride_AAAAMMDD_HHMMSS.mp4` (H.264, com OSD); em partes: `ride_…_02.mp4`, `_03`…
- `Pictures/BikeCam/bikecam_AAAAMMDD_HHMMSS.jpg` (limpa) e `…_hud.jpg` (carimbada)
- `Documents/BikeCam/ride_….gpx`, `.fit` (upload no Strava, com ganho de elevação) e `.jsonl`
  (telemetria a 5 Hz; `seg` = parte do vídeo e `v` = ms desde o início dessa parte, para
  re-renderizar overlay/mini-mapa)

## Limitações conhecidas
- **H.265**: o CameraX 1.5 não expõe escolha de codec para vídeo SDR; ele só usa HEVC em HDR
  10-bit, que não combina com o OSD. O vídeo continua em H.264.
- Entre as partes da gravação segmentada fica uma lacuna de uma fração de segundo (o CameraX não
  troca de arquivo sem parar o encoder).
- A localização usa os serviços do Google Play (aparelhos sem eles ficam sem GPS).
