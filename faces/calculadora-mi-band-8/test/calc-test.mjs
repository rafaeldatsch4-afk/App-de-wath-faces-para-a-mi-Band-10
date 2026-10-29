// Conferência do script da calculadora com node puro: node test/calc-test.mjs [cenas.json]
//
// Roda src/calc.js com uma imitação das funções da banda (as mesmas que os jogos oficiais usam),
// simula toques nas teclas e lê o visor pelas imagens mostradas. Com um argumento, grava as cenas
// (imagens e posições) de alguns estados para o build.py desenhar a prévia.
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import vm from 'node:vm';

const fonte = readFileSync(new URL('../src/calc.js', import.meta.url), 'utf8');

// ---- 1. Só o JS simples e as funções que os jogos oficiais chamam ----

const API_DOS_JOGOS = [
  'create_img', 'create_btn', 'addEvent', 'set_img_src', 'set_obj_hidden', 'set_obj_display',
  'set_obj_pos', 'exit_game', 'EventMap', 'registerEvent', 'Number',
];
const semComentarios = fonte.replace(/\/\/.*$/gm, '').replace(/'[^'\n]*'/g, "''");
const proibidos = [
  [/\blet\b/, 'let'], [/\bconst\b/, 'const'], [/=>/, 'arrow function'], [/`/, 'template string'],
  [/\bMath\b/, 'Math'], [/\.\s*[A-Za-z_]\w*\s*\(/, 'chamada de método'], [/\bclass\b/, 'class'],
  [/\bnew\s+(?!EventMap\b)/, 'new (além de EventMap)'], [/\bint\b/, 'int (reservada no ES3)'],
];
for (const [re, nome] of proibidos) assert.ok(!re.test(semComentarios), `calc.js usa ${nome}`);
const definidas = new Set([...semComentarios.matchAll(/function\s+([A-Za-z_]\w*)/g)].map((m) => m[1]));
const palavras = new Set(['if', 'for', 'while', 'function', 'return', 'switch', 'do']);
for (const m of semComentarios.matchAll(/(?<![.\w])([A-Za-z_]\w*)\s*\(/g)) {
  const nome = m[1];
  assert.ok(palavras.has(nome) || definidas.has(nome) || API_DOS_JOGOS.includes(nome), `calc.js chama ${nome}(), que os jogos oficiais não usam`);
}

// ---- 2. Imitação da banda ----

function banda(src = fonte) {
  const objs = [];
  const eventos = [];
  let saiu = 0;
  const novo = (o) => {
    objs.push({ ...o, oculto: false });
    return objs.length; // a banda devolve um identificador; 0 é a tela
  };
  const obj = (id) => {
    assert.ok(Number.isInteger(id) && id >= 1 && id <= objs.length, `objeto inválido: ${id}`);
    return objs[id - 1];
  };
  const ctx = {
    create_img(pai, arquivo, x, y) {
      assert.equal(arguments.length, 4);
      assert.match(arquivo, /^[a-z]_?[a-z0-9]*\.bin$|^[a-z]+\.bin$/);
      return novo({ tipo: 'img', pai, arquivo, x, y });
    },
    create_btn(pai, x, y, l, a) {
      assert.equal(arguments.length, 5);
      return novo({ tipo: 'btn', pai, x, y, l, a });
    },
    addEvent(id, lista, fn) {
      assert.ok(Array.isArray(lista) && typeof fn === 'function');
      eventos.push({ id: obj(id) && id, lista, fn });
    },
    set_img_src(id, arquivo) { obj(id).arquivo = arquivo; },
    set_obj_hidden(id) { obj(id).oculto = true; },
    set_obj_display(id) { obj(id).oculto = false; },
    set_obj_pos(id, x, y) { Object.assign(obj(id), { x, y }); },
    exit_game() { saiu++; },
    EventMap: function (a, lista, fn, b) { Object.assign(this, { a, lista, fn, b }); },
    registerEvent(em) { eventos.push({ id: 0, lista: em.lista, fn: em.fn }); },
    Number,
    NaN,
  };
  vm.createContext(ctx);
  vm.runInContext(src, ctx);
  const botoes = objs.map((o, i) => ({ ...o, id: i + 1 })).filter((o) => o.tipo === 'btn');
  const teclaPorBotao = (b) => objs.find((o) => o.pai === b.id).arquivo.slice(2, -4);
  const api = {
    objs,
    get saiu() { return saiu; },
    tocar(codigos) {
      for (const k of codigos) {
        const b = botoes.find((x) => teclaPorBotao(x) === k);
        assert.ok(b, `tecla ${k} não existe`);
        const ev = eventos.find((e) => e.id === b.id);
        ev.fn(1);
        assert.ok(!objs.find((o) => o.arquivo === 'destaque.bin').oculto, 'destaque aparece ao apertar');
        ev.fn(8);
        assert.ok(objs.find((o) => o.arquivo === 'destaque.bin').oculto, 'destaque some ao soltar');
      }
      return api;
    },
    // Texto mostrado: as casas visíveis com prefixo g_ (visor) ou s_ (linha de cima).
    ler(prefixo) {
      const nomes = { v: ',', m: '-', e: 'e', a: '+', s: '−', x: '×', d: '÷' };
      if (prefixo === 'g_' && !objs.find((o) => o.arquivo === 'erro.bin').oculto) return 'Erro';
      return objs
        .filter((o) => o.tipo === 'img' && o.pai === 0 && !o.oculto && o.arquivo.startsWith(prefixo))
        .sort((a, b) => a.x - b.x)
        .map((o) => {
          const s = o.arquivo.slice(2, -4);
          return nomes[s] ?? s;
        })
        .join('');
    },
    get visor() { return api.ler('g_'); },
    get linha() { return api.ler('s_'); },
    voltar() { eventos.find((e) => e.id === 0 && e.lista.includes(1)).fn(); },
    sair() { eventos.find((e) => objs[e.id - 1]?.arquivo === 'sair.bin').fn(7); },
  };
  return api;
}

// Teclas do jeito que aparecem na tela.
const COD = { C: 'c', '√': 'r', '%': 'p', '÷': 'd', '×': 'x', '−': 's', '+': 'a', '⌫': 'b', ',': 'v', '=': 'i' };
function conta(seq, src) {
  const b = banda(src);
  b.tocar([...seq].map((c) => COD[c] ?? c));
  return b;
}
const v = (seq, src) => conta(seq, src).visor;

// ---- 3. Contas ----

assert.equal(banda().visor, '0');
assert.equal(v('2+3='), '5');
assert.equal(v('7−10='), '-3');
assert.equal(v('6×7='), '42');
assert.equal(v('1÷4='), '0,25');
assert.equal(v('1,5+1,5='), '3');
assert.equal(v('2+3×4='), '20'); // da esquerda para a direita
assert.equal(v('2+3×'), '5');
assert.equal(conta('2+3×').linha, '5×');
assert.equal(conta('12+').linha, '12+');
assert.equal(conta('12+3=').linha, '');
assert.equal(v('8+×2='), '16');
assert.equal(v('0,1+0,2='), '0,3');
assert.equal(v('1÷3='), '0,333333333');
assert.equal(v('2÷3='), '0,666666667');
assert.equal(v('1÷3×3='), '1');
assert.equal(v('10÷4='), '2,5');
assert.equal(v('5÷0='), 'Erro');
assert.equal(v('5÷0=3'), '3');
assert.equal(v('5÷0=+'), 'Erro');
assert.equal(v('5÷0=C'), '0');
assert.equal(v(',5'), '0,5');
assert.equal(v('1,,2'), '1,2');
assert.equal(v('123⌫'), '12');
assert.equal(v('1⌫⌫'), '0');
assert.equal(v('1,⌫'), '1');
assert.equal(v('007'), '7');
assert.equal(v('12345678901'), '1234567890');
assert.equal(v('1,234567891'), '1,234567891');
// Porcentagem
assert.equal(v('50%'), '0,5');
assert.equal(v('200+10%='), '220');
assert.equal(v('200−10%='), '180');
assert.equal(v('200×10%='), '20');
assert.equal(v('200+10%×2='), '440');
// Raiz quadrada
assert.equal(v('16√'), '4');
assert.equal(v('144√'), '12');
assert.equal(v('2√'), '1,414213562');
assert.equal(v('9+16√='), '13');
assert.equal(v('9+16√×2='), '26');
assert.equal(v('16√+9='), '13');
assert.equal(v('16√5'), '5');
assert.equal(v('0√'), '0');
assert.equal(v('2,25√'), '1,5');
assert.equal(v('0,0001√'), '0,01');
assert.equal(v('1234567890√'), '35136,41829');
assert.equal(v('0−5=√'), 'Erro');
// Resultado e depois
assert.equal(v('2+2=5'), '5');
assert.equal(v('2+2=+1='), '5');
assert.equal(v('2+2=⌫'), '4');
// Números grandes e pequenos
assert.equal(v('9999999999×9999999999='), '1e20');
assert.equal(v('123456×1000000='), '1,2346e11');
assert.equal(v('9999999999+1='), '1e10');
assert.equal(v('9999999999='), '9999999999');
assert.equal(v('0−9999999999='), '-9999999999');
assert.equal(v('1÷1000000000='), '0,000000001');
assert.equal(v('1÷1000000000÷10='), '1e-10');
assert.equal(v('1÷1000000000÷8='), '1,25e-10');
assert.equal(v('2÷30000000='), '0,000000067');
assert.equal(v('0,000000004÷10='), '4e-10');
assert.equal(v('1−0,00000004%='), '1'); // 0,9999999996 arredonda para 1
assert.equal(v('0,1−0,00000004%='), '0,1');
assert.equal(v('0,000000001'), '0,000000001');
assert.equal(v('0−1,5='), '-1,5');
assert.equal(v('0−0,001='), '-0,001');
assert.equal(v('99999×'.repeat(60) + '99999='), '9,9939e304');
assert.equal(v('99999×'.repeat(61) + '99999='), 'Erro'); // passa do maior número
// Nada passa de 12 casas
for (const s of ['0−1÷3=', '0−9999999999,5=', '0−1÷7000000000=', '0−123456×1000000=', '7÷9=', '0−1÷1000000000=']) {
  const t = v(s);
  assert.ok(t.length <= 12, `${s} mostra ${t} (${t.length} casas)`);
}

// Sair: pelo botão e pelo gesto de voltar.
{
  const b = banda();
  b.sair();
  b.voltar();
  assert.equal(b.saiu, 2);
}

// ---- 4. Interpretador só de inteiros (caso a banda não tenha decimais) ----

const soInteiros = fonte.replace(/var COM_DECIMAIS = [^;]+;/, 'var COM_DECIMAIS = false;');
assert.notEqual(soInteiros, fonte);
assert.equal(v('2+3×4=', soInteiros), '20');
assert.equal(v('8÷2=', soInteiros), '4');
assert.equal(v('7÷2=', soInteiros), 'Erro');
assert.equal(v('1,5', soInteiros), '15'); // vírgula desligada
assert.equal(v('144√', soInteiros), '12');
assert.equal(v('2√', soInteiros), 'Erro');
assert.equal(v('200+10%=', soInteiros), '220');
assert.equal(v('5%', soInteiros), 'Erro');

// ---- 4b. Interpretador com números de 32 bits: só 6 algarismos nos resultados ----

const de32bits = fonte.replace(/var ALGARISMOS = [^;]+;/, 'var ALGARISMOS = 6;');
assert.notEqual(de32bits, fonte);
assert.equal(v('1÷3=', de32bits), '0,33333');
assert.equal(v('2÷3=', de32bits), '0,66667');
assert.equal(v('2√', de32bits), '1,41421');
assert.equal(v('999999+1=', de32bits), '1e6');
assert.equal(v('123456+1=', de32bits), '123457');
assert.equal(v('1÷100000=', de32bits), '0,00001');
assert.equal(v('1÷1000000=', de32bits), '1e-6');
assert.equal(v('1234567', de32bits), '1234567'); // o que se digita aparece inteiro

// ---- 5. Tudo dentro da tela, e as teclas na ordem certa ----

{
  const b = banda();
  const teclas = b.objs.filter((o) => o.tipo === 'img' && o.arquivo.startsWith('k_')).map((o) => o.arquivo.slice(2, -4));
  assert.deepEqual(teclas, ['c', 'r', 'p', 'd', '7', '8', '9', 'x', '4', '5', '6', 's', '1', '2', '3', 'a', 'b', '0', 'v', 'i']);
  for (const o of b.objs.filter((o) => o.tipo === 'btn')) {
    assert.ok(o.x >= 0 && o.y >= 0 && o.x + o.l <= 192 && o.y + o.a <= 490, `botão fora da tela em ${o.x},${o.y}`);
  }
}

// ---- 6. Cenas para a prévia ----

const saida = process.argv[2];
if (saida) {
  const cena = (seq) => conta(seq).objs.map((o, i) => ({ ...o, id: i + 1 }));
  writeFileSync(saida, JSON.stringify({
    inicio: cena(''),
    conta: cena('1234,5+'),
    resultado: cena('9+16√×2='),
    raiz: cena('2√'),
    erro: cena('5÷0='),
  }));
}

console.log('calculadora da face: todas as conferências passaram');
