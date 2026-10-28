// Calculadora de juros: lógica pura, sem nenhuma API da banda (testada com node em test/).
//
// Juros simples:   montante = valor × (1 + taxa × parcelas)
// Juros compostos: montante = valor × (1 + taxa) ^ parcelas
// Em ambos: juros = montante − valor e parcela = montante ÷ parcelas (parcelas iguais).

// Campos digitados no teclado. "inteiros" limita os dígitos antes da vírgula.
export var CAMPOS = [
  { id: 'valor', titulo: 'Valor', dica: 'em reais', decimais: 2, inteiros: 9 },
  { id: 'parcelas', titulo: 'Parcelas (vezes)', dica: 'de 1 a 600', decimais: 0, inteiros: 3 },
  { id: 'taxa', titulo: 'Taxa de juros ao mês', dica: 'em %, ex.: 2,5', decimais: 2, inteiros: 3 }
];

export var MAX_PARCELAS = 600;
export var MAX_TAXA = 100;

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
  if (campoId === 'valor' && numero <= 0) return 'O valor precisa ser maior que zero';
  if (campoId === 'parcelas') {
    if (numero < 1) return 'Pelo menos 1 parcela';
    if (numero > MAX_PARCELAS) return 'No máximo ' + MAX_PARCELAS + ' parcelas';
  }
  if (campoId === 'taxa' && numero > MAX_TAXA) return 'No máximo ' + MAX_TAXA + '% ao mês';
  return '';
}

// tipo: 'simples' ou 'composto'. taxa em porcentagem ao mês (2 = 2%).
export function calcular(valor, parcelas, taxa, tipo) {
  var i = taxa / 100;
  var montante = tipo === 'simples' ? valor * (1 + i * parcelas) : valor * Math.pow(1 + i, parcelas);
  return {
    montante: montante,
    juros: montante - valor,
    parcela: montante / parcelas
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

// Número para "R$ 1.234,56". Valores absurdos (juros compostos por muitos meses) viram aviso.
export function reais(x) {
  if (!isFinite(x) || Math.abs(x) >= 1e15) return 'grande demais';
  var centavos = Math.round(Math.abs(x) * 100);
  var inteiro = String(Math.floor(centavos / 100));
  var resto = String(centavos % 100);
  if (resto.length < 2) resto = '0' + resto;
  return (x < 0 ? '-' : '') + 'R$ ' + milhares(inteiro) + ',' + resto;
}

// Texto enquanto digita, com separador de milhares: "1500,5" → "1.500,5".
export function mostrarDigitado(texto) {
  if (texto === '') return '0';
  var virgula = texto.indexOf(',');
  if (virgula < 0) return milhares(texto);
  return milhares(texto.slice(0, virgula)) + texto.slice(virgula);
}

// Taxa para exibir: 2.5 → "2,5%".
export function porcento(taxa) {
  return String(Math.round(taxa * 100) / 100).replace('.', ',') + '%';
}

// Valor já confirmado, para mostrar na barra da tela principal.
export function mostrarCampo(campoId, numero) {
  if (campoId === 'valor') return reais(numero);
  if (campoId === 'parcelas') return numero + 'x';
  return porcento(numero);
}

// Texto enquanto digita, com a unidade do campo.
export function mostrarRascunho(campoId, texto) {
  var t = mostrarDigitado(texto);
  if (campoId === 'valor') return 'R$ ' + t;
  if (campoId === 'parcelas') return t + 'x';
  return t + '%';
}
