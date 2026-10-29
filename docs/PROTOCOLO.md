# Protocolo Xiaomi (Mi Band 8, 9 e 10): resumo

Resumo do que li no código do **Gadgetbridge** (`app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/` e os `.proto` em `GBProtobufGenerator/src/main/proto/`, commit `4c64bc8e`, 26/10/2026) e no **miband-hack** (`docs/protocol.md` e capturas reais da Mi Band 10 em `docs/captures/`). Os nomes de classes entre parênteses são do Gadgetbridge.

## 0. Modelos

| Modelo | Tela | Conexão no Gadgetbridge | No app | Observações |
|---|---|---|---|---|
| Mi Band 8 | 192 × 490 (preview reduzido conhecido: 122 × 310) | BLE → **BLE V1** (`XiaomiBleProtocolV1`) | BLE V1 | Não aceita mini-apps (miband-hack, firmware 2.3.14). Toda face precisa da tela AOD: uma face sem ela impediu uma Mi Band 8 de ligar. |
| Mi Band 9 | 192 × 490 | `BT_CLASSIC` → SPP V1 ou V2 (pela sondagem) | SPP (V1 ou V2) | |
| Mi Band 10 | 212 × 520 | `BT_CLASSIC` → SPP V2 | SPP V2 | Testada com uma banda real. |

As versões Pro e Active têm outra tela (e, na 9 Active, BLE V2) e não são suportadas. O modelo é detectado pelo nome Bluetooth (`^Xiaomi Smart Band 8 [A-Z0-9]{4}$`, `… 9 [0-9A-F]{4}$`, `… 10 [0-9A-F]{4}$`) ou escolhido à mão.

## 1. Transporte: Bluetooth Clássico, não BLE

**Diferença importante em relação ao pedido original:** no Gadgetbridge a Mi Band 10 é `ConnectionType.BT_CLASSIC` (`MiBand10Coordinator`), ou seja, a conexão é por **SPP (porta serial) sobre RFCOMM**, UUID `00001101-0000-1000-8000-00805f9b34fb`, e não por GATT/BLE. O mesmo vale para Mi Band 9 / 9 Pro / 10 Pro.

A banda também expõe um serviço BLE `FE95` com as características `005E` (notificação, banda → celular) e `005F` (escrita, celular → banda). Esse é o "BLE V2" (`XiaomiBleProtocolV2`), que usa **exatamente o mesmo enquadramento do SPP V2**. O miband-hack viu essas características numa Mi Band 10 real, mas o caminho testado no Android (Gadgetbridge) é o SPP. Por isso o app usa SPP por padrão e oferece BLE como opção experimental.

- Nome anunciado: `Xiaomi Smart Band 10 XXXX` (regex do Gadgetbridge `^Xiaomi Smart Band 10 [0-9A-F]{4}$`).
- Tela: 212 × 520 (`getScreenSize()`).
- No SPP a banda precisa estar **pareada (bond)** com o Android.

## 2. Sondagem de versão (só SPP)

Logo após abrir o RFCOMM, envia-se um pacote **SPP V1** pedindo a versão (`XiaomiSppSupport.initializeDevice`):

```
ba dc fe | 00 (canal versão) | c0 (flags) | 03 00 (tamanho) | 00 (opcode READ) | 00 (serial) | 00 (plain) | ef
```

Se `payload[0] >= 2`, a sessão passa a usar **SPP V2**. A Mi Band 10 respondeu `03 00 41` numa captura real. Com versão menor, ou sem resposta em 5 s, a sessão segue em **SPP V1**, como no Gadgetbridge.

### SPP V1 (`XiaomiSppPacketV1`, `XiaomiSppProtocolV1`)

```
ba dc fe | canal | flags (0x80) | tamanho (2 LE = payload + 3) | opcode (2 = SEND) | serial | dataType | payload | ef
```

- Canais: 0 versão, 1 comandos da banda, 2 comandos do celular, 3 atividade, 5 arquivos.
- `dataType`: 0 puro, 1 cifrado, 2 autenticação (comandos 1/26 e 1/27 em texto puro).
- Comandos cifrados: `u16 contador | AES-CCM(contador)`; o contador começa em 1. Arquivos (canal 5): AES-CCM com contador 0, sem prefixo. O que vem da banda é decifrado com contador 0.
- Nonce CCM de 12 bytes: `nonce4 | 00000000 | u32 contador LE`, tag de 4 bytes.
- Não há ACK nem reenvio: cada pacote vai uma vez.

