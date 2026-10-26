# Mi Band 10 Faces

App Android (Kotlin + Jetpack Compose) para instalar watch faces na **Xiaomi Smart Band 10** direto do celular, sem usar o Mi Fitness para o envio. O protocolo foi portado do [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) e conferido com o [miband-hack](https://github.com/ptrinh/miband-hack).

> ⚠️ **Ainda não foi testado com uma Mi Band 10 real.** Tudo foi validado só com testes automáticos contra uma banda simulada. Leia [Riscos](#riscos) antes de usar.

Resumo do protocolo: [docs/PROTOCOLO.md](docs/PROTOCOLO.md).

## Status

- [x] Busca da banda (BLE + Bluetooth Clássico) e pareamento com o Android
- [x] Conexão por Bluetooth Clássico (SPP/RFCOMM) ou BLE V2 (GATT FE95)
- [x] Sondagem de versão SPP e sessão SPP V2 com ACK/NACK, reenvio e janela de envio
- [x] Log detalhado dos pacotes (aba Logs: copiar/compartilhar)
- [ ] Autenticação com a auth key
- [ ] Envio do arquivo de watch face
- [ ] Lista de faces instaladas

## Compilar

Requisitos: JDK 17+ e Android SDK (plataforma 36).

```bash
./gradlew assembleDebug        # APK em app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # testes unitários
```

Ou abra a pasta no Android Studio.

## Por que Bluetooth Clássico?

O pedido original falava em BLE, mas no Gadgetbridge a Mi Band 10 conecta por **Bluetooth Clássico (SPP)**. A banda também tem um serviço BLE (FE95) com o mesmo protocolo, que o app oferece como alternativa experimental. Detalhes em [docs/PROTOCOLO.md](docs/PROTOCOLO.md#1-transporte-bluetooth-clássico-não-ble).

## Riscos

- Um envio corrompido ou uma watch face malformada pode deixar a banda travada (uma Mi Band 8 deixou de ligar com uma face malformada, segundo o miband-hack). O app valida o arquivo antes de enviar.
- Use por sua conta e risco, só no seu próprio aparelho. O app não mexe em firmware.

## Licença

AGPL-3.0-or-later (veja [LICENSE](LICENSE)), porque o código do protocolo e os arquivos `.proto` foram adaptados do Gadgetbridge (AGPL-3.0). A validação estrutural das faces foi portada do miband-hack (AGPL-3.0).
