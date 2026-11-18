# Pitágoras: hipotenusa e catetos na Mi Band

Mini-app (Xiaomi Vela, `.rpk`) que usa o teorema de Pitágoras na própria banda para achar a
**hipotenusa** ou um **cateto**. Feito para a Mi Band 10 (`designWidth` 212); na Mi Band 9 a tela é
reduzida para 192 px automaticamente. A Mi Band 8 só roda mini-apps em modo experimental (veja o
README principal).

## Como usar na banda

A tela principal mostra a fórmula `a² + b² = c²` e os três lados: **Cateto a**, **Cateto b** e
**Hipotenusa c**. Você marca com **X** o lado que quer descobrir e digita os outros dois:

1. Toque num lado. O teclado tem **0 a 9**, **X**, vírgula e apagar. Digite o valor e toque em *OK*
   (*Voltar* cancela). Até 4 dígitos antes da vírgula e 2 depois (de 0,01 a 9999,99).
2. A **hipotenusa já vem marcada com X** (o caso mais comum). Para descobrir um cateto, toque nele,
   digite **X** e *OK*: o X sai do lado em que estava, que fica vazio para você digitar.
3. Com os dois valores e o X, o botão verde **Calcular** aparece. Sem o X ele pede *Marque um X*; com
   lado vazio, *Preencha tudo*.

O resultado mostra o lado descoberto, a fórmula usada, os dois valores conhecidos, o quadrado do lado
(`c² = 25`) e a resposta (`c = 5`). *Voltar* volta à tela principal com os números, para trocar um
lado; *Nova* limpa tudo e põe o X de novo na hipotenusa. O gesto de voltar da banda vai para a tela
anterior; na tela principal, fecha o app.

Quando a raiz não é exata, a legenda acima da resposta vira **Aproximado** e o valor vem com 4 casas
decimais (1 e 1 dão `c = 1,4142`). Se você digitar uma hipotenusa que não é o maior lado, o app avisa
("A hipotenusa deve ser o maior lado").

## As contas

- Hipotenusa: `c² = a² + b²`
- Cateto: `a² = c² - b²` ou `b² = c² - a²`

Os lados têm até 2 casas decimais, então as contas são feitas em números inteiros (centésimos; os
quadrados, em décimos de milésimo), sem erro de ponto flutuante: 0,3 e 0,4 dão `c = 0,5` exatos. Só a
raiz quadrada é calculada com `Math.sqrt`, e o app sabe se ela é exata (o quadrado é inteiro) ou não.

Exemplos: 3 e 4 dão `c = 5`; 5 e 12 dão `c = 13`; `c = 5` e `b = 3` dão `a = 4`; 2 e 3 dão
`c = 3,6056` (aproximado).

## Código

```
src/manifest.json            pacote io.github.rafaeldatsch4afk.pitagoras, sem permissões especiais
src/app.ux
src/pages/index/index.ux     tela principal (três lados), teclado com X e resultado
src/common/pitagoras.js      lógica pura (digitação com X, validação, contas, textos do resultado)
src/common/logo.png          ícone
test/pitagoras-check.mjs     conferência da lógica com node, inclusive 20 mil casos comparados com a
                             conta em ponto flutuante
test/pagina-check.mjs        conferência da página: tags, variáveis e funções do template, estilos,
                             larguras dos textos e uma sequência de toques simulada (3, 4 e X = 5)
```

A página usa só o que já rodou numa Mi Band 10 no [Juros](../calculadora-juros/README.md): `div`,
`text`, `if` e `for`, propriedades simples no template (sem expressões), classes montadas no script e
o gesto de voltar tratado como na calculadora que já rodou (`onBackPress` sempre retorna `true` e sai
com `this.$app.exit()`). O teste recusa outras tags, argumentos em `onclick` (menos o teclado
numérico, como no Juros) e o alinhamento `flex-end`, e confere que todo texto cabe na largura da tela
(mensagens de erro de no máximo 19 caracteres por linha, em até duas linhas). Os textos usam só
caracteres comuns (`²`, `=`, `+`, `-`); nada de `√` ou `≈`, que a fonte da banda pode não ter.

## Compilar

Igual ao [Juros](../calculadora-juros/README.md#compilar): `npm test`, `npm install`, `npm run release`
(com `sign/private.pem` e `sign/certificate.pem`; a chave privada não vai para o repositório).
