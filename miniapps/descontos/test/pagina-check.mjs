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
const pasta = mkdtempSync(join(tmpdir(), 'descontos-'));
const arquivo = join(pasta, 'pagina.mjs');
const logica = pathToFileURL(join(raiz, 'src/common/descontos.js')).href;
writeFileSync(arquivo, script.replace("'../../common/descontos.js'", `'${logica}'`));
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

// Todo onclick chama uma função da página.
for (const [, chamada] of template.matchAll(/onclick="([^"]+)"/g)) {
  const nome = chamada.split('(')[0];
  assert.ok(metodos.includes(nome), `onclick sem função: ${chamada}`);
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

// --- Simulação de toques ---
function novaPagina() {
  const p = { ...structuredClone({ ...pagina.private, teclas: [] }), teclas: pagina.private.teclas };
  for (const m of metodos) {
    p[m] = function (...args) {
      const r = pagina[m].apply(this, args);
      conferirClasses(this);
      return r;
    };
  }
  p.saiu = false;
  p.$app = { exit: () => { p.saiu = true; } };
  return p;
}
function digita(p, teclas) {
  for (const k of teclas.split('')) p.tocar(k);
}

// O app abre direto no preço original, com o mesmo texto que abrir(0) montaria.
{
  const p = novaPagina();
  conferirClasses(p);
  assert.equal(p.telaTeclado, true);
  assert.equal(p.telaResultado, false);
  const inicial = { titulo: p.titulo, mostrado: p.mostrado, aviso: p.aviso, avisoCls: p.avisoCls, digitadoCls: p.digitadoCls, cancelarTexto: p.cancelarTexto };
  p.abrir(0);
  assert.deepEqual({ titulo: p.titulo, mostrado: p.mostrado, aviso: p.aviso, avisoCls: p.avisoCls, digitadoCls: p.digitadoCls, cancelarTexto: p.cancelarTexto }, inicial);
}

// O pedido: um tênis de R$ 200 com 20% de desconto
const p = novaPagina();
assert.equal(p.titulo, 'Preço original');
assert.equal(p.cancelarTexto, 'Sair');
digita(p, '200');
assert.equal(p.mostrado, 'R$ 200');
p.confirmar();
assert.equal(p.telaTeclado, true);
assert.equal(p.titulo, 'Desconto');
assert.equal(p.aviso, '2 de 2 · em %, ex.: 20');
assert.equal(p.mostrado, '0%');
assert.equal(p.cancelarTexto, 'Voltar');
digita(p, '20');
assert.equal(p.mostrado, '20%');
p.confirmar();
assert.equal(p.telaResultado, true);
assert.equal(p.telaTeclado, false);
assert.equal(p.tituloResultado, 'Desconto de 20%');
assert.equal(p.preco, 'De R$ 200,00');
assert.equal(p.total, 'R$ 160,00');
assert.equal(p.economia, 'R$ 40,00');

// Voltar leva ao desconto, com o 20 já preenchido; trocar para 25%
p.voltar();
assert.equal(p.titulo, 'Desconto');
assert.equal(p.mostrado, '20%');
p.tocar('DEL');
p.tocar('DEL');
p.tocar('2');
p.tocar('5');
p.confirmar();
assert.equal(p.tituloResultado, 'Desconto de 25%');
assert.equal(p.total, 'R$ 150,00');
assert.equal(p.economia, 'R$ 50,00');

// Gesto de voltar: resultado → desconto → preço → fecha o app
assert.equal(p.onBackPress(), true);
assert.equal(p.titulo, 'Desconto');
assert.equal(p.onBackPress(), true);
assert.equal(p.titulo, 'Preço original');
assert.equal(p.mostrado, 'R$ 200'); // o preço continua preenchido
assert.equal(p.saiu, false);
assert.equal(p.onBackPress(), true);
assert.equal(p.saiu, true);
p.saiu = false;

// Ao editar o preço, o desconto anterior continua preenchido
p.abrir(0);
digita(p, '1');
p.confirmar();
assert.equal(p.titulo, 'Desconto');
assert.equal(p.mostrado, '25%');

// Erros: preço vazio ou zerado e desconto vazio ou acima de 100%
p.novaConta();
p.confirmar();
assert.equal(p.titulo, 'Preço original');
assert.equal(p.aviso, 'Digite um valor');
assert.ok(p.avisoCls.startsWith('erro '));
p.tocar('0');
p.confirmar();
assert.equal(p.aviso, 'Maior que zero');
p.tocar('5');
assert.ok(p.avisoCls.startsWith('dica '));
assert.equal(p.mostrado, 'R$ 5');
p.confirmar();
assert.equal(p.titulo, 'Desconto');
p.confirmar(); // desconto vazio
assert.equal(p.aviso, 'Digite um valor');
digita(p, '150');
assert.equal(p.mostrado, '150%');
p.confirmar();
assert.equal(p.aviso, 'No máximo 100%');
assert.equal(p.telaTeclado, true);
p.tocar('DEL');
p.tocar('DEL');
p.tocar('DEL');
digita(p, '100');
p.confirmar();
assert.equal(p.total, 'R$ 0,00');
assert.equal(p.economia, 'R$ 5,00');

// "Voltar" do teclado do desconto volta ao preço sem perder nada; "Sair" no preço fecha o app
p.novaConta();
digita(p, '99,9');
assert.equal(p.mostrado, 'R$ 99,9');
p.confirmar();
digita(p, '1');
p.cancelar();
assert.equal(p.titulo, 'Preço original');
assert.equal(p.mostrado, 'R$ 99,9');
p.cancelar();
assert.equal(p.saiu, true);
p.saiu = false;

// Nova conta limpa tudo e volta para o preço
p.novaConta();
assert.equal(p.titulo, 'Preço original');
assert.equal(p.mostrado, 'R$ 0');
assert.deepEqual(p.textos, ['', '']);

// Números grandes cabem na tela (o tamanho da letra diminui)
digita(p, '999999999,99');
p.confirmar();
digita(p, '33,33');
p.confirmar();
assert.equal(p.total, 'R$ 666.699.999,99');
assert.equal(p.economia, 'R$ 333.300.000,00');
assert.equal(p.precoCls, 'preco f16');
assert.equal(p.totalCls, 'total f18'); // 17 caracteres: 17 × 18 × 0,62 = 190 px ≤ 192
assert.equal(p.economiaCls, 'linhaValor f18');

console.log('página: template, estilos e toques conferidos');
