// Calculadora de bolso para a Mi Band 8, dentro de uma watch face: o botão CALC da face abre este
// script (um "app embutido", como os jogos oficiais da Xiaomi). Teclas:
//
//   C  √  %  ÷
//   7  8  9  ×
//   4  5  6  −
//   1  2  3  +
//   ⌫  0  ,  =
//
// O interpretador da banda não tem documentação. Por isso este arquivo só chama funções que os
// jogos oficiais "谁是学霸" e "小人过桥" já chamam (create_img, create_btn, addEvent, set_img_src,
// set_obj_hidden, set_obj_display, set_obj_pos, exit_game, EventMap, registerEvent) e usa um JS
// simples: sem let/const, sem Math, sem métodos de string ou de array. test/calc-test.mjs confere.

// Posições na tela de 192 × 490 (o build.py lê estes valores para desenhar as imagens).
var TECLA_L = 43;
var TECLA_A = 56;
var TECLA_X0 = 4;
var TECLA_Y0 = 122;
var TECLA_PASSO_X = 47;
var TECLA_PASSO_Y = 60;
var VISOR_CASAS = 12;
var VISOR_L = 14;
var VISOR_A = 28;
var VISOR_X0 = 12;
var VISOR_Y = 62;
var LINHA_CASAS = 16;
var LINHA_L = 8;
var LINHA_A = 14;
var LINHA_X0 = 32;
var LINHA_Y = 42;
var ERRO_L = 60;
var SAIR_X = 68;
var SAIR_Y = 10;
var FUNDO_A = 98;
var FUNDO_PARTES = 5;

var MAX_DIGITOS = 10;

// Teclas na ordem da tela; o nome da imagem é "k_" + código + ".bin".
var TECLAS = ['c', 'r', 'p', 'd', '7', '8', '9', 'x', '4', '5', '6', 's', '1', '2', '3', 'a', 'b', '0', 'v', 'i'];

// Algumas bandas poderiam ter um interpretador só de inteiros: aí a vírgula fica desligada e √, ÷ e
// % só aceitam resultados exatos (senão mostram "Erro"), em vez de mostrar um número errado.
var COM_DECIMAIS = (1 / 4) * 4 == 1 && 1 / 4 != 0;
// Algarismos confiáveis nos resultados: 10 com números de 64 bits; 6 se o interpretador usar 32 bits.
var ALGARISMOS = 1 + 1e-12 != 1 ? MAX_DIGITOS : 6;

// Estado:
// ent: dígitos sendo digitados (números 0–9 e 'v' para a vírgula) ou null se o visor mostra val;
// val: número no visor quando ent é null; acc/op: conta pendente (op: 'a' + 's' − 'x' × 'd' ÷);
// novo: o próximo dígito começa um número novo; operando: o visor tem um √ ou % que ainda entra na
// conta pendente; erro: o visor mostra "Erro".
var ent, val, acc, op, novo, operando, erro;

function limpar() {
  ent = [0];
  val = 0;
  acc = null;
  op = null;
  novo = true;
  operando = false;
  erro = false;
}

function poe(lista, item) {
  lista[lista.length] = item;
}

function finito(x) {
  return x - x == 0; // falso para NaN e ±Infinity
}

// Parte inteira de um número >= 0.
function inteiro(x) {
  return x - (x % 1);
}

function pot10(n) {
  var r = 1;
  for (var i = 0; i < n; i++) r = r * 10;
  return r;
}

function quantosDigitos(lista) {
  var n = 0;
  for (var i = 0; i < lista.length; i++) if (lista[i] != 'v') n++;
  return n;
}

function temVirgula(lista) {
  for (var i = 0; i < lista.length; i++) if (lista[i] == 'v') return true;
  return false;
}

// Valor do número digitado: todos os dígitos como inteiro, dividido uma vez só (1,15 = 115 / 100).
function valorDigitado(lista) {
  var n = 0;
  var casas = 0;
  var depois = false;
  for (var i = 0; i < lista.length; i++) {
    if (lista[i] == 'v') {
      depois = true;
    } else {
      n = n * 10 + lista[i];
      if (depois) casas++;
    }
  }
  return casas > 0 ? n / pot10(casas) : n;
}

