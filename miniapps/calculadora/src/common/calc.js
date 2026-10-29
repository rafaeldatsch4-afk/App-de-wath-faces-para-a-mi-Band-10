// Calculadora de bolso: lógica pura, sem nenhuma API da banda (testada com node em test/).
//
// Funciona como as calculadoras simples: cada operação é feita na hora, da esquerda para a
// direita (2 + 3 × 4 = 20). Números com vírgula, como no Brasil.

var MAX_DIGITOS = 10;

export function estadoInicial() {
  // operando: o visor tem um número calculado (√ ou %) que ainda entra na operação pendente.
  return { visor: '0', acumulado: null, operador: null, novo: true, erro: false, expressao: '', operando: false };
}

// "1,5" → 1.5
export function paraNumero(visor) {
  return parseFloat(visor.replace(',', '.'));
}

// 1.5 → "1,5". Arredonda para 10 algarismos (0,1 + 0,2 mostra 0,3) e usa notação científica
// quando o número não cabe.
export function formatar(x) {
  if (!isFinite(x)) return 'Erro';
  var r = parseFloat(x.toPrecision(MAX_DIGITOS));
  if (r === 0) return '0'; // também evita "-0"
  var s = String(r);
  if (s.replace(/[^0-9]/g, '').length > MAX_DIGITOS + 1 || s.indexOf('e') >= 0) {
    s = r.toExponential(4).replace(/\.?0+e/, 'e').replace('e+', 'e');
  }
  return s.replace('.', ',');
}

function aplicar(a, operador, b) {
  if (operador === '+') return a + b;
  if (operador === '−') return a - b;
  if (operador === '×') return a * b;
  if (operador === '÷') return b === 0 ? NaN : a / b;
  return b;
}

function digitos(s) {
  return s.replace(/[^0-9]/g, '').length;
}

function erro() {
  var e = estadoInicial();
  e.visor = 'Erro';
  e.erro = true;
  return e;
}

function copiar(e) {
  return { visor: e.visor, acumulado: e.acumulado, operador: e.operador, novo: e.novo, erro: e.erro, expressao: e.expressao, operando: e.operando };
}

// Aplica uma tecla e devolve o estado novo. Teclas: '0'–'9', ',', 'C', 'DEL', '±', '%', '√',
// '+', '−', '×', '÷', '='.
export function tecla(estado, k) {
  if (k === 'C') return estadoInicial();
  var e = copiar(estado);
  if (e.erro) {
    // Depois de um erro, só um número ou C recomeçam.
    if ((k >= '0' && k <= '9' && k.length === 1) || k === ',') e = estadoInicial();
    else return e;
  }

  if (k.length === 1 && k >= '0' && k <= '9') {
    if (e.novo || e.visor === '0') {
      e.visor = k;
    } else if (e.visor === '-0') {
      e.visor = '-' + k;
    } else if (digitos(e.visor) < MAX_DIGITOS) {
      e.visor += k;
    }
    e.novo = false;
    e.operando = false;
    return e;
  }

  if (k === ',') {
    if (e.novo) {
      e.visor = '0,';
      e.novo = false;
      e.operando = false;
    } else if (e.visor.indexOf(',') < 0) {
      e.visor += ',';
    }
    return e;
  }

  if (k === 'DEL') {
    if (e.novo) return e;
    e.visor = e.visor.slice(0, -1);
    if (e.visor === '' || e.visor === '-') e.visor = '0';
    return e;
  }

  if (k === '±') {
    if (e.visor === '0') return e;
    e.visor = e.visor.charAt(0) === '-' ? e.visor.slice(1) : '-' + e.visor;
    return e;
  }

  if (k === '√') {
    // Raiz quadrada do número no visor, na hora (como nas calculadoras de bolso): 9 + √16 = 13.
    var n = paraNumero(e.visor);
    if (n < 0) return erro();
    var antes = e.visor;
    e.visor = formatar(Math.sqrt(n));
    e.expressao = (e.operador !== null ? formatar(e.acumulado) + ' ' + e.operador + ' ' : '') + '√' + antes;
    e.novo = true;
    e.operando = e.operador !== null;
    return e;
  }

  if (k === '%') {
    var b = paraNumero(e.visor);
    // Como no celular: 200 + 10% = 220 (10% de 200); sozinho, 50% = 0,5.
    var p = e.acumulado !== null && (e.operador === '+' || e.operador === '−') ? (e.acumulado * b) / 100 : b / 100;
    e.visor = formatar(p);
    e.novo = true;
    e.operando = e.operador !== null;
    return e;
  }

  if (k === '+' || k === '−' || k === '×' || k === '÷') {
    if (e.operador !== null && (!e.novo || e.operando)) {
      var r = aplicar(e.acumulado, e.operador, paraNumero(e.visor));
      if (!isFinite(r)) return erro();
      e.visor = formatar(r);
    }
    e.acumulado = paraNumero(e.visor);
    e.operador = k;
    e.novo = true;
    e.operando = false;
    e.expressao = e.visor + ' ' + k;
    return e;
  }

  if (k === '=') {
    if (e.operador === null) return e;
    var segundo = e.visor;
    var resultado = aplicar(e.acumulado, e.operador, paraNumero(segundo));
    if (!isFinite(resultado)) return erro();
    e.expressao = formatar(e.acumulado) + ' ' + e.operador + ' ' + segundo + ' =';
    e.visor = formatar(resultado);
    e.acumulado = null;
    e.operador = null;
    e.novo = true;
    e.operando = false;
    return e;
  }

  return e;
}