## 3. SPP V2 (`XiaomiSppPacketV2`, `XiaomiSppProtocolV2`)

```
a5 a5 | tipo (4 bits baixos) | seq (1) | tamanho (2, LE) | CRC-16/ARC do payload (2, LE) | payload
```

- Tipos: `0` NACK, `1` ACK, `2` SESSION_CONFIG, `3` DATA.
- **Sessão:** o celular envia SESSION_CONFIG (opcode 1) com TLVs: versão 1.0.0, quadro máximo `0xfc00`, janela de 32 quadros, timeout 10000 ms. A banda responde com a configuração dela (na Mi Band 10 real: janela 3, quadro máximo 32768). Só então começa a autenticação.
- **DATA:** `canal (1) | opcode (1) | corpo`. Canal `1` = protobuf/autenticação, `2` = envio de arquivo, `5` = atividade. Opcode `1` = texto puro, `2` = cifrado.
- **ACK/NACK:** sequência de 8 bits que dá a volta. ACKs são cumulativos. A banda aceita quadros estritamente em ordem (go-back-N): `NACK(n)` = "estou esperando o quadro n". Todo quadro DATA recebido da banda deve ser respondido com `ACK(seq)`.
- Reenvio após 10 s sem ACK; após 3 timeouts seguidos a conexão é considerada morta.
- **Conexão parada:** na Mi Band 10 real, a banda fechou o RFCOMM depois de ~60 s sem nenhum tráfego (medido: 57,5 s e 62,5 s sem RX/TX). Por isso o app pede a bateria (`2/1`, comando inofensivo) quando a conexão fica 20 s parada. Não está no Gadgetbridge, que normalmente tem tráfego constante (notificações, atividade).
- **Quedas mesmo com tráfego:** com o keep-alive, a banda ainda fechou o RFCOMM uma vez 48 s depois de conectar (2 s depois de uma resposta normal). A causa não está clara (a banda manda pedidos que o app não responde: 2/42, 18/0 música, 17/7, 10/3 clima). O app reconecta sozinho e, no envio de face, repete 4/4 e 22/0: a banda responde com `resumePosition` e o envio continua dali (o mesmo campo que o Gadgetbridge usa).

## 3b. BLE V1 da Mi Band 8 (`XiaomiBleProtocolV1`, `XiaomiCharacteristicV1`)

Serviço `FE95` com as características `0051` (comandos da banda), `0052` (comandos do celular), `0053` (atividade) e `0055` (arquivos, opcional). O celular pede MTU 512 e liga as notificações das quatro. Cada característica tem o próprio protocolo:

- Controle (`00 00 | tipo | …`): `0` início de transferência em partes `[00 00 00 cifrado u16 n]` → `00 00 01 01`; `1` resposta (0 terminou, 1 pode mandar, 2 NACK, 5 reenvie `[u16…]`); `2` comando único `[00 00 02 cifra (1 cifrado / 2 puro) (u16 contador) payload]` → ACK `00 00 03 00`; `3` ACK (byte 3 = 0 é OK).
- Partes: `u16 índice (a partir de 1) | dados`, até (escrita − 2) bytes. Vai em partes o que não cabe em (escrita − 6).
- Cifra: AES-CCM como no SPP V1. O contador vai no cabeçalho do comando único ou na frente do payload em partes; começa em 1 depois da autenticação. Na `0055` o contador é sempre 0 e não vai no pacote. O que vem da banda é decifrado com contador 0.
- Envio de arquivos: cada parte do 22/0 é uma transferência na `0055`, por escrita sem resposta (5× mais rápido numa Mi Band 8, segundo o miband-hack). Uma face de 1 MB leva cerca de 10 minutos.

## 4. Autenticação (`XiaomiAuthService`)

Comandos protobuf `Command{type, subtype, ...}`; autenticação é `type = 1`. As mensagens de autenticação vão em texto puro (opcode 1) no canal 1.

