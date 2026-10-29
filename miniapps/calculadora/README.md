# Calculadora para a Mi Band 8 e 9

Mini-app (Xiaomi Vela, `.rpk`) de calculadora de bolso, feito para a tela de 192 × 490 da Mi Band 8 e
da Mi Band 9. Na Mi Band 10 (212 px) a tela é ampliada automaticamente.

**Mi Band 8:** a calculadora só funciona se o firmware da sua Mi Band 8 aceitar mini-apps. No teste
do miband-hack (firmware 2.3.14) ela não respondia a nenhum comando de app. Por isso o app do celular
trata a Mi Band 8 como **experimental**: primeiro pergunta à banda quais apps ela tem e só envia o
arquivo se ela aceitar o pedido de instalação. Se ela ficar em silêncio, nada é enviado. Uma watch
face não serve como calculadora: na Mi Band 8 as faces não têm áreas de toque.

## Teclas

```
C   √   %   ÷
7   8   9   ×
4   5   6   −
1   2   3   +
⌫   0   ,   =
```

- As contas são feitas na hora, da esquerda para a direita, como numa calculadora de bolso:
  2 + 3 × 4 = 20.
- **√** tira a raiz quadrada do número no visor: √144 = 12; 9 + √16 = 13.
- **%** como no celular: 200 + 10% = 220 (10% de 200); 200 × 10% = 20; sozinho, 50% = 0,5.
- **⌫** apaga o último dígito; **C** limpa tudo. Divisão por zero e raiz de número negativo mostram
  "Erro" (um número ou C recomeçam).
- Números com vírgula, até 10 dígitos; resultados grandes aparecem em notação científica (1e20). A
  linha de cima mostra a conta; a letra diminui para caber.
- O gesto de voltar da banda fecha o app.

## Código

```
src/manifest.json          pacote io.github.rafaeldatsch4afk.calculadora, sem permissões especiais
src/pages/index/index.ux   visor e teclado
src/common/calc.js         lógica pura (teclas, contas, formatação com vírgula)
src/common/logo.png        ícone
test/calc-check.mjs        conferência da lógica com node
test/pagina-check.mjs      conferência da página: só div/text, variáveis, funções, estilos e toques
```

A página usa só o que já rodou numa Mi Band 10 (`div`, `text`, `for` e o gesto de voltar como na
calculadora que roda nela); o teste recusa outras tags e o alinhamento `flex-end`.

## Compilar

Igual ao [Juros](../calculadora-juros/README.md#compilar): `npm test`, `npm install`, `npm run release`
(com `sign/private.pem` e `sign/certificate.pem`; a chave privada não vai para o repositório).
