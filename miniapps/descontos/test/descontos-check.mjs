// Conferência da lógica dos descontos (node puro, sem dependências): npm test
import assert from 'node:assert/strict';
import { CAMPOS, calcular, digitar, mostrarDigitado, mostrarRascunho, paraNumero, porcento, reais, validar } from '../src/common/descontos.js';

const preco = CAMPOS[0];
const desc = CAMPOS[1];

// O exemplo do pedido: um tênis de R$ 200 com 20% de desconto
{
  const r = calcular(200, 20);
  assert.equal(r.desconto, 40);
  assert.equal(r.final, 160);
  assert.equal(reais(r.desconto), 'R$ 40,00');
  assert.equal(reais(r.final), 'R$ 160,00');
}

// Outras contas
assert.deepEqual(calcular(100, 10), { desconto: 10, final: 90 });
assert.deepEqual(calcular(59.9, 50), { desconto: 29.95, final: 29.95 });
assert.deepEqual(calcular(199.9, 15), { desconto: 29.99, final: 169.91 }); // 29,985 → 29,99
assert.deepEqual(calcular(0.01, 50), { desconto: 0.01, final: 0 }); // meio centavo sobe
assert.deepEqual(calcular(1234.56, 12.5), { desconto: 154.32, final: 1080.24 });
assert.deepEqual(calcular(200, 0), { desconto: 0, final: 200 });
assert.deepEqual(calcular(200, 100), { desconto: 200, final: 0 });
assert.deepEqual(calcular(999999999.99, 33.33), { desconto: 333300000, final: 666699999.99 });
// Preço digitado com 3 casas por engano nunca chega aqui (o teclado limita a 2), mas 0,1 + 0,2 não pode sujar
assert.equal(reais(calcular(0.1 + 0.2, 10).final), 'R$ 0,27');

// Formatação em reais
assert.equal(reais(0), 'R$ 0,00');
assert.equal(reais(1234567.8), 'R$ 1.234.567,80');
assert.equal(reais(1.005), 'R$ 1,01');
assert.equal(porcento(20), '20%');
assert.equal(porcento(12.5), '12,5%');
assert.equal(porcento(33.333), '33,33%');
assert.equal(porcento(100), '100%');

// Digitação
function digita(campo, teclas) {
  let t = '';
  for (const k of teclas === 'DEL' ? ['DEL'] : teclas.split('')) t = digitar(t, k, campo);
  return t;
}
assert.equal(digita(preco, '200'), '200');
assert.equal(digita(preco, '1999,9'), '1999,9');
assert.equal(digita(preco, '1,999'), '1,99'); // 2 casas
assert.equal(digita(preco, ',5'), '0,5');
assert.equal(digita(preco, '1,,5'), '1,5');
assert.equal(digita(preco, '007'), '7');
assert.equal(digita(preco, '1234567890'), '123456789'); // 9 dígitos
assert.equal(digita(desc, '1000'), '100'); // 3 dígitos
assert.equal(digitar('123', 'DEL', preco), '12');
assert.equal(digitar('', 'DEL', preco), '');
assert.equal(digitar('5', 'x', preco), '5');
assert.equal(digitar('5', '', preco), '5');

// Texto enquanto digita
assert.equal(mostrarDigitado(''), '0');
assert.equal(mostrarDigitado('1500,5'), '1.500,5');
assert.equal(mostrarRascunho('preco', '200'), 'R$ 200');
assert.equal(mostrarRascunho('preco', ''), 'R$ 0');
assert.equal(mostrarRascunho('desconto', '20'), '20%');

// Números
assert.equal(paraNumero('200'), 200);
assert.equal(paraNumero('12,5'), 12.5);
assert.ok(isNaN(paraNumero('')));
assert.ok(isNaN(paraNumero(',')));

// Validação
assert.equal(validar('preco', NaN), 'Digite um valor');
assert.equal(validar('preco', 0), 'O preço precisa ser maior que zero');
assert.equal(validar('preco', 0.01), '');
assert.equal(validar('desconto', NaN), 'Digite um valor');
assert.equal(validar('desconto', 0), '');
assert.equal(validar('desconto', 100), '');
assert.equal(validar('desconto', 100.01), 'No máximo 100%');
assert.equal(validar('desconto', 150), 'No máximo 100%');

console.log('descontos: todas as conferências passaram');