1. Celular → banda: `subtype 26`, `auth.appVerify.nonce` = 16 bytes aleatórios.
2. Banda → celular: `auth.deviceVerify{nonce, hmac}`.
3. Derivação de chaves (estilo HKDF-expand):
   - `hk = HMAC-SHA256(chave = nonceCelular ‖ nonceBanda, msg = authKey)`
   - `T(i) = HMAC-SHA256(hk, T(i-1) ‖ "miwear-auth" ‖ i)`, `i = 1, 2, …`, até 64 bytes.
   - `decKey = [0:16]`, `encKey = [16:32]`, `decNonce = [32:36]`, `encNonce = [36:40]`.
4. Conferir `HMAC-SHA256(decKey, nonceBanda ‖ nonceCelular) == hmac`. Se não bater, a **auth key está errada**.
5. Celular → banda: `subtype 27`, `auth.appConfirm{encryptedNonces, encryptedDeviceInfo}`:
   - `encryptedNonces = HMAC-SHA256(encKey, nonceCelular ‖ nonceBanda)`
   - `encryptedDeviceInfo = AES-128-CCM(encKey, nonce = encNonce ‖ 00000000 ‖ 00000000, tag 4 bytes)` do protobuf `CompanionDevice{0, nível de API, modelo do celular, 224, região}`.
6. Banda responde `subtype 27`: autenticado.
7. O Gadgetbridge acerta a hora logo em seguida (`XiaomiSystemService.setCurrentTime`): comando `2/3` com `system.clock{date, time{hora, minuto, segundo, ms}, timezone{zoneOffset e dstOffset em blocos de 15 min, nome do fuso}, isNot24hour}`, sem esperar resposta. Este app faz o mesmo.

Depois disso, no SPP V2, os comandos protobuf vão cifrados com **AES-128-CTR, IV = a própria chave** (`encKey` para enviar, `decKey` para receber), contador reiniciado a cada pacote (sim, é isso mesmo: `encryptV2`/`decryptV2`). O canal de dados (upload) não é cifrado.

**Recusa NO_BOUND:** o proto do Gadgetbridge não tem o campo 3 de `Account`; pelo schema do AstroBox (citado no miband-hack) ele é `error_code`, e `4` = NO_BOUND. Uma Mi Band 10 real respondeu `0801101a1a021804` ao passo 1 quando estava vinculada a um iPhone e o pedido vinha de outro aparelho.

### De onde vem a auth key

A chave (32 caracteres hex) é criada quando a banda é vinculada à conta Xiaomi no Mi Fitness. Formas conhecidas de obtê-la (documentação do Gadgetbridge e do miband-hack):

- **Logs do Mi Fitness no Android:** `/sdcard/Android/data/com.mi.health/files/log/` (ou `com.xiaomi.wearable`), campos `encryptKey`, `token`, `authKey` ou `huamiAuthKey`. Usar a **última** ocorrência do arquivo.
- **Pasta do Mi Fitness no iPhone/iPad:** `MHWCahe/<uid>/VirtualDevice_registerList/manifest.sqlite`, tabela `manifest`, linhas `registerList_<região>` (ex.: `registerList_de`, `registerList_sg`), cada uma com uma plist que contém `encryptKey`. É preciso olhar **todas** as regiões.
- Não desvincular a banda no Mi Fitness (invalida a chave). Reset de fábrica também gera chave nova.

### Buscar a chave na conta Xiaomi (nuvem)

Portado de `worker/xiaomi-cloud.ts` do miband-hack (que cita Xiaomi-cloud-tokens-extractor, ha_miband e huami-token). Não faz parte do protocolo da banda, é só para obter a auth key:

