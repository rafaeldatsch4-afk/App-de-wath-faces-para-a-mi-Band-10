// Conferência da lógica da calculadora (node puro, sem dependências): npm test
import assert from 'node:assert/strict';
import { CAMPOS, calcular, digitar, mostrarDigitado, paraNumero, porcento, reais, validar } from '../src/common/juros.js';

const [valor, parcelas, taxa] = CAMPOS;
const digita = (campo, teclas) => teclas.split('').reduce((t, k) => digitar(t, k === '<' ? 'DEL' : k, campo), '');

// Digitação
assert.equal(digita(valor, '1500,5'), '1500,5');
assert.equal(digita(valor, '1500,555'), '1500,55'); // no máximo 2 casas
assert.equal(digita(valor, '0012'), '12'); // sem zeros à esquerda
assert.equal(digita(valor, ',5'), '0,5');
assert.equal(digita(valor, '1,,2'), '1,2'); // uma vírgula só
assert.equal(digita(valor, '1234567890'), '123456789'); // até 9 dígitos inteiros
assert.equal(digita(valor, '12<3'), '13');
assert.equal(digita(parcelas, '1,2'), '12'); // parcelas não têm vírgula
assert.equal(digita(taxa, '2,75'), '2,75');
assert.equal(mostrarDigitado('1234567,8'), '1.234.567,8');
assert.equal(mostrarDigitado(''), '0');
assert.equal(paraNumero('2,5'), 2.5);
assert.ok(isNaN(paraNumero('')));

// Validação
assert.equal(validar('valor', 0), 'O valor precisa ser maior que zero');
assert.equal(validar('valor', 10), '');
assert.equal(validar('parcelas', 0), 'Pelo menos 1 parcela');
assert.equal(validar('parcelas', 601), 'No máximo 600 parcelas');
assert.equal(validar('taxa', 0), ''); // sem juros é permitido
assert.equal(validar('taxa', 150), 'No máximo 100% ao mês');
assert.equal(validar('taxa', NaN), 'Digite um valor');

// Juros simples: 1000 × (1 + 0,02 × 12) = 1240
let r = calcular(1000, 12, 2, 'simples');
assert.equal(reais(r.montante), 'R$ 1.240,00');
assert.equal(reais(r.juros), 'R$ 240,00');
assert.equal(reais(r.parcela), 'R$ 103,33');

// Juros compostos: 1000 × 1,02^12 = 1268,2417…
r = calcular(1000, 12, 2, 'composto');
assert.equal(reais(r.montante), 'R$ 1.268,24');
assert.equal(reais(r.juros), 'R$ 268,24');
assert.equal(reais(r.parcela), 'R$ 105,69');

// Uma parcela: simples e compostos dão o mesmo
assert.equal(reais(calcular(500, 1, 10, 'simples').montante), reais(calcular(500, 1, 10, 'composto').montante));

// Taxa zero: só divide o valor
r = calcular(1200, 12, 0, 'composto');
assert.equal(reais(r.juros), 'R$ 0,00');
assert.equal(reais(r.parcela), 'R$ 100,00');

// Formatação
assert.equal(reais(1234567.891), 'R$ 1.234.567,89');
assert.equal(reais(0.005), 'R$ 0,01');
assert.equal(reais(calcular(999999999, 600, 100, 'composto').montante), 'grande demais');
assert.equal(porcento(2.5), '2,5%');
assert.equal(porcento(2), '2%');

console.log('juros: todas as conferências passaram');
