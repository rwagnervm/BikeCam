# Plano: HUD sobre vídeo externo

Renderizar, no próprio app, um vídeo novo com os mostradores do BikeCam desenhados sobre um vídeo
de outra câmera (GoPro, celular…), usando a trilha de um ciclocomputador (iGPSport, Garmin…).

## Decisões

| Tema | Decisão |
|---|---|
| Onde renderiza | No app Android (Media3 Transformer), reaproveitando `HudRenderer`, layout e tiles |
| Câmeras | GoPro sem GPS (Hero 11 Mini, Hero 12) e outras câmeras/celulares |
| Sincronização (v1) | Metadado do vídeo + ajuste manual com prévia |
| Trilhas | GPX, TCX e FIT |
| Extras na v1 | Capítulos da GoPro + recorte, rota completa no mapa, novos widgets |
| Layout | Layout próprio de renderização, editado sobre um quadro do vídeo, exportável em JSON |
| Saída padrão | 1080p H.264 (opção de manter a resolução original e de usar HEVC) |
| Widgets novos | Potência, temperatura e perfil de elevação só na renderização (ficam fora do editor da gravação ao vivo) |

## O que os arquivos de exemplo mostraram

- **GoPro HERO11 Black Mini** (sem GPS): HEVC 5312×2988, 10-bit, 29,97 fps; trilha GPMF só com
  acelerômetro/giroscópio. Dois relógios, que **concordam**:
  - `creation_time` (mvhd) = `20:16:36Z` (UTC verdadeiro, 17:16:36 no fuso −03:00), resolução de 1 s;
  - timecode (`tmcd`): contador de quadros desde a meia-noite **local**. O rótulo
    `17:15:34:19` (o que o ffprobe mostra) conta a 30 fps nominais e atrasa ~62 s; convertido com a
    duração real do quadro (1001/30000 s) dá **17:16:36,77**, com fração de segundo.
  - A GoPro grava `29` no campo “quadros por segundo” do `tmcd`; a taxa certa é
    `timescale/frameDuration`.
  - Na trilha do iGPSport, esse instante bate com a foto de exemplo do README (FC 160, cadência 58,
    ~21 km/h às 17:16:41).
  Outras câmeras gravam de outros jeitos, então a tela de sincronização oferece **candidatos** e o
  ajuste fino decide.
- Correlacionar a vibração do acelerômetro com a velocidade da trilha **não funcionou** neste
  exemplo (r≈0,7 num ponto errado): a vibração reflete mais o piso que a velocidade.
- O nome do arquivo exportado pelo Quik ganha sufixo: `GX010127_1795371688674.MP4`.
- **iGPSport** usa gravação “inteligente”: pontos a cada ~8 s (às vezes 1 s), com buracos de
  2–4 min nas paradas.
  - GPX: só posição, altitude e hora (sem FC/cadência), 289 pontos em 72 min.
  - TCX: posição, altitude barométrica, distância, velocidade, FC e cadência.
  - FIT: o mais completo (também potência e temperatura, se houver).
  Interpolar por quadro é obrigatório, e buraco longo (> 20 s) vira “parado”.

## Fluxo do usuário

1. **Renderizar** (nova tela) → escolher vídeo(s) e trilha (`.fit`, `.tcx`, `.gpx`). Capítulos da
   GoPro (`GX01xxxx`, `GX02xxxx`…) são agrupados e concatenados.
2. **Sincronizar** → escolher a hora de início entre os candidatos e ajustar o offset (±1 s,
   ±0,1 s) vendo o quadro com o HUD por cima. Atalho: pausar num quadro que mostra um relógio (ou a
   tela do ciclocomputador) e digitar a hora vista. Gráfico da velocidade/altitude da trilha com o
   intervalo do vídeo destacado; aviso se o vídeo sair do intervalo da trilha.
3. **Recortar e configurar** → início/fim, resolução, codec, áudio, layout.
4. **Renderizar** → foreground service com progresso e cancelamento.
   Saída: `Movies/BikeCam/render_AAAAMMDD_HHMMSS.mp4`.

## Sincronização

Hora de início do vídeo (`videoStartEpochMs`); a hora de cada quadro é
`videoStartEpochMs + pts`. Candidatos, nesta ordem (um candidato a menos de 1 s de outro anterior é
omitido):

