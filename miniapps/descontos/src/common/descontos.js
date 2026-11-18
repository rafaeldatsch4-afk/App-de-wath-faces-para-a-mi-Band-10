// Calculadora de descontos: lógica pura, sem nenhuma API da banda (testada com node em test/).
//
// desconto = preço × porcentagem ÷ 100   e   preço final = preço − desconto
// As contas são feitas em centavos, arredondando o desconto para o centavo mais próximo.

// Campos digitados no teclado, na ordem em que o app pergunta. "inteiros" limita os dígitos antes da vírgula.
export var CAMPOS = [
  { id: 'preco', titulo: 'Preço original', dica: '1 de 2 · em reais', decimais: 2, inteiros: 9 },
  { id: 'desconto', titulo: 'Desconto', dica: '2 de 2 · em %, ex.: 20', decimais: 2, inteiros: 3 }
];

export var MAX_DESCONTO = 100;

// Aplica uma tecla ('0'–'9', ',' ou 'DEL') ao texto digitado e devolve o texto novo.
export function digitar(texto, tecla, campo) {
  if (tecla === 'DEL') return texto.slice(0, -1);
  var virgula = texto.indexOf(',');
  if (tecla === ',') {
    if (campo.decimais === 0 || virgula >= 0) return texto;
    return (texto === '' ? '0' : texto) + ',';
  }
  if (tecla.length !== 1 || tecla < '0' || tecla > '9') return texto;
  if (virgula >= 0) {
    if (texto.length - virgula - 1 >= campo.decimais) return texto;
    return texto + tecla;
  }
  if (texto === '0') return tecla; // sem zeros à esquerda
  if (texto.length >= campo.inteiros) return texto;
  return texto + tecla;
}

// Texto digitado ("1500,5") para número (1500.5). Vazio vira NaN.
export function paraNumero(texto) {
  if (texto === '' || texto === ',') return NaN;
  return parseFloat(texto.replace(',', '.'));
}

// Devolve a mensagem de erro do campo, ou '' se o valor serve.
export function validar(campoId, numero) {
  if (isNaN(numero)) return 'Digite um valor';
  if (campoId === 'preco' && numero <= 0) return 'Maior que zero';
  if (campoId === 'desconto' && numero > MAX_DESCONTO) return 'No máximo ' + MAX_DESCONTO + '%';
  return '';
}

// preço em reais, desconto em porcentagem (20 = 20%). Devolve o desconto e o preço final em reais.
export function calcular(preco, desconto) {
  var centavos = Math.round(preco * 100);
  // A folga evita que 2998,4999… (meio centavo guardado com erro) arredonde para baixo.
  var descontoCentavos = Math.round((centavos * desconto) / 100 + 1e-7);
  return {
    desconto: descontoCentavos / 100,
    final: (centavos - descontoCentavos) / 100
  };
}

// Separa os milhares com ponto: "1234567" → "1.234.567".
function milhares(inteiro) {
  var s = '';
  for (var i = 0; i < inteiro.length; i++) {
    if (i > 0 && (inteiro.length - i) % 3 === 0) s += '.';
    s += inteiro[i];
  }
  return s;
}

// Número para "R$ 1.234,56".
export function reais(x) {
  var centavos = Math.round(Math.abs(x) * 100 + 1e-7);
  var inteiro = String(Math.floor(centavos / 100));
  var resto = String(centavos % 100);
  if (resto.length < 2) resto = '0' + resto;
  return 'R$ ' + milhares(inteiro) + ',' + resto;
}

// Texto enquanto digita, com separador de milhares: "1500,5" → "1.500,5".
export function mostrarDigitado(texto) {
  if (texto === '') return '0';
  var virgula = texto.indexOf(',');
  if (virgula < 0) return milhares(texto);
  return milhares(texto.slice(0, virgula)) + texto.slice(virgula);
}

// Porcentagem para exibir: 20 → "20%", 12.5 → "12,5%".
export function porcento(valor) {
  return String(Math.round(valor * 100) / 100).replace('.', ',') + '%';
}

// Texto enquanto digita, com a unidade do campo.
export function mostrarRascunho(campoId, texto) {
  var t = mostrarDigitado(texto);
  return campoId === 'preco' ? 'R$ ' + t : t + '%';
}
