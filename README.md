# Mi Band 10 Faces

App Android (Kotlin + Jetpack Compose, tema escuro) para instalar watch faces na **Xiaomi Smart Band 10** direto do celular, sem usar o Mi Fitness para o envio. O protocolo foi portado do [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) e conferido com o [miband-hack](https://github.com/ptrinh/miband-hack).

> ⚠️ **Ainda não foi testado com uma Mi Band 10 real.** Tudo foi validado com testes automáticos contra uma banda simulada (a criptografia do lado da banda nos testes é independente da do app). Leia [Riscos](#riscos) antes de usar e mande os logs da aba **Logs** se algo der errado.

Resumo do protocolo: [docs/PROTOCOLO.md](docs/PROTOCOLO.md).

## Funcionalidades

- [x] Busca da banda (BLE + Bluetooth Clássico), lista de pareados e pareamento com o Android
- [x] Conexão por Bluetooth Clássico (SPP/RFCOMM, padrão) ou BLE V2 (GATT FE95, experimental)
- [x] Sondagem de versão SPP e sessão SPP V2 com ACK/NACK, reenvio por timeout e janela de envio
- [x] Autenticação com a auth key (handshake miwear-auth, AES-CCM/CTR)
- [x] Chave digitada (32 hex, aceita `0x`) ou importada de arquivo, guardada cifrada com o **Android Keystore**
- [x] Importar a chave de arquivo: logs do Mi Fitness (`encryptKey`, `token`, `authKey`, `huamiAuthKey`) ou a pasta do Mi Fitness (`manifest.sqlite` / ZIP) com `registerList_<região>` de **todas** as regiões
- [x] Informações da banda (modelo, firmware, bateria)
- [x] Escolha do arquivo `.face`/`.bin` com validação: cabeçalho v2, ID, nome, resolução do preview (212×520) e checagem estrutural interna
- [x] Preview da face na proporção da tela da banda
- [x] Instalação: pedido 4/4, envio 22/0 em partes **esperando o ACK de cada parte**, barra de progresso, confirmação pela lista e ativação
- [x] Erros tratados: desconexão, timeout sem ACK, NACK repetido, face já instalada (remove a versão antiga se você confirmar), banda cheia (10 faces), recusa da banda (`installStatus`/`errno`)
- [x] Cancelamento seguro: para entre duas partes, não ativa a face e fecha a conexão
- [x] Lista das faces instaladas, com ativar e remover
- [x] Log detalhado dos pacotes TX/RX (copiar/compartilhar); a chave nunca aparece no log

### Futuro (ainda não feito)

- Editor visual de watch face (fundo, relógio digital, data, bateria, passos, batimentos) exportando `.face`
- Importar imagem da galeria como fundo, com recorte automático para 212×520
- Biblioteca de faces salvas no celular

## Como usar

1. **Vincule a banda no Mi Fitness** (uma vez) e obtenha a auth key. Não desvincule depois: isso invalida a chave.
2. Na aba **Chave**, digite a chave ou toque em *Importar de um arquivo* (log do Mi Fitness, `manifest.sqlite` ou ZIP da pasta do Mi Fitness). Se aparecerem várias candidatas, comece pelas marcadas como "campo conhecido".
3. Feche o Mi Fitness (ou tire a permissão de Bluetooth dele), para ele não disputar a conexão.
4. Na aba **Conexão**: *Buscar* → toque na sua banda → *Parear* (confirme na banda) → *Conectar*. O status deve chegar a "Conectado e autenticado".
5. Na aba **Instalar**: *Escolher arquivo*, confira a validação e o preview, marque "Entendo os riscos" e toque em *Instalar na banda*. Deixe o app aberto até terminar.
6. Na aba **Faces**: veja as faces instaladas, ative ou remova.

### Onde fica a chave

- **Android:** logs do Mi Fitness em `Android/data/com.mi.health/files/log/` (ou `com.xiaomi.wearable`). No Android 11+ essa pasta não aparece para outros apps: copie pelo computador (USB/`adb pull`) ou por um gerenciador de arquivos com acesso. Use a **última** chave do arquivo.
- **iPhone/iPad:** Arquivos → No meu iPhone → Mi Fitness → Comprimir, e importe o ZIP. O app lê as linhas `registerList_<região>` do `manifest.sqlite`.

## Compilar

Requisitos: JDK 17+ e Android SDK (plataforma 36).

```bash
./gradlew assembleDebug        # APK em app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # 52 testes unitários (protocolo, cripto, arquivo .face, instalação)
./gradlew lintDebug
```

Ou abra a pasta no Android Studio. Android mínimo: 8.0 (API 26).

## Arquitetura

```
app/src/main/
  proto/xiaomi.proto           subconjunto das mensagens do Gadgetbridge (mesmos números de campo)
  java/.../mibandfaces/
    bluetooth/                 busca/pareamento, transportes SPP (RFCOMM) e BLE (GATT FE95)
    protocol/                  SPP V1 (sondagem), SPP V2 (quadros, CRC-16/ARC), enlace com ACK/NACK/janela,
                               criptografia (derivação de chaves, AES-CCM, AES-CTR)
    band/                      cliente da banda (comandos protobuf, autenticação), watch faces e envio em partes
    face/                      leitura/validação do .face, checagem estrutural, decodificação do preview
    key/                       validação, Keystore e importação da auth key
    log/                       log de pacotes
    ui/                        telas Compose (Conexão, Chave, Instalar, Faces, Logs)
app/src/test/                  testes com uma banda simulada (transporte, autenticação, faces, upload)
```

## O que não está confirmado no código de referência

Detalhes em [docs/PROTOCOLO.md](docs/PROTOCOLO.md#8-o-que-não-está-confirmado-no-código-de-referência). Resumo:

- **BLE vs. Clássico:** o Gadgetbridge conecta a Mi Band 10 por Bluetooth Clássico (SPP). O app usa SPP por padrão; o BLE é alternativa experimental.
- **Limite de 10 faces:** vem da especificação do app, não do Gadgetbridge nem do miband-hack.
- **Tamanho das partes:** segue o Gadgetbridge (`chunkSize − 4`); o miband-hack desconta 2 bytes a mais no SPP.
- **Janela de envio:** 1 quadro por vez (cada parte espera o ACK da anterior), como pedido. É mais lento que a janela anunciada pela banda, mas mais conservador. Ajustável em `LinkConfig.maxWindow`.
- **Hora:** o Gadgetbridge acerta a hora da banda depois de autenticar; este app não envia a hora.
- **NO_BOUND:** uma Mi Band 10 vinculada a um iPhone recusou outro aparelho com `NO_BOUND` (captura do miband-hack). Se isso acontecer, vincule a banda pelo Mi Fitness **neste** celular e use a chave nova.

## Riscos

- Um envio corrompido ou uma watch face malformada pode deixar a banda travada (uma Mi Band 8 deixou de ligar com uma face malformada, segundo o miband-hack). Por isso o app:
  - valida cabeçalho, ID, resolução e a estrutura interna do arquivo antes de liberar o envio;
  - recusa watch faces v3, firmware e arquivos desconhecidos;
  - manda o MD5 e o CRC32 do arquivo inteiro, como o Gadgetbridge, para a banda conferir;
  - nunca corta um pacote no meio ao cancelar, e não ativa uma face cujo envio não terminou.
- Mantenha a banda carregada e perto do celular durante o envio.
- Uso pessoal, no seu próprio aparelho. O app não mexe em firmware.

## Licença

AGPL-3.0-or-later (veja [LICENSE](LICENSE)), porque o código do protocolo e os arquivos `.proto` foram adaptados do Gadgetbridge (AGPL-3.0). A checagem estrutural das faces foi portada do miband-hack (AGPL-3.0).