1. Timecode `tmcd` (hora local do dia, com fração de segundo) na data do `creation_time`.
2. `creation_time` como início (UTC).
3. `creation_time` como hora local gravada como UTC (câmeras sem fuso configurado).
4. `creation_time` como **fim** da gravação (alguns celulares) → menos a duração.
5. Hora no nome do arquivo (`VID_20261122_171641`, `PXL_20261122_201641123` em UTC,
   `YYYYMMDD_HHMMSS`).

Candidatos fora do intervalo da trilha aparecem marcados. O fuso usado para hora local é o do
aparelho, editável. Distância, média e ganho mostrados são os **da trilha inteira** (como no
ciclocomputador); opção futura: zerar no início do vídeo.

## Arquitetura

```
importer/
  ActivitySample        amostra bruta da trilha (hora, posição, altitude, velocidade, distância,
                        FC, cadência, potência, temperatura) — tudo opcional menos a hora
  GpxReader, TcxReader  SAX (javax.xml), sem depender de Android
  FitReader             Garmin FIT SDK (já está no projeto)
  ActivityReader        detecta o formato pelo conteúdo e despacha
render/
  RideTimeline          trilha processada: distância acumulada, velocidade, tempo em movimento,
                        média, ganho e inclinação (janela centrada), rota completa;
                        snapshotAt(epochMs) → TelemetrySnapshot interpolado
  Mp4Metadata           parser mínimo de caixas MP4 (mvhd, tmcd, duração) sem Android
  VideoStartCandidates  candidatos de hora de início (seção acima)
  GoProChapters         agrupa capítulos pelo nome
  (fase 3) RenderJob, HudCanvasOverlay, RenderService
ui/
  (fase 2) RenderScreen, SyncScreen
```

Mudanças em código existente:
- `TelemetrySnapshot`: `powerW`, `temperatureC`, `route` (rota completa) e `SpeedSource.FILE`.
- `HudRenderer`: rota completa em cinza, sem marcador “×” quando o dado vem de arquivo.
- `HudWidget`: `POWER`, `TEMPERATURE`, `ELEVATION_PROFILE`.

### Processamento da trilha (`RideTimeline`)

- Ordena por hora e remove duplicatas.
- **Distância**: a do arquivo se existir; senão, haversine entre posições.
- **Velocidade**: a do arquivo se existir; senão, Δdistância/Δtempo entre pontos vizinhos.
- **Buracos** > 20 s: velocidade e cadência 0, posição parada no ponto anterior.
- **Interpolação linear** de tudo entre amostras (FC e cadência arredondadas).
- **Tempo em movimento / média**: só com velocidade ≥ 0,6 m/s (igual ao `TripComputer`).
- **Altitude suavizada** por distância; **ganho** com histerese de 2 m; **inclinação** por janela
  **centrada** de 60 m (dá para olhar para frente porque a trilha inteira é conhecida).
- **Rumo** pela direção entre posições vizinhas (para a seta do mini-mapa).
- **Rota** com `TrackBuilder` (um ponto a cada 5 m); o trecho percorrido é um prefixo dela.

### Renderização (fase 3)

- `Composition` com os capítulos em sequência; recorte por `ClippingConfiguration`.
- `OverlayEffect` + `CanvasOverlay` chama `HudRenderer.draw()` com
  `timeline.snapshotAt(start + presentationTimeUs / 1000)`.
- Tiles do mapa baixados **antes** de começar (a rota é conhecida).
- Fonte HDR/10-bit → tone-mapping para SDR antes do overlay.
- Áudio copiado sem recodificar.
- Checagem com `MediaCodecList` se o aparelho decodifica a resolução de entrada.
- Estimativa de tamanho do arquivo e de espaço livre antes de iniciar.

## Fases

| Fase | Entrega | Testes |
|---|---|---|
| 1 ✅ | Leitores GPX/TCX/FIT, `RideTimeline`, `Mp4Metadata`, candidatos de início, capítulos | Unitários (dados sintéticos) + teste local opcional com `exemplos/` |
| 2 ✅ | Telas de seleção e sincronização com prévia (`MediaMetadataRetriever`) | Cálculo de offset, cobertura |
| 3 ✅ | Media3 Transformer, foreground service, recorte, saída | Plano de saída (unitário) + teste no aparelho |
| 4 ✅ | Rota completa, widgets novos, layout de renderização (JSON) | Layout, JSON, perfil (unitário) + teste no aparelho |

## Resultados da fase 3 (Galaxy S23 Ultra, vídeo de exemplo 5.3K HEVC 10-bit)