1. `GET https://account.xiaomi.com/longPolling/loginUrl?sid=miothealth&callback=https://sts-hlth.io.mi.com/healthapp/sts&…` devolve `qr` (imagem), `loginUrl` (a mesma página, para abrir no navegador) e `lp` (espera longa).
2. O usuário confirma o login na página da Xiaomi (a senha nunca passa pelo app). O QR contém exatamente o `loginUrl`. A espera longa (`lp`) fica aberta por ~300 s e depois a Xiaomi fecha a conexão sem resposta (medido em 26/10/2026, de forma anônima, sem login). **Depois que o cliente interrompe uma espera, as seguintes respondem 403 por alguns segundos** e só então voltam a segurar a conexão: enquanto não há espera ligada, o escaneamento mostra "QR expirado". Por isso o app mantém uma única espera até o fim da validade e, se ela cair, pausa 4 s antes de religar. Quando conclui, a espera responde com `userId`, `cUserId`, `ssecurity`, `passToken`, `location` e `nonce`.
3. `location` (STS) é aberto para receber o cookie `serviceToken` (primeiro simples, depois com `clientSign = base64(SHA1("nonce=<nonce>&<ssecurity>"))`).
4. `POST <região>.hlth.io.mi.com/app/v1/source/get_source_list` com `{"page_size":50,"status":1}`, assinado e cifrado com RC4 (descartando 1024 bytes) a partir de `SHA256(ssecurity ‖ nonce)`; há duas codificações (estilo Mi Home e fluxo único do huami-token). A resposta vem cifrada igual.
5. Cada aparelho traz a chave em `detail.encrypt_key` (ou `auth_key`, `token`). Chaves de 32 hex são ofertadas; `beaconkey` nunca.

Não confirmado com conta real (o miband-hack avisa o mesmo).

## 5. Watch faces (`XiaomiWatchfaceService`, `type = 4`)

| Subtipo | Comando |
|---|---|
| `0` | lista: `watchface.watchfaceList.watchface[]{id, name, active, canDelete}` |
| `1` | ativar: `watchface.watchfaceId` |
| `2` | remover: `watchface.watchfaceId` (o Gadgetbridge recusa remover a ativa ou uma do sistema) |
| `4` | iniciar instalação: `watchface.watchfaceInstallStart{id, size}` → resposta `installStatus` (`0` = pode enviar, `2` = já instalada) |

## 6. Envio do arquivo (`XiaomiDataUploadService`, `type = 22`)

1. `22/0 dataUpload.dataUploadRequest{type = 16 (watch face), md5, size}`.
2. Resposta `dataUploadAck{errno, resumePosition, chunkSize}`. `errno ≠ 0` = recusado. Sem `chunkSize`, usar 2048.
3. Payload = `00 | tipo | md5 (16) | tamanho (u32 LE) | bytes[resumePosition:] | crc32 (u32 LE) de tudo o que vem antes`.
4. Dividido em partes de `chunkSize − 4` bytes; cada parte = `total (u16 LE) | índice começando em 1 (u16 LE) | dados`, enviada como DATA no canal 2. **Cada parte recebe um ACK de transporte da banda.**
5. Terminado o envio, o Gadgetbridge ativa a face (`4/1`) e pede a lista de novo.

## 6b. Mini-apps `.rpk` (`XiaomiRpkService`, `type = 20`)

- **Arquivo:** ZIP (app Vela/QuickApp) com `manifest.json` (`package`, `name`, `versionName`, `versionCode`, `config.designWidth`, `router`), `app.js` e as páginas compiladas (`pages/<página>/<componente>.js`). O Gadgetbridge só lê o `manifest.json`; o app também confere o CRC de cada arquivo do ZIP, caminhos inválidos e a página inicial.
- `20/0`: lista. Resposta `rpk.rpkList.rpkInfo[]` com `id` (o `package`), `name` e `sha`.
- `20/1`: pedido de instalação com `rpk.rpkInfo` (`id`, `unknown2` = versionCode, `size`). Resposta `20/1` com `rpk.rpkInstallStart.cmd` (0 = aceito; os outros códigos não são conhecidos).
- Envio: igual ao das faces (seção 6), mas com **tipo 64**. Ao terminar, a banda manda `20/2` (instalado). O app confere pela lista.
- `20/3`: remover, com `rpk.rpkDel` (`id` e o `sha` da lista). Segundo o Gadgetbridge a banda às vezes não responde, então o resultado é conferido pela lista.

## 7. Formato do arquivo de watch face (`XiaomiFWHelper`)

