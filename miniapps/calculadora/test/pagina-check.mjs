// Conferência da página (src/pages/index/index.ux) sem a banda: só tags já testadas na banda,
// o template só usa dados e funções que existem, toda classe tem estilo, e toques simulados
// chegam ao resultado certo. Roda com node puro: npm test
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const raiz = join(dirname(fileURLToPath(import.meta.url)), '..');
const ux = readFileSync(join(raiz, 'src/pages/index/index.ux'), 'utf8');
const template = ux.slice(ux.indexOf('<template>'), ux.indexOf('</template>')).replace(/<!--[\s\S]*?-->/g, '');
const script = ux.slice(ux.indexOf('<script>') + 8, ux.indexOf('</script>'));
const estilo = ux.slice(ux.indexOf('<style>') + 7, ux.indexOf('</style>'));

const pasta = mkdtempSync(join(tmpdir(), 'calc-'));
const arquivo = join(pasta, 'pagina.mjs');
const logica = pathToFileURL(join(raiz, 'src/common/calc.js')).href;
writeFileSync(arquivo, script.replace("'../../common/calc.js'", `'${logica}'`));
const pagina = (await import(pathToFileURL(arquivo).href)).default;
rmSync(pasta, { recursive: true });

// Só as tags que já rodaram numa Mi Band 10 (o <list> deixou o Juros 1.1.0 com a tela preta).
for (const [, tag] of template.matchAll(/<([a-z][\w-]*)/g)) {
  assert.ok(['template', 'div', 'text'].includes(tag), `tag não testada na banda: <${tag}>`);
}

const dados = Object.keys(pagina.private);
const metodos = Object.keys(pagina).filter((k) => typeof pagina[k] === 'function');
const variaveisDeLaco = ['linha', 't'];
for (const [, expr] of template.matchAll(/\{\{([^}]+)\}\}/g)) {
  const e = expr.trim();
  if (/^\w+ in \w+$/.test(e)) {
    const lista = e.split(' in ')[1];
    assert.ok(dados.includes(lista) || variaveisDeLaco.includes(lista), `lista do for inexistente: ${e}`);
    continue;
  }
  assert.match(e, /^[\w.]+$/, `expressão complexa no template: {{${e}}}`);
  assert.ok(dados.includes(e.split('.')[0]) || variaveisDeLaco.includes(e.split('.')[0]), `variável inexistente: {{${e}}}`);
}
for (const [, chamada] of template.matchAll(/onclick="([^"]+)"/g)) {
  assert.ok(metodos.includes(chamada.split('(')[0]), `onclick sem função: ${chamada}`);
}

const classesComEstilo = new Set([...estilo.matchAll(/\.([A-Za-z][\w-]*)\s*\{/g)].map((m) => m[1]));
for (const [, c] of template.matchAll(/class="([^"{]+)"/g)) {
  for (const x of c.split(/\s+/)) if (x) assert.ok(classesComEstilo.has(x), `classe sem estilo: .${x}`);
}
const permitidas = new Set([
  'width', 'height', 'flex', 'flex-direction', 'justify-content', 'align-items', 'background-color',
  'padding', 'padding-left', 'padding-top', 'margin', 'margin-top', 'margin-bottom', 'border-radius', 'font-size',
  'color', 'text-align', 'font-weight',
]);
for (const [, prop] of estilo.matchAll(/^\s*([a-z-]+)\s*:/gm)) assert.ok(permitidas.has(prop), `propriedade CSS não usual: ${prop}`);
// O alinhamento à direita é feito com text-align (a calculadora que roda na banda não usa flex-end).
assert.ok(!/flex-end|flex-start|space-/.test(estilo), 'alinhamento flex não testado na banda');

function conferirClasses(p) {
  const valores = [p.visorCls, p.expressaoCls];
  for (const linha of p.teclas) for (const t of linha) valores.push(t.cls, t.textoCls);
  for (const v of valores) for (const c of v.split(/\s+/)) if (c) assert.ok(classesComEstilo.has(c), `classe sem estilo: .${c}`);
}

// Simulação de toques
const p = { ...pagina.private, estado: { ...pagina.private.estado }, saiu: false };
p.$app = { exit: () => { p.saiu = true; } };
for (const m of metodos) p[m] = pagina[m];
conferirClasses(p);
assert.equal(p.teclas.length, 5);
assert.ok(p.teclas.every((l) => l.length === 4));
const todas = p.teclas.flat().map((t) => t.k);
for (const k of ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9', ',', 'C', 'DEL', '%', '√', '+', '−', '×', '÷', '=']) {
  assert.ok(todas.includes(k), `falta a tecla ${k}`);
}

const toca = (seq) => { for (const k of seq) { p.tocar(k); conferirClasses(p); } };
toca(['1', '2', '+', '3', '=']);
assert.equal(p.visor, '15');
assert.equal(p.expressao, '12 + 3 =');
toca(['×', '2', '=']);
assert.equal(p.visor, '30');
toca(['C']);
assert.equal(p.visor, '0');
assert.equal(p.expressao, '');
// Número comprido: a letra diminui para caber
toca(['1', '2', '3', '4', '5', '6', '7', '8', '9', '0']);
assert.equal(p.visor, '1234567890');
assert.notEqual(p.visorCls, 'valor f48');
toca(['C', '1', '÷', '0', '=']);
assert.equal(p.visor, 'Erro');
// Raiz e porcentagem pelos botões
toca(['C', '8', '1', '√']);
assert.equal(p.visor, '9');
assert.equal(p.expressao, '√81');
toca(['C', '2', '0', '0', '+', '1', '5', '%', '=']);
assert.equal(p.visor, '230');

// Gesto de voltar fecha o app
assert.equal(p.onBackPress(), true);
assert.equal(p.saiu, true);

console.log('página: template, estilos e toques conferidos');
