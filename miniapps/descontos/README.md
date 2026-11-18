# Descontos: calculadora para a Mi Band

Mini-app (Xiaomi Vela, `.rpk`) que calcula o preço com desconto na própria banda. Feito para a
Mi Band 10 (`designWidth` 212); na Mi Band 9 a tela é reduzida para 192 px automaticamente. A Mi Band 8
só roda mini-apps em modo experimental (veja o README principal).

## Como usar na banda

O app abre direto no teclado e pergunta duas coisas, uma de cada vez:

1. **Preço original** (R$): digite e toque em *OK*. *Sair* fecha o app.
2. **Desconto** (%, de 0 a 100, aceita vírgula, ex.: 12,5): digite e toque em *OK*. *Voltar* volta ao preço.

O resultado mostra o desconto aplicado, o preço original, **quanto você paga** e **quanto você economiza**.
*Voltar* volta ao desconto com os números preenchidos (para testar outra porcentagem); *Nova* limpa
tudo. Ao editar o preço de novo, o desconto anterior continua preenchido. O gesto de voltar da banda vai
sempre para a tela anterior; no preço, fecha o app.

Exemplo: um tênis de R$ 200,00 com 20% de desconto custa **R$ 160,00** (você economiza R$ 40,00).

## As contas

- `desconto = preço × porcentagem ÷ 100`
- `preço final = preço − desconto`

As contas são feitas em centavos, e o desconto é arredondado para o centavo mais próximo: R$ 199,90
com 15% de desconto dá R$ 29,99 de desconto (29,985 arredonda para cima) e R$ 169,91 a pagar. O teclado
aceita até 9 dígitos de reais com 2 casas, e o desconto até 100%.

## Código

```
src/manifest.json            pacote io.github.rafaeldatsch4afk.descontos, sem permissões especiais
src/app.ux
src/pages/index/index.ux     teclado (preço e desconto) e resultado
src/common/descontos.js      lógica pura (digitação, validação, contas, formatação em reais)
src/common/logo.png          ícone
test/descontos-check.mjs     conferência da lógica com node
test/pagina-check.mjs        conferência da página: tags, variáveis e funções do template, estilos e
                             uma sequência de toques simulada (200 + 20% = 160) até o resultado
```

A página usa só o que já rodou numa Mi Band 10 no [Juros](../calculadora-juros/README.md): `div`,
`text`, `if` e `for`, propriedades simples no template (sem expressões), classes montadas no script e
o gesto de voltar tratado como na calculadora que já rodou (`onBackPress` sempre retorna `true` e sai
com `this.$app.exit()`). O teste recusa outras tags e o alinhamento `flex-end`. O tamanho de cada
número é escolhido pelo comprimento do texto, para caber na tela sem rolar.

## Compilar

Igual ao [Juros](../calculadora-juros/README.md#compilar): `npm test`, `npm install`, `npm run release`
(com `sign/private.pem` e `sign/certificate.pem`; a chave privada não vai para o repositório).
