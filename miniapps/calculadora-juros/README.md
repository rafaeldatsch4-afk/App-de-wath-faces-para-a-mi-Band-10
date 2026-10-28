# Juros: calculadora para a Mi Band

Mini-app (Xiaomi Vela, `.rpk`) que calcula juros simples e compostos na própria banda. Feito para a
Mi Band 10 (`designWidth` 212); na Mi Band 9 a tela é reduzida para 192 px automaticamente. A Mi Band 8
não roda mini-apps.

## Como usar na banda

Testado numa Mi Band 10 real (versão 1.0.0). Desde a 1.1.0 tudo é maior e a tela principal e o
resultado rolam para baixo (arraste com o dedo). O tamanho de cada número é escolhido pelo
comprimento do texto, para caber na largura da tela.

A tela principal tem quatro barras, de cima para baixo:

1. **Valor** (R$): toque para abrir o teclado, digite e toque em *OK*.
2. **Parcelas (vezes)**: de 1 a 600, sem vírgula.
3. **Tipo de juros**: toque em *Simples* ou *Composto* (o escolhido fica laranja).
4. **Taxa ao mês** (%): aceita vírgula, de 0 a 100.

Com tudo preenchido, o botão **Calcular** (no fim da lista) fica verde. O resultado mostra o total a pagar, quanto
disso é juros e o valor de cada parcela. *Voltar* mantém os números (para trocar o tipo, por
exemplo); *Nova conta* limpa tudo. O gesto de voltar da banda volta para a tela principal; nela, fecha o app.

## As contas

Com `taxa` em decimal (2% = 0,02):

- Juros simples: `total = valor × (1 + taxa × parcelas)`
- Juros compostos: `total = valor × (1 + taxa) ^ parcelas`
- Nos dois: `juros = total − valor` e `parcela = total ÷ parcelas` (parcelas iguais)

Exemplo: R$ 1.000,00 em 12x a 2% ao mês dá R$ 1.240,00 (12x de R$ 103,33) com juros simples e
R$ 1.268,24 (12x de R$ 105,69) com juros compostos.

Observação: financiamentos de banco costumam usar a Tabela Price, em que a parcela é calculada de
outro jeito (`parcela = valor × taxa ÷ (1 − (1 + taxa) ^ −parcelas)`) e sai menor que o total
composto dividido pelas parcelas. Este app não faz a Tabela Price.

## Código

```
src/manifest.json          pacote io.github.rafaeldatsch4afk.juros, sem permissões especiais
src/app.ux
src/pages/index/index.ux   telas: principal (barras), teclado e resultado
src/common/juros.js        lógica pura (digitação, validação, contas, formatação em reais)
src/common/logo.png        ícone
test/juros-check.mjs       conferência da lógica com node
test/pagina-check.mjs      conferência da página: variáveis e funções do template, estilos e uma
                           sequência de toques simulada até o resultado
```

Para rodar bem na banda, a página usa só padrões vistos em mini-apps que já rodam numa Mi Band 10:
propriedades simples no template (sem expressões), classes montadas no script, `for` sobre listas
de objetos, e o gesto de voltar tratado como na calculadora que já rodou (`onBackPress` sempre
retorna `true` e sai com `this.$app.exit()`). O `.rpk` é gerado em modo release (código minificado),
como o Fruits_cut.

## Compilar

Requer Node 18 ou mais novo. A compilação usa a ferramenta oficial da Xiaomi, `aiot-toolkit` 2.0.5
(a mesma versão que gerou outros mini-apps da Mi Band 10).

```bash
cd miniapps/calculadora-juros
npm test                # confere a lógica (não precisa instalar nada)
npm install             # baixa o aiot-toolkit
npm run build           # dist/…debug….rpk, assinado com a chave de depuração da ferramenta
npm run release         # dist/…release….rpk, assinado com sign/private.pem + sign/certificate.pem
```

Para o `release`, crie antes um par de chaves próprio (comando da documentação do Vela):

```bash
mkdir -p sign && openssl req -newkey rsa:2048 -nodes -keyout sign/private.pem -x509 -days 3650 \
  -out sign/certificate.pem -subj "/CN=io.github.rafaeldatsch4afk.juros"
```

A chave privada (`sign/private.pem`) não vai para o repositório. Uma atualização assinada com outra
chave pode ser recusada pela banda: nesse caso, remova o app na aba **Faces** do app Mi Band 10 Faces
e instale de novo.

Depois instale o `.rpk` pela aba **Instalar** do app Mi Band 10 Faces.
