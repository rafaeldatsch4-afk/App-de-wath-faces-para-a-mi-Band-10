// Conferência da lógica da calculadora (node puro, sem dependências): npm test
import assert from 'node:assert/strict';
import { estadoInicial, formatar, tecla } from '../src/common/calc.js';

// Digita uma sequência: cada item é uma tecla ('DEL', 'C' etc.) ou um caractere.
function conta(...teclas) {
  let e = estadoInicial();
  for (const t of teclas) {
    const lista = ['DEL', 'C'].includes(t) ? [t] : t.split('');
    for (const k of lista) e = tecla(e, k);
  }
  return e;
}

// Básico
assert.equal(conta('2+3=').visor, '5');
assert.equal(conta('7−10=').visor, '-3');
assert.equal(conta('6×7=').visor, '42');
assert.equal(conta('1÷4=').visor, '0,25');
assert.equal(conta('1,5+1,5=').visor, '3');

// Da esquerda para a direita, como a calculadora de bolso
assert.equal(conta('2+3×4=').visor, '20');
assert.equal(conta('2+3×').visor, '5'); // o resultado parcial aparece ao trocar de operação
assert.equal(conta('2+3×').expressao, '5 ×');

// Trocar de operação sem digitar outro número
assert.equal(conta('8+×2=').visor, '16');

// Arredondamento: 0,1 + 0,2
assert.equal(conta('0,1+0,2=').visor, '0,3');

// Divisão por zero
assert.equal(conta('5÷0=').visor, 'Erro');
assert.equal(conta('5÷0=', '3').visor, '3'); // um número recomeça
assert.equal(conta('5÷0=', '+').visor, 'Erro'); // operação depois do erro não faz nada

// Vírgula, apagar, sinal
assert.equal(conta(',5').visor, '0,5');
assert.equal(conta('1,,2').visor, '1,2');
assert.equal(conta('123', 'DEL').visor, '12');
assert.equal(conta('1', 'DEL', 'DEL').visor, '0');
assert.equal(conta('5±').visor, '-5');
assert.equal(conta('5±±').visor, '5');
assert.equal(conta('5±', 'DEL').visor, '0');
assert.equal(conta('0±').visor, '0');
assert.equal(conta('007').visor, '7');
assert.equal(conta('12345678901').visor, '1234567890'); // até 10 dígitos

// Porcentagem
assert.equal(conta('50%').visor, '0,5');
assert.equal(conta('200+10%=').visor, '220');
assert.equal(conta('200−10%=').visor, '180');
assert.equal(conta('200×10%=').visor, '20');

// Raiz quadrada
assert.equal(conta('16√').visor, '4');
assert.equal(conta('16√').expressao, '√16');
assert.equal(conta('2√').visor, '1,414213562');
assert.equal(conta('9+16√').expressao, '9 + √16');
assert.equal(conta('9+16√=').visor, '13');
assert.equal(conta('16√', '5').visor, '5'); // o resultado da raiz é substituído ao digitar
assert.equal(conta('5', '±', '√').visor, 'Erro'); // raiz de negativo
assert.equal(conta('0√').visor, '0');
assert.equal(conta('2,25√').visor, '1,5');
// Uma operação depois da raiz ou da % usa a operação pendente (não a descarta)
assert.equal(conta('9+16√×2=').visor, '26'); // (9 + 4) × 2
assert.equal(conta('200+10%×2=').visor, '440'); // (200 + 20) × 2
assert.equal(conta('16√+9=').visor, '13');

// Resultado e depois um número novo
assert.equal(conta('2+2=', '5').visor, '5');
assert.equal(conta('2+2=', '+1=').visor, '5');

// C limpa tudo
assert.deepEqual(conta('9+9', 'C'), estadoInicial());

// Formatação de números grandes e pequenos
assert.equal(formatar(123456789012), '1,2346e11');
assert.equal(formatar(0.000001234), '0,000001234');
assert.equal(formatar(-0), '0');
assert.equal(formatar(Infinity), 'Erro');
assert.equal(conta('9999999999×9999999999=').visor, '1e20');

console.log('calculadora: todas as conferências passaram');
