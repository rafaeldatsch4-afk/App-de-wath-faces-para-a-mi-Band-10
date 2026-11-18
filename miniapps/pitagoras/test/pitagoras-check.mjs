// Conferência da lógica do teorema de Pitágoras (node puro, sem dependências): npm test
import assert from 'node:assert/strict';
import { LADOS, calcular, descrever, digitar, mostrarLado, mostrarRascunho, paraNumero, unidades, validar } from '../src/common/pitagoras.js';

const lado = LADOS[0];

// Resolve e devolve os textos prontos para a tela (ou o erro).
function resolve(a, b, c) {
  const r = calcular([a, b, c]);
  return r.erro ? r : { ...descrever(r), exato: r.exato };
}

// Hipotenusa: o clássico 3, 4, 5
{
  const r = resolve('3', '4', 'X');
  assert.deepEqual(r, {
    titulo: 'Hipotenusa (c)', formula: 'c² = a² + b²', dado1: 'a = 3', dado2: 'b = 4',
    quadrado: 'c² = 25', legenda: 'Resultado', resposta: 'c = 5', exato: true,
  });
}
assert.equal(resolve('5', '12', 'X').resposta, 'c = 13');
assert.equal(resolve('8', '15', 'X').resposta, 'c = 17');
assert.equal(resolve('0,3', '0,4', 'X').resposta, 'c = 0,5'); // 0,09 + 0,16 = 0,25, sem erro de ponto flutuante
assert.equal(resolve('0,3', '0,4', 'X').quadrado, 'c² = 0,25');
assert.equal(resolve('1,5', '2', 'X').resposta, 'c = 2,5');

// Hipotenusa aproximada (raiz que não é exata)
{
  const r = resolve('1', '1', 'X');
  assert.equal(r.resposta, 'c = 1,4142');
  assert.equal(r.legenda, 'Aproximado');
  assert.equal(r.quadrado, 'c² = 2');
  assert.equal(resolve('2', '3', 'X').resposta, 'c = 3,6056'); // √13
  assert.equal(resolve('1', '2', 'X').resposta, 'c = 2,2361'); // √5
  assert.equal(resolve('0,01', '0,01', 'X').resposta, 'c = 0,0141'); // √0,0002
}

// Cateto
{
  const r = resolve('X', '3', '5');
  assert.deepEqual(r, {
    titulo: 'Cateto (a)', formula: 'a² = c² - b²', dado1: 'b = 3', dado2: 'c = 5',
    quadrado: 'a² = 16', legenda: 'Resultado', resposta: 'a = 4', exato: true,
  });
}
{
  const r = resolve('5', 'X', '13');
  assert.equal(r.titulo, 'Cateto (b)');
  assert.equal(r.formula, 'b² = c² - a²');
  assert.equal(r.dado1, 'a = 5');
  assert.equal(r.dado2, 'c = 13');
  assert.equal(r.resposta, 'b = 12');
}
assert.equal(resolve('X', '1', '2').resposta, 'a = 1,7321'); // √3
assert.equal(resolve('X', '1', '2').legenda, 'Aproximado');
assert.equal(resolve('X', '0,5', '1,3').resposta, 'a = 1,2'); // 1,69 - 0,25 = 1,44

// Limites: 9999,99 nos três tamanhos de conta
assert.equal(resolve('9999,99', '9999,99', 'X').resposta, 'c = 14142,1215');
assert.equal(resolve('9999,99', '9999,99', 'X').quadrado, 'c² = 199999600,0002');
assert.equal(resolve('X', '0,01', '9999,99').legenda, 'Aproximado');
assert.equal(resolve('X', '0,01', '0,02').resposta, 'a = 0,0173'); // √0,0003

// Erros
assert.deepEqual(calcular(['3', '5', 'X']), calcular(['3', '5', 'X'])); // não muda o estado
assert.equal(calcular(['X', '5', '5']).erro, 'A hipotenusa deve|ser o maior lado');
assert.equal(calcular(['X', '5', '3']).erro, 'A hipotenusa deve|ser o maior lado');
assert.equal(calcular(['4', 'X', '4']).erro, 'A hipotenusa deve|ser o maior lado');
assert.equal(calcular(['3', '4', '5']).erro, 'Marque um X');
assert.equal(calcular(['3', '', 'X']).erro, 'Preencha tudo');
assert.equal(calcular(['', '', 'X']).erro, 'Preencha tudo');
assert.equal(calcular(['X', 'X', '5']).erro, 'Só um X');
assert.equal(calcular(['0', '4', 'X']).erro, 'Preencha tudo');
// Todo erro cabe em até duas linhas curtas (≤ 19 caracteres: 19 × 16 × 0,62 = 188 px)
for (const e of ['A hipotenusa deve|ser o maior lado', 'Marque um X', 'Preencha tudo', 'Só um X']) {
  for (const linha of e.split('|')) assert.ok(linha.length <= 19, linha);
}