- 2:08 renderizados em 1080p H.264 em ~54 s (~2,4× o tempo real), codificador `c2.qti.avc.encoder`.
- Recorte de 9 s (0:58–1:07): primeiro quadro idêntico ao original em 0:58; o overlay recebe o tempo
  da saída a partir de 0 (efeito aplicado na `Composition`).
- O codificador gravou ~19,4 Mbps pedindo 15,5 Mbps; a estimativa de tamanho usa +25 %.
- Android 16+ (SDK 37): `ServiceCompat.startForeground` chegava com tipo “none” e o sistema
  recusava; o serviço chama `startForeground(id, n, tipo)` direto.

## Resultados da fase 4

- Layout de renderização separado (`render_landscape`/`render_portrait` no DataStore), editado
  sobre o quadro da prévia; exporta/importa `bikecam-layout.json`.
- Com a trilha FIT do exemplo: temperatura aparece, potência fica escondida (sem dado), perfil de
  elevação 280–325 m. Saída HEVC 1080p testada (`c2.qti.hevc.encoder`).
- Cuidado: o regex do Android (ICU) recusa `}` sem escape, a JVM aceita — os testes unitários não
  pegam isso; o app quebrou ao abrir até o regex do JSON ser corrigido.

## Limpeza de áudio (opções da renderização)

- **Remover ruído do vento** (`render/audio/WindFilter.kt`, sem biblioteca): passa-altas Butterworth
  de 4ª ordem em 120 Hz + crossover Linkwitz-Riley em 400 Hz; quando os graves passam 4× a energia
  dos agudos (rajada), são atenuados até −18 dB (ataque 10 ms, soltura 250 ms), com o mesmo ganho nos
  dois canais. No exemplo (30 s): 20–120 Hz −16 dB, 120–400 Hz −3,7 dB, acima de 400 Hz < 1 dB.
