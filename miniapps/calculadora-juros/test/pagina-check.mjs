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
const pasta = mkdtempSync(join(tmpdir(), 'juros-'));
const arquivo = join(pasta, 'pagina.mjs');
const logica = pathToFileURL(join(raiz, 'src/common/juros.js')).href;
writeFileSync(arquivo, script.replace("'../../common/juros.js'", `'${logica}'`));
const pagina = (await import(pathToFileURL(arquivo).href)).default;
rmSync(pasta, { recursive: true });

// --- Conferências estáticas do template ---
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
for (const [, c] of script.matchAll(/'([a-zA-Z ]+)'/g)) {
  // strings do script que são nomes de classe: as que aparecem em atribuições *Cls / cls
  if (/^(tecla|op|calcular|digitado|total)\b|^(vazio|valor|opTexto\w*|calcularTexto\w*|dica|erro|fn|apagada|grande|medio|pequeno|sel|off)$/.test(c)) {
    c.split(/\s+/).forEach((x) => x && classesUsadas.add(x));
  }
}
for (const c of classesUsadas) assert.ok(classesComEstilo.has(c), `classe sem estilo: .${c}`);

// Só propriedades de CSS que os mini-apps conhecidos da banda usam.
const permitidas = new Set([
  'width', 'height', 'flex', 'flex-direction', 'justify-content', 'align-items', 'background-color',
  'padding', 'padding-left', 'margin', 'margin-top', 'margin-bottom', 'border-radius', 'font-size', 'color',
  'text-align', 'font-weight',
]);
for (const [, prop] of estilo.matchAll(/^\s*([a-z-]+)\s*:/gm)) assert.ok(permitidas.has(prop), `propriedade CSS não usual: ${prop}`);

// --- Simulação de toques ---
function novaPagina() {
  const p = { ...structuredClone(pagina.private) };
  // As listas de teclas não são clonáveis com funções; recupera as originais.
  p.teclas = pagina.private.teclas;
  for (const m of metodos) p[m] = pagina[m];
  p.saiu = false;
  p.$app = { exit: () => { p.saiu = true; } };
  return p;
}

const p = novaPagina();
assert.equal(p.telaPrincipal, true);
assert.equal(p.valorTexto, 'Tocar para digitar');
assert.equal(p.calcularCls, 'calcular off');
p.calcularConta(); // não faz nada sem os campos
assert.equal(p.telaPrincipal, true);

// Valor: 1500
p.abrirValor();
assert.equal(p.telaTeclado, true);
assert.equal(p.titulo, 'Valor');
assert.equal(p.aviso, 'em reais');
for (const k of '1500') p.tocar(k);
assert.equal(p.mostrado, 'R$ 1.500');
p.confirmar();
assert.equal(p.telaPrincipal, true);
assert.equal(p.valorTexto, 'R$ 1.500,00');
assert.equal(p.valorCls, 'valor');

// Parcelas: tenta 0 (erro), depois 12; a vírgula não existe nesse teclado
p.abrirParcelas();
assert.equal(p.teclas[3][0].k, '');
p.tocar('');
p.tocar('0');
p.confirmar();
assert.equal(p.telaTeclado, true);
assert.equal(p.aviso, 'Pelo menos 1 parcela');
assert.equal(p.avisoCls, 'erro');
p.tocar('DEL');
p.tocar('1');
p.tocar('2');
assert.equal(p.avisoCls, 'dica');
p.confirmar();
assert.equal(p.parcelasTexto, '12x');

// Cancelar não muda nada
p.abrirParcelas();
p.tocar('9');
p.cancelar();
assert.equal(p.parcelasTexto, '12x');

// Taxa: 2,5
p.abrirTaxa();
for (const k of '2,5') p.tocar(k);
assert.equal(p.mostrado, '2,5%');
p.confirmar();
assert.equal(p.taxaTexto, '2,5%');
assert.equal(p.calcularCls, 'calcular');

// Compostos (padrão)
assert.equal(p.compostoCls, 'op sel');
p.calcularConta();
assert.equal(p.telaResultado, true);
assert.equal(p.tituloResultado, 'Juros compostos');
assert.equal(p.total, 'R$ 2.017,33');
assert.equal(p.juros, 'R$ 517,33');
assert.equal(p.parcela, '12x de R$ 168,11');
assert.equal(p.resumo1, 'R$ 1.500,00 em 12x');
assert.equal(p.resumo2, '2,5% ao mês');

// Gesto de voltar no resultado: volta para a principal sem fechar o app
assert.equal(p.onBackPress(), true);
assert.equal(p.telaPrincipal, true);
assert.equal(p.saiu, false);
// Na principal, fecha o app
assert.equal(p.onBackPress(), true);
assert.equal(p.saiu, true);

// Troca para simples e recalcula com os mesmos números
p.escolherSimples();
assert.equal(p.simplesCls, 'op sel');
assert.equal(p.compostoCls, 'op');
p.calcularConta();
assert.equal(p.tituloResultado, 'Juros simples');
assert.equal(p.total, 'R$ 1.950,00'); // 1500 × (1 + 0,025 × 12)
assert.equal(p.parcela, '12x de R$ 162,50');

// Nova conta limpa tudo
p.novaConta();
assert.equal(p.valorTexto, 'Tocar para digitar');
assert.equal(p.calcularCls, 'calcular off');

console.log('página: template, estilos e toques conferidos');