// Unidades
assert.equal(unidades(250000, 4), '25');
assert.equal(unidades(14142, 4), '1,4142');
assert.equal(unidades(100, 4), '0,01');
assert.equal(unidades(1, 4), '0,0001');
assert.equal(unidades(0, 4), '0');
assert.equal(unidades(250, 2), '2,5');
assert.equal(unidades(300, 2), '3');
assert.equal(unidades(5, 2), '0,05');

// Digitação
function digita(campo, teclas, inicial = '') {
  let t = inicial;
  for (const k of teclas) t = digitar(t, k, campo);
  return t;
}
const sequencia = (s) => s.split('');
assert.equal(digita(lado, sequencia('345')), '345');
assert.equal(digita(lado, sequencia('1234567')), '1234'); // 4 dígitos antes da vírgula
assert.equal(digita(lado, sequencia('12,345')), '12,34'); // 2 casas
assert.equal(digita(lado, sequencia(',5')), '0,5');
assert.equal(digita(lado, sequencia('1,,5')), '1,5');
assert.equal(digita(lado, sequencia('007')), '7');
assert.equal(digita(lado, ['DEL']), '');
assert.equal(digita(lado, ['1', '2', 'DEL']), '1');
// O X vale o texto inteiro
assert.equal(digita(lado, ['X']), 'X');
assert.equal(digita(lado, sequencia('12X')), 'X'); // X no meio de um número troca tudo
assert.equal(digita(lado, ['X', '5']), '5'); // um dígito depois do X recomeça
assert.equal(digita(lado, ['X', ',']), '0,');
assert.equal(digita(lado, ['X', 'DEL']), '');
assert.equal(digita(lado, ['X', 'X']), 'X');
assert.equal(digitar('5', 'q', lado), '5');
assert.equal(digitar('5', '', lado), '5');

// Números e validação
assert.equal(paraNumero('2,5'), 2.5);
assert.ok(isNaN(paraNumero('')));
assert.ok(isNaN(paraNumero('X')));
assert.ok(isNaN(paraNumero(',')));
assert.equal(validar('X'), '');
assert.equal(validar(''), 'Digite um valor');
assert.equal(validar(','), 'Digite um valor');
assert.equal(validar('0'), 'Maior que zero');
assert.equal(validar('0,'), 'Maior que zero');
assert.equal(validar('0,01'), '');
assert.equal(validar('3'), '');

// Textos da tela
assert.equal(mostrarLado('X'), 'X');
assert.equal(mostrarLado('2,50'), '2,5');
assert.equal(mostrarLado('3'), '3');
assert.equal(mostrarLado('0,05'), '0,05');
assert.equal(mostrarRascunho(''), '0');
assert.equal(mostrarRascunho('X'), 'X');
assert.equal(mostrarRascunho('2,5'), '2,5');

// Conferência cruzada com a conta em ponto flutuante: 20 mil casos aleatórios com 2 casas
{
  let semente = 12345;
  const aleatorio = () => (semente = (semente * 1103515245 + 12345) % 2147483648) / 2147483648;
  for (let i = 0; i < 20000; i++) {
    const a = Math.round(aleatorio() * 999999 + 1) / 100;
    const b = Math.round(aleatorio() * 999999 + 1) / 100;
    const r = calcular([String(a).replace('.', ','), String(b).replace('.', ','), 'X']);
    const esperado = Math.sqrt(a * a + b * b);
    assert.ok(Math.abs(r.valor / 10000 - esperado) < 0.00006, `${a}, ${b}: ${r.valor / 10000} ≠ ${esperado}`);
    // O inverso: com a hipotenusa calculada (4 casas), recuperar b a menos da folga do arredondamento
    const c = Math.round(esperado * 100) / 100;
    if (c > a) {
      const inv = calcular([String(a).replace('.', ','), 'X', String(c).replace('.', ',')]);
      assert.ok(!inv.erro, `${a}, ${c}`);
      assert.ok(Math.abs(inv.valor / 10000 - Math.sqrt(c * c - a * a)) < 0.00006);
    }
  }
}

console.log('pitágoras: todas as conferências passaram');