function valorAtual() {
  return ent !== null ? valorDigitado(ent) : val;
}

// Dígitos de um inteiro 0 <= n < 10^10, do mais significativo ao menos.
function digitosDe(n) {
  var rev = [];
  do {
    var d = n % 10;
    poe(rev, d);
    n = (n - d) / 10;
  } while (n > 0);
  var r = [];
  for (var i = rev.length - 1; i >= 0; i--) poe(r, rev[i]);
  return r;
}

// Arredonda m (1 <= m < 10) para k algarismos: devolve { d: dígitos, e: 0 ou 1 se "virou" 10 }.
function arredonda(m, k) {
  var n = inteiro(m * pot10(k - 1) + 0.5);
  var sobe = 0;
  if (n >= pot10(k)) {
    n = n / 10;
    sobe = 1;
  }
  return { d: digitosDe(n), e: sobe };
}

// Número → lista de símbolos do visor (0–9, 'v' vírgula, 'm' menos, 'e'), ou null se não é finito.
// Até ALGARISMOS algarismos significativos, vírgula decimal; notação científica quando não cabe (1,2346e11).
function formatar(x) {
  if (!finito(x)) return null;
  var r = [];
  if (x < 0) {
    poe(r, 'm');
    x = -x;
  }
  var i;
  if (x % 1 == 0 && x < pot10(ALGARISMOS)) {
    var ds = digitosDe(x);
    if (ds.length == 1 && ds[0] == 0) return [0]; // também evita "-0"
    for (i = 0; i < ds.length; i++) poe(r, ds[i]);
    return r;
  }
  // Só chega aqui com decimais (número não inteiro ou grande).
  var m = x;
  var e = 0;
  while (m >= 10) {
    m = m / 10;
    e++;
  }
  while (m < 1) {
    m = m * 10;
    e--;
  }
  var a = arredonda(m, ALGARISMOS);
  e = e + a.e;
  var d = a.d;
  if (e >= ALGARISMOS || e < 1 - ALGARISMOS) {
    var c = arredonda(m, 5);
    e = e - a.e + c.e;
    d = c.d;
    var fim = d.length - 1;
    while (fim > 0 && d[fim] == 0) fim--;
    poe(r, d[0]);
    if (fim > 0) {
      poe(r, 'v');
      for (i = 1; i <= fim; i++) poe(r, d[i]);
    }
    poe(r, 'e');
    if (e < 0) {
      poe(r, 'm');
      e = -e;
    }
    var de = digitosDe(e);
    for (i = 0; i < de.length; i++) poe(r, de[i]);
    return r;
  }
  if (e < 0) {
    // 0,000ddd: no máximo ALGARISMOS - 1 casas depois da vírgula.
    var b = arredonda(m, ALGARISMOS + e);
    if (b.e == 1) {
      e++;
      if (e == 0) return juntar(r, [1], []);
    }
    var frac = [];
    for (i = 0; i < -e - 1; i++) poe(frac, 0);
    for (i = 0; i < b.d.length; i++) poe(frac, b.d[i]);
    return juntar(r, [0], frac);
  }
  var ints = [];
  var resto = [];
  for (i = 0; i < d.length; i++) {
    if (i <= e) poe(ints, d[i]);
    else poe(resto, d[i]);
  }
  return juntar(r, ints, resto);
}

// Junta sinal, parte inteira e casas decimais (sem zeros à direita).
function juntar(r, ints, frac) {
  var i;
  for (i = 0; i < ints.length; i++) poe(r, ints[i]);
  var fim = frac.length - 1;
  while (fim >= 0 && frac[fim] == 0) fim--;
  if (fim >= 0) {
    poe(r, 'v');
    for (i = 0; i <= fim; i++) poe(r, frac[i]);
  }
  return r;
}

// Raiz quadrada sem Math: leva n para [1, 100) em passos de 100 e usa o método de Newton.
function raiz(n) {
  if (n == 0) return 0;
  var f = 1;
  while (n >= 100) {
    n = n / 100;
    f = f * 10;
  }
  while (n < 1) {
    n = n * 100;
    f = f / 10;
  }
  var g = 5;
  for (var i = 0; i < 30; i++) g = (g + n / g) / 2;
  return g * f;
}

