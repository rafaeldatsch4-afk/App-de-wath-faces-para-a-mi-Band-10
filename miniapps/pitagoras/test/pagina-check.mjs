// Conferência da página (src/pages/index/index.ux) sem a banda: o template só usa dados e funções
// que existem, toda classe tem estilo, e uma sequência de toques chega ao resultado certo.
// Roda com node puro: npm test
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const raiz = join(dirname(fileURLToPath(import.meta.url)), '..');
const ux = readFileSync(join(raiz, 'src/pages/index/index.ux'), 'utf8');
const template = ux.slice(ux.indexOf('<template>'), ux.indexOf('</template>'));
const script = ux.slice(ux.indexOf('<script>') + 8, ux.indexOf('</script>'));
const estilo = ux.slice(ux.indexOf('<style>') + 7, ux.indexOf('</style>'));

// Carrega o <script> da página como módulo, apontando o import para a lógica real.
const pasta = mkdtempSync(join(tmpdir(), 'pitagoras-'));
const arquivo = join(pasta, 'pagina.mjs');
const logica = pathToFileURL(join(raiz, 'src/common/pitagoras.js')).href;
writeFileSync(arquivo, script.replace("'../../common/pitagoras.js'", `'${logica}'`));
const pagina = (await import(pathToFileURL(arquivo).href)).default;
rmSync(pasta, { recursive: true });

// --- Conferências estáticas do template ---
// Só as tags que já rodaram na Mi Band 10 (o Juros 1.0.0). O <list> deixou a tela preta.
for (const [, tag] of template.matchAll(/<([a-z][\w-]*)/g)) {
  assert.ok(['template', 'div', 'text'].includes(tag), `tag não testada na banda: <${tag}>`);
}
const dados = Object.keys(pagina.private);
const metodos = Object.keys(pagina).filter((k) => typeof pagina[k] === 'function');
const variaveisDeLaco = ['linha', 't'];

// Toda {{expressão}} é um nome simples dos dados ou um campo da variável do laço.
for (const [, expr] of template.matchAll(/\{\{([^}]+)\}\}/g)) {
  const e = expr.trim();
  if (/^\w+ in \w+$/.test(e)) {
    const lista = e.split(' in ')[1];
    assert.ok(dados.includes(lista) || variaveisDeLaco.includes(lista), `lista do for inexistente: ${e}`);
    continue;
  }
  assert.match(e, /^[\w.]+$/, `expressão complexa no template: {{${e}}}`);
  const base = e.split('.')[0];
  assert.ok(dados.includes(base) || variaveisDeLaco.includes(base), `variável inexistente no template: {{${e}}}`);
}

// Todo onclick chama uma função da página; só o teclado numérico leva argumento (como no Juros).
for (const [, chamada] of template.matchAll(/onclick="([^"]+)"/g)) {
  const nome = chamada.split('(')[0];
  assert.ok(metodos.includes(nome), `onclick sem função: ${chamada}`);
  if (chamada.includes('(')) assert.equal(chamada, 'tocar(t.k)', `argumento não testado na banda: ${chamada}`);
}