- **v2** (o que pode ser instalado): começa com `5A A5`. ID numérico em texto terminado em zero no offset `0x28`. Nome no offset `0x68` (ou tabela de traduções se `0x68` = `FF FF FF FF`). Offset do preview (u32) em `0x20`; o cabeçalho do preview tem largura/altura em `+4`/`+6`.
- **v3** (cabeçalho `00 00 06 07 00 02 00 01`): o Gadgetbridge não consegue extrair o ID e **não instala**; o miband-hack também recusa. Este app faz o mesmo.
- Compatibilidade (`XiaomiInstallHandler`): o preview deve ter o tamanho da tela (212 × 520) ou, pelo menos, a mesma proporção (±2 %) sem ser maior.
- O miband-hack valida a estrutura interna do v2 antes de enviar (tabelas dentro do arquivo, imagens que descomprimem para o tamanho declarado): uma Mi Band 8 deixou de ligar depois de uma face malformada. Este app porta essa validação.
- **Por dentro do v2** (visto nas faces oficiais da Mi Band 8 e no UnpackMiColorFace): número de telas em `0x1C` (normal e AOD); por tela, 8 + 10 × 8 bytes em `0xA8`: imagem de fundo, prévia e (quantidade, tabela) de 10 seções. Cada tabela tem itens de 16 bytes: id (`tipo << 24 | índice`), 4 zeros, posição e tamanho. Seções conhecidas: 0 elementos (alvo + x, y), 2 imagens, 3 listas de imagens, 5 apps, 7 widgets, 9 ações. Os dados vêm em sequência, sem buracos, logo depois da prévia.
- **Imagens da face:** 12 bytes de cabeçalho (largura e altura em `+4`/`+6`, tamanho em `+8`) e compressão `E0 21 A5 5A` + (tamanho bruto << 4 | tipo), com tipo 2 = RGB565 e 4 = BGRA. Cada byte de controle: bit 7 ligado = n pixels literais, desligado = 1 pixel repetido n vezes (n até 127). As faces oficiais só repetem 5 ou mais pixels e não têm literal de 1 pixel.
- **Listas de imagens (seção 3)**, usadas pelos widgets (algarismos, dias da semana): byte 1 = quantidade de quadros, largura e altura em `+4`/`+6`, soma dos tamanhos em `+8`, os tamanhos de cada quadro a partir de `+12` e os quadros comprimidos em seguida. Os widgets (seção 7) apontam para uma lista em `+8`; os bytes 0 e 1 parecem indicar o dado (ex.: `0A 11` dezena da hora, `10 12` mês, `18 12` dia, `20 12` dia da semana, `08 41` bateria).
- **Apps embutidos (seção 5)**, como nos jogos oficiais "谁是学霸" e "小人过桥": cada item é um arquivo com 20 bytes de cabeçalho (tamanho em 24 bits + tamanho do nome no 4º byte, depois 16 zeros), o nome e os dados. Há imagens `.bin` no formato do LVGL v8 (cabeçalho `cf | w << 10 | h << 21`; cf 4 = RGB565, cf 5 = BGRA8888) e um script `.js` em texto. O tamanho na tabela é o do arquivo + 12 nas imagens e o do bloco inteiro no script.
- **Ações (seção 9):** a ação `0x23721400` em `+0x28` abre o app `0x05000000 | índice` indicado em `+0x2C`; a imagem do botão está em `+0x20`. Um elemento da seção 0 coloca a ação na tela. O script usa funções como `create_img`, `create_btn`, `addEvent`, `set_img_src` e `exit_game` (lista e exemplo em [`faces/calculadora-mi-band-8`](../faces/calculadora-mi-band-8/README.md)).

## 8. O que não está confirmado no código de referência

- **Mi Band 8 e 9:** o suporte foi portado do Gadgetbridge e do miband-hack e testado só contra bandas simuladas. Não sei qual firmware da Mi Band 9 ainda fala SPP V1.
- **Limite de 10 faces:** não aparece no Gadgetbridge nem no miband-hack; o app usa o valor informado por você.
- **Tamanho das partes do upload:** o Gadgetbridge usa `chunkSize − 4`; o miband-hack desconta mais 2 bytes no SPP (citando o AstroBox). O app segue o Gadgetbridge.
- **Cancelamento:** não existe comando de "abortar upload" no código de referência. O cancelamento do app para de enviar entre duas partes (nunca no meio de um quadro), não ativa a face e fecha a conexão. Como o MD5 e o CRC32 do arquivo inteiro vão no início e no fim, um envio incompleto não deveria ser instalado, mas isso não está confirmado.
- **Sincronização de hora:** o miband-hack observa que a banda pode desconectar sem ela, mas não confirmou. O app envia a hora como o Gadgetbridge (passo 7 da autenticação).
