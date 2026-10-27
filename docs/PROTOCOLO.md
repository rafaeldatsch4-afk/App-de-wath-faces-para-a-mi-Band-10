# Protocolo Xiaomi (Mi Band 10): resumo

Resumo do que li no código do **Gadgetbridge** (`app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/` e os `.proto` em `GBProtobufGenerator/src/main/proto/`, commit `4c64bc8e`, 26/10/2026) e no **miband-hack** (`docs/protocol.md` e capturas reais da Mi Band 10 em `docs/captures/`). Os nomes de classes entre parênteses são do Gadgetbridge.

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

Se `payload[0] >= 2`, a sessão passa a usar **SPP V2**. A Mi Band 10 respondeu `03 00 41` numa captura real. Sem resposta em 5 s, o Gadgetbridge assume V1 (este app não implementa V1 e mostra um erro).

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

## 7. Formato do arquivo de watch face (`XiaomiFWHelper`)

- **v2** (o que pode ser instalado): começa com `5A A5`. ID numérico em texto terminado em zero no offset `0x28`. Nome no offset `0x68` (ou tabela de traduções se `0x68` = `FF FF FF FF`). Offset do preview (u32) em `0x20`; o cabeçalho do preview tem largura/altura em `+4`/`+6`.
- **v3** (cabeçalho `00 00 06 07 00 02 00 01`): o Gadgetbridge não consegue extrair o ID e **não instala**; o miband-hack também recusa. Este app faz o mesmo.
- Compatibilidade (`XiaomiInstallHandler`): o preview deve ter o tamanho da tela (212 × 520) ou, pelo menos, a mesma proporção (±2 %) sem ser maior.
- O miband-hack valida a estrutura interna do v2 antes de enviar (tabelas dentro do arquivo, imagens que descomprimem para o tamanho declarado): uma Mi Band 8 deixou de ligar depois de uma face malformada. Este app porta essa validação.

## 8. O que não está confirmado no código de referência

- **Limite de 10 faces:** não aparece no Gadgetbridge nem no miband-hack; o app usa o valor informado por você.
- **Tamanho das partes do upload:** o Gadgetbridge usa `chunkSize − 4`; o miband-hack desconta mais 2 bytes no SPP (citando o AstroBox). O app segue o Gadgetbridge.
- **Cancelamento:** não existe comando de "abortar upload" no código de referência. O cancelamento do app para de enviar entre duas partes (nunca no meio de um quadro), não ativa a face e fecha a conexão. Como o MD5 e o CRC32 do arquivo inteiro vão no início e no fim, um envio incompleto não deveria ser instalado, mas isso não está confirmado.
- **Sincronização de hora:** o miband-hack observa que a banda pode desconectar sem ela, mas não confirmou. O app envia a hora como o Gadgetbridge (passo 7 da autenticação).
