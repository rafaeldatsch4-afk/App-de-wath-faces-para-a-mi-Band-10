// Teorema de Pitágoras: lógica pura, sem nenhuma API da banda (testada com node em test/).
//
//   hipotenusa:  c² = a² + b²
//   cateto:      a² = c² - b²   ou   b² = c² - a²
//
// O usuário marca com X o lado que quer descobrir e digita os outros dois. As contas são feitas
// em números inteiros (os lados têm até 2 casas decimais, então em centésimos; os quadrados, em
// décimos de milésimo), sem erro de ponto flutuante; só a raiz quadrada é calculada com Math.sqrt.

// Os três lados, na ordem da tela. "inteiros" limita os dígitos antes da vírgula (até 9999,99).
export var LADOS = [
  { id: 'a', titulo: 'Cateto a', decimais: 2, inteiros: 4 },
  { id: 'b', titulo: 'Cateto b', decimais: 2, inteiros: 4 },
  { id: 'c', titulo: 'Hipotenusa c', decimais: 2, inteiros: 4 }
];

export var FORMULAS = {
  a: 'a² = c² - b²',
  b: 'b² = c² - a²',
  c: 'c² = a² + b²'
};

// Aplica uma tecla ('0'–'9', ',', 'X' ou 'DEL') ao texto digitado e devolve o texto novo.
// 'X' vale o texto inteiro: o lado a descobrir.
export function digitar(texto, tecla, campo) {
  if (tecla === 'X') return 'X';
  if (tecla === 'DEL') return texto === 'X' ? '' : texto.slice(0, -1);
  if (texto === 'X') texto = '';
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

// Texto digitado ("2,5") para número (2.5). Vazio e "X" viram NaN.
export function paraNumero(texto) {
  if (texto === '' || texto === ',' || texto === 'X') return NaN;
  return parseFloat(texto.replace(',', '.'));
}

// Devolve a mensagem de erro do campo (curta, cabe numa linha da banda), ou '' se o texto serve.
export function validar(texto) {
  if (texto === 'X') return '';
  var n = paraNumero(texto);
  if (isNaN(n)) return 'Digite um valor';
  if (n <= 0) return 'Maior que zero';
  return '';
}

// Número inteiro q em unidades de 10^-casas para texto com vírgula, sem zeros à direita:
// unidades(250000, 4) → "25"; unidades(14142, 4) → "1,4142".
export function unidades(q, casas) {
  var s = String(q);
  while (s.length <= casas) s = '0' + s;
  var inteiro = s.slice(0, s.length - casas);
  var frac = s.slice(s.length - casas);
  while (frac.length > 0 && frac.charAt(frac.length - 1) === '0') frac = frac.slice(0, frac.length - 1);
  return frac === '' ? inteiro : inteiro + ',' + frac;
}

// textos: os textos dos três lados (a, b, c), exatamente um deles "X" e os outros dois números.
// Devolve { erro } ou { id, quadrado, valor, exato, conhecidos }. O erro é curto, de uma ou duas
// linhas da banda, separadas por "|":
//   id: o lado descoberto ('a', 'b' ou 'c');
//   quadrado: o quadrado dele, em décimos de milésimo (inteiro exato);
//   valor: o lado, arredondado para 4 casas, em décimos de milésimo (inteiro);
//   exato: true se o lado tem no máximo 2 casas (raiz exata), false se o valor é aproximado;
//   conhecidos: os outros dois lados, [{ id, centesimos }].
export function calcular(textos) {
  var x = -1;
  var n = [0, 0, 0];
  for (var i = 0; i < 3; i++) {
    if (textos[i] === 'X') {
      if (x >= 0) return { erro: 'Só um X' };
      x = i;
    } else {
      var numero = paraNumero(textos[i]);
      if (isNaN(numero) || numero <= 0) return { erro: 'Preencha tudo' };
      n[i] = Math.round(numero * 100);
    }
  }
  if (x < 0) return { erro: 'Marque um X' };

  var soma;
  if (x === 2) {
    soma = n[0] * n[0] + n[1] * n[1];
  } else {
    var cateto = n[x === 0 ? 1 : 0];
    soma = n[2] * n[2] - cateto * cateto;
    if (soma <= 0) return { erro: 'A hipotenusa deve|ser o maior lado' };
  }

  var raiz = Math.sqrt(soma); // em centésimos
  var inteira = Math.round(raiz);
  var exato = inteira * inteira === soma;
  var conhecidos = [];
  for (var j = 0; j < 3; j++) {
    if (j !== x) conhecidos.push({ id: LADOS[j].id, centesimos: n[j] });
  }
  return {
    id: LADOS[x].id,
    quadrado: soma,
    valor: exato ? inteira * 100 : Math.round(raiz * 100),
    exato: exato,
    conhecidos: conhecidos
  };
}

// Os textos do resultado, prontos para a tela.
export function descrever(r) {
  return {
    titulo: r.id === 'c' ? 'Hipotenusa (c)' : 'Cateto (' + r.id + ')',
    formula: FORMULAS[r.id],
    dado1: r.conhecidos[0].id + ' = ' + unidades(r.conhecidos[0].centesimos, 2),
    dado2: r.conhecidos[1].id + ' = ' + unidades(r.conhecidos[1].centesimos, 2),
    quadrado: r.id + '² = ' + unidades(r.quadrado, 4),
    legenda: r.exato ? 'Resultado' : 'Aproximado',
    resposta: r.id + ' = ' + unidades(r.valor, 4)
  };
}

// Valor de um lado já confirmado, para mostrar na barra da tela principal: "2,50" → "2,5".
export function mostrarLado(texto) {
  if (texto === 'X') return 'X';
  return unidades(Math.round(paraNumero(texto) * 100), 2);
}

// Texto enquanto digita (com o X, se for o caso).
export function mostrarRascunho(texto) {
  return texto === '' ? '0' : texto;
}