function aplicar(a, operador, b) {
  if (operador == 'a') return a + b;
  if (operador == 's') return a - b;
  if (operador == 'x') return a * b;
  if (b == 0) return NaN;
  if (!COM_DECIMAIS && a % b != 0) return NaN;
  return a / b;
}

function mostraNumero(x) {
  if (!finito(x)) {
    limpar();
    erro = true;
    return;
  }
  ent = null;
  val = x;
}

// Aplica uma tecla ao estado.
function tecla(k) {
  if (k == 'c') {
    limpar();
    return;
  }
  var digito = k == '0' || k == '1' || k == '2' || k == '3' || k == '4' || k == '5' || k == '6' || k == '7' || k == '8' || k == '9';
  if (erro) {
    // Depois de um erro, só um número ou a vírgula recomeçam.
    if (digito || (k == 'v' && COM_DECIMAIS)) limpar();
    else return;
  }

  if (digito) {
    var d = Number(k);
    if (novo || ent === null || (ent.length == 1 && ent[0] == 0)) {
      ent = [d];
    } else if (quantosDigitos(ent) < MAX_DIGITOS) {
      poe(ent, d);
    }
    novo = false;
    operando = false;
    return;
  }

  if (k == 'v') {
    if (!COM_DECIMAIS) return;
    if (novo || ent === null) {
      ent = [0, 'v'];
      novo = false;
      operando = false;
    } else if (!temVirgula(ent)) {
      poe(ent, 'v');
    }
    return;
  }

  if (k == 'b') {
    if (novo || ent === null) return;
    var menor = [];
    for (var i = 0; i < ent.length - 1; i++) poe(menor, ent[i]);
    ent = menor.length == 0 ? [0] : menor;
    return;
  }

  if (k == 'r') {
    // Raiz do número no visor, na hora: 9 + √16 = 13.
    var n = valorAtual();
    if (n < 0) {
      mostraNumero(NaN);
      return;
    }
    var q = raiz(n);
    if (!COM_DECIMAIS) {
      q = inteiro(q + 0.5);
      if (q * q != n) q = NaN;
    }
    mostraNumero(q);
    novo = true;
    operando = op !== null;
    return;
  }

  if (k == 'p') {
    // Como no celular: 200 + 10% = 220 (10% de 200); sozinho, 50% = 0,5.
    var b = valorAtual();
    var base = acc !== null && (op == 'a' || op == 's') ? acc * b : b;
    if (!COM_DECIMAIS && base % 100 != 0) {
      mostraNumero(NaN);
      return;
    }
    mostraNumero(base / 100);
    novo = true;
    operando = op !== null;
    return;
  }

  if (k == 'a' || k == 's' || k == 'x' || k == 'd') {
    if (op !== null && (!novo || operando)) {
      mostraNumero(aplicar(acc, op, valorAtual()));
      if (erro) return;
    }
    acc = valorAtual();
    op = k;
    novo = true;
    operando = false;
    return;
  }

  if (k == 'i') {
    if (op === null) return;
    var resultado = aplicar(acc, op, valorAtual());
    acc = null;
    op = null;
    mostraNumero(resultado);
    novo = true;
    operando = false;
  }
}

// ---- Tela ----

var casasVisor = [];
var casasLinha = [];
var mostradoVisor = [];
var mostradoLinha = [];
var imgErro, destaque;

// Mostra uma lista de símbolos alinhada à direita em casas de imagem (prefixo "g_" ou "s_").
function desenhaCasas(casas, mostrado, prefixo, simbolos) {
  var total = casas.length;
  for (var i = 0; i < total; i++) {
    var j = i - (total - simbolos.length);
    if (j < 0) {
      if (mostrado[i] !== '') {
        set_obj_hidden(casas[i]);
        mostrado[i] = '';
      }
    } else {
      var s = '' + simbolos[j];
      if (mostrado[i] !== s) {
        set_img_src(casas[i], prefixo + s + '.bin');
        if (mostrado[i] === '') set_obj_display(casas[i]);
        mostrado[i] = s;
      }
    }
  }
}