// Toda classe usada (fixa no template ou montada no script) tem estilo.
const classesComEstilo = new Set([...estilo.matchAll(/\.([A-Za-z][\w-]*)\s*\{/g)].map((m) => m[1]));
const classesUsadas = new Set();
for (const [, c] of template.matchAll(/class="([^"{]+)"/g)) c.split(/\s+/).forEach((x) => x && classesUsadas.add(x));
for (const c of classesUsadas) assert.ok(classesComEstilo.has(c), `classe sem estilo: .${c}`);

// Só propriedades de CSS que os mini-apps conhecidos da banda usam, e nada de flex-end.
const permitidas = new Set([
  'width', 'height', 'flex', 'flex-direction', 'justify-content', 'align-items', 'background-color',
  'padding', 'margin', 'margin-top', 'margin-bottom', 'border-radius', 'font-size', 'color',
  'text-align', 'font-weight',
]);
for (const [, prop] of estilo.matchAll(/^\s*([a-z-]+)\s*:/gm)) assert.ok(permitidas.has(prop), `propriedade CSS não usual: ${prop}`);
assert.ok(!/flex-end/.test(estilo), 'flex-end não foi testado na banda');

// Classes montadas pelo script (propriedades *Cls e as teclas) também precisam ter estilo.
function conferirClasses(p) {
  const valores = Object.keys(p).filter((k) => /Cls$/.test(k)).map((k) => p[k]);
  for (const linha of p.teclas) for (const t of linha) valores.push(t.cls);
  for (const v of valores) {
    for (const c of String(v).split(/\s+/)) if (c) assert.ok(classesComEstilo.has(c), `classe sem estilo: .${c} (de "${v}")`);
  }
}

// Todo texto mostrado cabe numa linha da tela (192 px; 0,62 da altura da letra por caractere).
function conferirLargura(p) {
  const pares = [
    ['aTexto', 'aCls'], ['bTexto', 'bCls'], ['cTexto', 'cCls'], ['mostrado', 'digitadoCls'], ['aviso', 'avisoCls'],
    ['tituloResultado', 'tituloResultadoCls'], ['dado1', 'dado1Cls'], ['dado2', 'dado2Cls'], ['quadrado', 'quadradoCls'],
    ['resposta', 'respostaCls'], ['mensagem1', 'mensagemCls'], ['mensagem2', 'mensagemCls'],
  ];
  for (const [texto, cls] of pares) {
    const tamanho = Number(/f(\d+)/.exec(p[cls])?.[1] ?? /\.vazio\s*\{\s*font-size:\s*(\d+)/.exec(estilo)[1]);
    const largura = p[texto].length * tamanho * 0.62;
    const limite = /^[abc]Texto$/.test(texto) ? 168 : 192;
    assert.ok(largura <= limite, `"${p[texto]}" (${p[cls]}) passa da largura: ${largura.toFixed(0)} > ${limite}`);
  }
}

// --- Simulação de toques ---
function novaPagina() {
  const p = { ...structuredClone({ ...pagina.private, teclas: [] }), teclas: pagina.private.teclas };
  for (const m of metodos) {
    p[m] = function (...args) {
      const r = pagina[m].apply(this, args);
      conferirClasses(this);
      conferirLargura(this);
      return r;
    };
  }
  p.saiu = false;
  p.$app = { exit: () => { p.saiu = true; } };
  return p;
}
function digita(p, teclas) {
  for (const k of teclas.split('')) {
    if (k === 'X') p.tocarX();
    else if (k === ',') p.tocarVirgula();
    else if (k === '0') p.tocarZero();
    else p.tocar(k);
  }
}
function lado(p, abrir, teclas) {
  p[abrir]();
  digita(p, teclas);
  p.confirmar();
}

// Estado inicial: a hipotenusa já é o X
const p = novaPagina();
conferirClasses(p);
conferirLargura(p);
assert.equal(p.telaPrincipal, true);
assert.equal(p.aTexto, 'Tocar aqui');
assert.equal(p.bTexto, 'Tocar aqui');
assert.equal(p.cTexto, 'X');
assert.equal(p.cCls, 'valorX f32');
assert.equal(p.calcularTexto, 'Preencha tudo');
assert.equal(p.calcularCls, 'botaoGrande off');
// Os textos do estado inicial são os mesmos que atualizarPrincipal() monta
{
  const antes = ['aTexto', 'aCls', 'bTexto', 'bCls', 'cTexto', 'cCls', 'mensagem1', 'mensagem2', 'mensagemCls', 'calcularCls', 'calcularTextoCls', 'calcularTexto'].map((k) => p[k]);
  p.atualizarPrincipal();
  assert.deepEqual(['aTexto', 'aCls', 'bTexto', 'bCls', 'cTexto', 'cCls', 'mensagem1', 'mensagem2', 'mensagemCls', 'calcularCls', 'calcularTextoCls', 'calcularTexto'].map((k) => p[k]), antes);
}
p.calcularConta(); // com o botão desligado, mostra o motivo em vermelho e continua na principal
assert.equal(p.telaPrincipal, true);
assert.equal(p.mensagem1, 'Preencha tudo');
assert.ok(p.mensagemCls.startsWith('erro '));

// O clássico 3, 4, 5
lado(p, 'abrirA', '3');
assert.equal(p.telaPrincipal, true);
assert.equal(p.aTexto, '3');
assert.equal(p.calcularTexto, 'Preencha tudo');
p.abrirB();
assert.equal(p.titulo, 'Cateto b');
assert.equal(p.mostrado, '0');
assert.equal(p.aviso, 'X para descobrir');
digita(p, '4');
assert.equal(p.mostrado, '4');
p.confirmar();
assert.equal(p.bTexto, '4');
assert.equal(p.calcularTexto, 'Calcular');
assert.equal(p.calcularCls, 'botaoGrande verde');
assert.ok(p.mensagem1 === 'Toque num lado,' && p.mensagem2 === 'X = o que falta');
p.calcularConta();
assert.equal(p.telaResultado, true);
assert.equal(p.telaPrincipal, false);
assert.equal(p.tituloResultado, 'Hipotenusa (c)');
assert.equal(p.formula, 'c² = a² + b²');
assert.equal(p.dado1, 'a = 3');
assert.equal(p.dado2, 'b = 4');
assert.equal(p.quadrado, 'c² = 25');
assert.equal(p.legendaResposta, 'Resultado');
assert.equal(p.resposta, 'c = 5');

// Voltar leva à tela principal, com os números
p.voltar();
assert.equal(p.telaPrincipal, true);
assert.equal(p.aTexto, '3');
assert.equal(p.cTexto, 'X');

// Mudar o X para o cateto a: o X sai da hipotenusa (que fica vazia até digitar 5)
p.abrirA();
digita(p, 'X');
assert.equal(p.mostrado, 'X');
p.confirmar();
assert.equal(p.aTexto, 'X');
assert.equal(p.aCls, 'valorX f32');
assert.equal(p.cTexto, 'Tocar aqui');
assert.equal(p.calcularTexto, 'Preencha tudo');
lado(p, 'abrirB', '');
// (confirmar com o texto atual "4" continua válido)
assert.equal(p.bTexto, '4');
p.abrirB();
p.tocarApagar();
digita(p, '3');
p.confirmar();
lado(p, 'abrirC', '5');
assert.equal(p.calcularTexto, 'Calcular');
p.calcularConta();
assert.equal(p.tituloResultado, 'Cateto (a)');
assert.equal(p.formula, 'a² = c² - b²');
assert.equal(p.dado1, 'b = 3');
assert.equal(p.dado2, 'c = 5');
assert.equal(p.quadrado, 'a² = 16');
assert.equal(p.resposta, 'a = 4');

// Marcar X num lado que já tinha número, e depois um número no lugar do X
p.voltar();
p.abrirB();
digita(p, 'X'); // b vira o X; a (que era X) fica vazio
p.confirmar();
assert.equal(p.bTexto, 'X');
assert.equal(p.aTexto, 'Tocar aqui');
assert.equal(p.calcularTexto, 'Preencha tudo');
p.abrirA();
digita(p, '5');
p.confirmar();
assert.equal(p.calcularTexto, 'Calcular'); // a = 5, b = X, c = 5 → vai dar o erro da hipotenusa
p.calcularConta();
assert.equal(p.telaPrincipal, true);
assert.equal(p.mensagem1, 'A hipotenusa deve');
assert.equal(p.mensagem2, 'ser o maior lado');
assert.ok(p.mensagemCls.startsWith('erro '));
p.abrirC();
assert.equal(p.mostrado, '5'); // o teclado abre com o valor anterior; os dígitos entram depois dele
p.tocarApagar();
digita(p, '13');
p.confirmar();
assert.equal(p.mensagem1, 'Toque num lado,'); // a dica volta
p.calcularConta();
assert.equal(p.resposta, 'b = 12');
assert.equal(p.tituloResultado, 'Cateto (b)');
assert.equal(p.dado1, 'a = 5');
assert.equal(p.dado2, 'c = 13');

// Sem nenhum X: o botão pede para marcar um
p.voltar();
p.abrirB();
digita(p, '1');
p.tocarApagar();
digita(p, '12');
p.confirmar();
assert.equal(p.calcularTexto, 'Marque um X');
assert.equal(p.calcularCls, 'botaoGrande off');
p.calcularConta();
assert.equal(p.mensagem1, 'Marque um X');
assert.equal(p.mensagem2, ' ');

// Erros do teclado: vazio e zero; o aviso volta a ser a dica ao digitar
p.abrirA();
p.tocarApagar();
p.tocarApagar();
assert.equal(p.mostrado, '0');
p.confirmar();
assert.equal(p.telaTeclado, true);
assert.equal(p.aviso, 'Digite um valor');
assert.ok(p.avisoCls.startsWith('erro '));
p.tocarZero();
p.confirmar();
assert.equal(p.aviso, 'Maior que zero');
digita(p, ',5');
assert.equal(p.mostrado, '0,5');
assert.ok(p.avisoCls.startsWith('dica '));
p.confirmar();
assert.equal(p.aTexto, '0,5');

// A vírgula e as duas casas
p.abrirA();
for (const k of ['2', ',', '5', '0', '7']) k === ',' ? p.tocarVirgula() : p.tocar(k);
assert.equal(p.mostrado, '0,52'); // 0,5 + 2 → 0,52; a segunda vírgula e as casas além de 2 não entram
p.tocarApagar();
p.tocarApagar();
p.tocarApagar();
p.tocarApagar();
digita(p, '2,5');
assert.equal(p.mostrado, '2,5');
p.confirmar();
assert.equal(p.aTexto, '2,5');

// X seguido de dígito recomeça; X no meio de um número troca tudo
p.abrirA();
digita(p, 'X');
digita(p, '7');
assert.equal(p.mostrado, '7');
digita(p, '1X');
assert.equal(p.mostrado, 'X');

// Voltar do teclado não muda nada; o gesto de voltar também
p.cancelar();
assert.equal(p.telaPrincipal, true);
assert.equal(p.aTexto, '2,5');
p.abrirA();
assert.equal(p.onBackPress(), true);
assert.equal(p.telaPrincipal, true);
assert.equal(p.aTexto, '2,5');

// Gesto de voltar: resultado → principal → fecha o app
p.novaConta();
assert.deepEqual(p.textos, ['', '', 'X']);
assert.equal(p.aTexto, 'Tocar aqui');
assert.equal(p.cTexto, 'X');
lado(p, 'abrirA', '1');
lado(p, 'abrirB', '1');
p.calcularConta();
assert.equal(p.resposta, 'c = 1,4142');
assert.equal(p.legendaResposta, 'Aproximado');
assert.equal(p.onBackPress(), true);
assert.equal(p.telaPrincipal, true);
assert.equal(p.saiu, false);
assert.equal(p.onBackPress(), true);
assert.equal(p.saiu, true);
p.saiu = false;

// Números no limite cabem na tela (as conferências de largura rodam a cada toque)
p.novaConta();
lado(p, 'abrirA', '9999,99');
lado(p, 'abrirB', '9999,99');
assert.equal(p.aTexto, '9999,99');
p.calcularConta();
assert.equal(p.resposta, 'c = 14142,1215');
assert.equal(p.quadrado, 'c² = 199999600,0002');
assert.equal(p.quadradoCls, 'quadrado f16');
assert.equal(p.legendaResposta, 'Aproximado');

console.log('página: template, estilos e toques conferidos');
