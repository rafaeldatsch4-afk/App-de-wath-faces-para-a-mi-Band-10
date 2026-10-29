# Calculadora na Mi Band 8 (watch face)

A Mi Band 8 não aceita mini-apps `.rpk` (ela não responde aos comandos de app), mas as faces de jogo
oficiais da Xiaomi trazem um **app embutido**: um script JavaScript com as imagens dele, aberto por um
botão da face (o "PLAY"). Esta pasta usa o mesmo recurso: pega a face de jogo "谁是学霸" (o quiz de
contas) e troca o quiz por uma calculadora e o botão PLAY por um botão **CALC**.

Dois estilos (`--estilo`):

- **vidro** (padrão): tudo redesenhado em "vidro líquido": papel de parede colorido, painéis de vidro
  fosco com borda clara para bateria, data e saúde, algarismos brancos translúcidos, data em dia/mês e
  dias da semana em português (também na tela AOD). A calculadora tem teclas de vidro, com as
  operações em vidro laranja e o = em verde. O layout (posições e tamanhos) é o da face original;
- **creme**: o visual da face original (creme e marrom); só o botão e o app mudam.

```
C   √   %   ÷
7   8   9   ×
4   5   6   −
1   2   3   +
⌫   0   ,   =
```

- Contas feitas na hora, da esquerda para a direita, como numa calculadora de bolso: 2 + 3 × 4 = 20.
- **√** tira a raiz do número no visor (9 + √16 = 13). **%** como no celular: 200 + 10% = 220;
  200 × 10% = 20; sozinho, 50% = 0,5.
- A linha pequena de cima mostra a conta pendente ("12+"). Divisão por zero, raiz de negativo e
  números grandes demais mostram "Erro" (um número ou C recomeçam).
- Até 10 algarismos, vírgula decimal; resultados grandes ou pequenos em notação científica (1,2346e11).
- **sair** (no alto) ou o gesto de voltar fecham a calculadora.

## Gerar a face

Precisa de Python 3 com Pillow, Node.js 18+ e a fonte DejaVu Sans (`fonts-dejavu`).

```
python3 build.py --base 谁是学霸.bin                 # vidro
python3 build.py --base 谁是学霸.bin --estilo creme
```

A base é o arquivo da face "谁是学霸" para a Mi Band 8 (o ID dela é 266269997). Ela **não** está neste
repositório, porque as imagens e o relógio são da Xiaomi: use a que você baixou para a sua banda. O
resultado (`dist/Calculadora-face.bin`, também com material da Xiaomi) é para uso pessoal; não
redistribua. O `build.py`:

1. desmonta a base e confere que remontá-la sem mudanças reproduz o arquivo **byte a byte** (se não
   reproduzir, para sem gerar nada);
2. desenha as imagens da calculadora e troca os arquivos do app embutido;
3. troca a imagem do botão PLAY por CALC (mesmo tamanho) e atualiza a prévia; no estilo vidro, também
   redesenha o fundo, os ícones e as listas de algarismos e dias da semana com os mesmos tamanhos, e
   troca as posições do dia e do mês (antes confere que a base tem exatamente o layout esperado);
4. troca o ID (970810001) e o nome ("Calculadora"), para não substituir a face original;
5. confere a face nova: cada item que não devia mudar é idêntico ao da base; as imagens e listas
   redesenhadas mantêm cabeçalho, tamanho, quantidade de quadros e tipo de cor; dos elementos, só a
   posição do dia e do mês muda; o botão abre o `calc.js`, e cada imagem usada pelo script existe;
6. grava `dist/previa-calculadora-face.png`, desenhada a partir das mesmas imagens e das posições
   que o script usa (via `test/calc-test.mjs`).

Para instalar, use o app do celular (aba **Instalar**, modelo Mi Band 8). A face de vidro tem cerca
de 1,4 MB (o degradê do fundo comprime pouco); a ~2 KB/s da Mi Band 8, o envio leva uns 12 minutos.
As duas versões usam o mesmo ID: instalar uma substitui a outra.

## Código

```
src/calc.js          o app embutido: tela, teclas e contas
build.py             monta a face a partir da base
vidro.py             desenho do estilo vidro (papel de parede, painéis, texto, ícones)
test/calc-test.mjs   roda o calc.js com uma imitação da banda e confere contas, teclas e tela
```

`node test/calc-test.mjs` confere:

- que o script só usa funções que os jogos oficiais já usam (`create_img`, `create_btn`, `addEvent`,
  `set_img_src`, `set_obj_hidden`, `set_obj_display`, `set_obj_pos`, `exit_game`, `EventMap`,
  `registerEvent`, `Number`), sem `let`, `Math`, métodos de string ou de array;
- as contas, simulando toques (aperta e solta) e lendo o visor pelas imagens mostradas;
- o comportamento se o interpretador da banda só tiver inteiros, ou números de 32 bits.

## O que não se sabe

O interpretador JavaScript da banda não tem documentação. O que está aqui veio dos dois jogos
oficiais ("谁是学霸" e "小人过桥") e do formato visto neles e no
[UnpackMiColorFace](https://github.com/hasechris/UnpackMiColorFace):

- os eventos 1 (apertar) e 8 (soltar) dos botões e 7 (toque) das imagens, e o evento 1 da tela
  (voltar), são usados como nos jogos; o significado exato não é documentado;
- o script não foi testado numa Mi Band 8 real, só na imitação. Ele se protege de um interpretador
  sem decimais ou de 32 bits, mas outras diferenças podem aparecer;
- a calculadora cria cerca de 80 objetos e o app tem 59 arquivos; os jogos oficiais usam menos;
- o vidro é desenhado (o fundo já vem desfocado): a banda não faz o efeito de verdade, então ele não
  muda com o movimento como no iPhone.

**Risco:** uma face malformada já impediu uma Mi Band 8 de ligar (miband-hack). Esta face mantém a
estrutura da oficial e passa pela checagem do app do celular, mas é uma face modificada: mantenha a
banda carregada e perto do celular durante o envio. Se a calculadora não abrir ou travar, troque de
face na banda e remova esta pela aba **Faces** do app.