function desenhar() {
  var linha = [];
  if (op !== null && !erro) {
    var a = formatar(acc);
    if (a !== null && a.length < LINHA_CASAS - 1) {
      for (var i = 0; i < a.length; i++) poe(linha, a[i]);
      poe(linha, op);
    }
  }
  desenhaCasas(casasLinha, mostradoLinha, 's_', linha);

  var simbolos = erro ? null : ent !== null ? ent : formatar(val);
  if (simbolos === null) {
    desenhaCasas(casasVisor, mostradoVisor, 'g_', []);
    set_obj_display(imgErro);
  } else {
    set_obj_hidden(imgErro);
    desenhaCasas(casasVisor, mostradoVisor, 'g_', simbolos);
  }
}

// Toque numa tecla: ao apertar (1) mostra o destaque; ao soltar (8) aplica a tecla.
function toque(codigo, i) {
  if (codigo == 1) {
    set_obj_pos(destaque, TECLA_X0 + (i % 4) * TECLA_PASSO_X, TECLA_Y0 + inteiro(i / 4) * TECLA_PASSO_Y);
    set_obj_display(destaque);
  } else if (codigo == 8) {
    set_obj_hidden(destaque);
    tecla(TECLAS[i]);
    desenhar();
  }
}

// Uma função por tecla, como nos jogos oficiais (sem closures).
function t0(c) { toque(c, 0); }
function t1(c) { toque(c, 1); }
function t2(c) { toque(c, 2); }
function t3(c) { toque(c, 3); }
function t4(c) { toque(c, 4); }
function t5(c) { toque(c, 5); }
function t6(c) { toque(c, 6); }
function t7(c) { toque(c, 7); }
function t8(c) { toque(c, 8); }
function t9(c) { toque(c, 9); }
function t10(c) { toque(c, 10); }
function t11(c) { toque(c, 11); }
function t12(c) { toque(c, 12); }
function t13(c) { toque(c, 13); }
function t14(c) { toque(c, 14); }
function t15(c) { toque(c, 15); }
function t16(c) { toque(c, 16); }
function t17(c) { toque(c, 17); }
function t18(c) { toque(c, 18); }
function t19(c) { toque(c, 19); }
var TOQUES = [t0, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19];

function sair() {
  exit_game();
}

function criarTela() {
  var i;
  for (i = 0; i < FUNDO_PARTES; i++) create_img(0, 'fundo' + i + '.bin', 0, i * FUNDO_A);
  var imgSair = create_img(0, 'sair.bin', SAIR_X, SAIR_Y);
  addEvent(imgSair, [7], sair);

  for (i = 0; i < LINHA_CASAS; i++) {
    poe(casasLinha, create_img(0, 's_0.bin', LINHA_X0 + i * LINHA_L, LINHA_Y));
    poe(mostradoLinha, '0');
  }
  for (i = 0; i < VISOR_CASAS; i++) {
    poe(casasVisor, create_img(0, 'g_0.bin', VISOR_X0 + i * VISOR_L, VISOR_Y));
    poe(mostradoVisor, '0');
  }
  imgErro = create_img(0, 'erro.bin', VISOR_X0 + VISOR_CASAS * VISOR_L - ERRO_L, VISOR_Y);

  var eventos = [1, 8];
  for (i = 0; i < TECLAS.length; i++) {
    var x = TECLA_X0 + (i % 4) * TECLA_PASSO_X;
    var y = TECLA_Y0 + inteiro(i / 4) * TECLA_PASSO_Y;
    var botao = create_btn(0, x, y, TECLA_L, TECLA_A);
    create_img(botao, 'k_' + TECLAS[i] + '.bin', 0, 0);
    addEvent(botao, eventos, TOQUES[i]);
  }
  destaque = create_img(0, 'destaque.bin', TECLA_X0, TECLA_Y0);
  set_obj_hidden(destaque);
}

function main() {
  // O gesto de voltar (evento 1 da tela) fecha o app, como nos jogos oficiais.
  var voltar = new EventMap(0, [1], sair, true);
  registerEvent(voltar);
  limpar();
  criarTela();
  desenhar();
}

main();