- **Remover vozes** — primeira versão, depois trocada pelo MossFormer2 (ver abaixo): DeepFilterNet3 via
  [AndroidDeepFilterNet](https://github.com/KaleyraVideo/AndroidDeepFilterNet) 0.0.8 (Apache-2.0)
  estima a fala na mistura mono (48 kHz, quadros de 480); a fala é subtraída de cada canal, alinhada
  pelo atraso do modelo (**1440 amostras = 30 ms, medido no aparelho**). Sem fala, a saída muda
  ~0,01 dB. A avaliação com conversa depende de um vídeo novo em `exemplos/`.
- Os processadores entram como `Effects.audioProcessors` da `Composition`; o áudio passa a ser
  recodificado. A sincronia com o vídeo não muda (medido).
- Bug encontrado: escrever o “rabo” do processamento dentro de `onQueueEndOfStream` sobrescrevia o
  último pedaço ainda não lido (o `replaceOutputBuffer` reaproveita o buffer) — ~0,12 s a menos de
  áudio. O rabo agora sai na próxima chamada de `queueInput`.
- APK limitado a `arm64-v8a` (83 MB → 40 MB): o DeepFilterNet trazia código nativo para 4
  arquiteturas (o ONNX Runtime também).
- **Teste com voz + vento** (`exemplos/som/`, 14,7 s, celular, vertical; trilha sintética só para
  renderizar): as três versões (só vozes, só vento, ambos) saem com duração e rotação corretas.
  Vento: 20–150 Hz −8 a −14 dB em todo o clipe, faixa de voz (300–3400 Hz) ≈ −1 dB. Vozes: nos
  trechos com fala a faixa de voz cai só −2 a −7 dB (sem fala, ~0 dB). A subtração está alinhada (a
  queda bate com a energia da fala estimada); o limite é a estimativa: com o vento alto o modelo
  considera a maior parte da voz como ruído (fala estimada 2–10 dB abaixo da mistura). A biblioteca
  Android só expõe `setAttenuationLimit` e `setPostFilterBeta` (o limiar de SNR local, −15 dB, é
  fixo); `processFrame` devolve o SNR local de cada quadro, útil para diagnóstico.
  Próximo passo a testar: dar ao modelo o sinal já sem o ronco (passa-altas/filtro de vento antes da
  estimativa).
- **Ouvido, o resultado não serviu** (vozes e ruído continuam audíveis nas três versões). Avaliação no
  PC do **MossFormer2_SE_48K** (ClearerVoice-Studio, Apache-2.0, checkpoint de 212 MB) por canal,
  offline: ~4× mais rápido que o tempo real em 8 núcleos (CPU), saída alinhada (atraso 0). A fala
  estimada fica só 2–4 dB abaixo da mistura na faixa 400–8000 Hz (DFN: 2–10 dB). Fala restante na
  versão “sem voz”, medida rodando o próprio MossFormer2 de novo na saída: DFN do app −2,6 dB;
  MossFormer2 1 passada −9 a −10 dB; 2 passadas (subtrai também a fala achada no resíduo) ≈ −15 dB.
  O modelo separa fala × resto, não vento × ambiente: “sem vento” = fala + resto −12 dB.
  Versões para ouvir em `exemplos/som/resultados/mossformer2/`. **Aprovado de ouvido (1 passada).**

### Implementação com MossFormer2 (substitui o DeepFilterNet)

- **Exportação** (scripts fora do repositório): o grafo ONNX (opset 17) inclui o pré e o
  pós-processamento do ClearVoice — fbank estilo Kaldi sem dither, deltas, máscara por canal,
  STFT/iSTFT de 1920/384 como multiplicação de matrizes (o DFT do ONNX Runtime é O(n²) para tamanhos
  que não são potência de 2), matrizes da DFT geradas no grafo e pesos em fp16 (120 MB; −63 dB em
  relação ao fp32). Igual ao ClearVoice a −115 dB. int8 dinâmico descartado (pior e quase do mesmo
  tamanho).
- **No app** (`render/audio/`): janelas de 4 s a cada 3 s, aproveitando só o miolo (descarta 0,5 s
  em cada borda), como o decode longo do ClearVoice (`WindowedSeparator`); fala restante −9,2 dB, igual
  ao arquivo inteiro. Resíduo = original − fala; `WindFilter` no resíduo quando “vento” está marcado.
- **Distribuição**: release `modelo-audio-v1` do GitHub (com o texto da Apache-2.0); o app baixa com
  retomada (`.part` + `Range`) e confere o SHA-256 (`ModelDownload`). Sem o modelo, as opções ficam
  desativadas com o botão de baixar.
- **Duas passadas**: com a limpeza na mesma exportação do vídeo, a renderização travava às vezes. O
  muxer do Media3 não deixa uma trilha passar 0,5 s à frente da outra, e a limpeza segura o áudio
  ~3,5 s; com o buffer de entrada cheio, tudo para (visto no aparelho: todas as threads paradas).
  Agora a 1ª passada exporta só o áudio limpo (AAC 320 kbps, temporário em `cacheDir`) e a 2ª faz o
  vídeo com o HUD e usa esse áudio numa segunda sequência da `Composition`.
- **Sincronia**: o codificador AAC atrasa o som 448 amostras além do que marca no `elst` (medido);
  na dupla passada isso somava 19 ms. A 1ª passada descarta 448 amostras no início
  (`SkipStartProcessor`), e a saída fica com os mesmos 9 ms de uma renderização sem limpeza.
  Recortar o início na 2ª passada não serve: com o corte, o ExoPlayer deixa de aplicar o `elst`
  (ficava 43 ms).
- **Desempenho no S23 Ultra** (4 threads): modelo carrega em 8–15 s; 2,4–4,4 s por janela de 4 s
  (passo de 3 s), conforme a temperatura — a passada de áudio leva ~0,8–1,5× a duração do trecho.
  No exemplo de 14,7 s: ~22 s de áudio + ~9 s de vídeo. A saída do celular bate com a do PC
  (diferença −23 dB, que é a recodificação AAC).
- APK de 53 MB (ONNX Runtime para `arm64-v8a`) sem o modelo.

## Riscos

- 5.3K HEVC 10-bit pode não decodificar em celulares mais fracos → avisar e sugerir 1080p.
- Renderizar 4K+ no celular pode levar de 1× a 3× a duração do vídeo; aquece e gasta bateria.
- Relógio da câmera errado → o ajuste manual é o que garante a sincronia.
- Trilhas esparsas (8 s) deixam curvas “cortadas” no mapa; a interpolação é linear.

## Fora da v1 (ideias)

- Sincronia automática pelo GPMF: a vibração não serviu no exemplo; talvez giroscópio (curvas) ×
  rumo da trilha em percursos com mais curvas.
- Trilha GPMF com GPS (Hero 5–11 não-Mini, Hero 13) para sincronia exata.
- Sensor BLE de potência (0x1818) na gravação ao vivo.
- Ferramenta de PC para renderizações pesadas.
