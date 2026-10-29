#!/usr/bin/env python3
"""Monta a watch face "Calculadora" para a Mi Band 8 a partir de uma face de jogo oficial da Xiaomi.

    python3 build.py --base 谁是学霸.bin [--saida dist/Calculadora-face.bin]

A base é a face "谁是学霸" (o quiz de contas com o botão PLAY), que você baixa para a sua banda. Ela
não vem neste repositório: as imagens e o relógio dela são da Xiaomi. O script troca só:

- o app embutido (o quiz) pela calculadora de src/calc.js e as imagens desenhadas aqui;
- a imagem do botão PLAY por um botão CALC do mesmo tamanho, e a prévia da face;
- o ID e o nome da face, para não substituir a face original na banda.

O relógio, os widgets e a tela AOD continuam iguais aos da base. Antes de montar, o script confere
que remontar a base sem mudanças reproduz o arquivo byte a byte; depois confere a face nova.

Formato (watch face v2 "5A A5 34 12" da Mi Band 8), visto nas faces oficiais e no UnpackMiColorFace:
- cabeçalho de 0xA8 bytes: prévia em 0x20, ID (texto) em 0x28, nome em 0x68, telas em 0x1C;
- por tela, 8 + 10 × 8 bytes: imagem de fundo, prévia e (quantidade, tabela) de 10 seções;
- cada tabela tem itens de 16 bytes: id (tipo << 24 | índice), 4 zeros, posição e tamanho;
- seção 5 (apps): arquivos com 20 bytes de cabeçalho (tamanho em 24 bits, tamanho do nome) + nome +
  dados; imagens .bin no formato do LVGL (cf 4 = RGB565, cf 5 = BGRA8888);
- seção 9 (ações): o botão; ação 0x23721400 abre o app da seção 5 indicado em +0x2C.
"""
import argparse
import json
import os
import re
import struct
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont

AQUI = os.path.dirname(os.path.abspath(__file__))
TIPO_APP = 0x05
TIPO_IMAGEM = 0x02
ABRIR_APP = 0x23721400
NOVO_ID = b'970810001'
NOVO_NOME = 'Calculadora'

# Cores da face base (amostradas das imagens dela).
CREME = (255, 246, 222)
GRADE = (255, 226, 222)
MARROM = (107, 50, 3)
MARROM_CLARO = (160, 112, 74)
AMARELO = (244, 217, 142)
ROSA = (255, 217, 212)
BRANCO = (255, 251, 240)
VERDE = (164, 214, 140)


def u32(d, o):
    return struct.unpack_from('<I', d, o)[0]


# ---------------------------------------------------------------- arquivo da face

class Face:
    """Uma watch face v2 desmontada em cabeçalho, tabelas e blocos de dados."""

    def __init__(self, dados):
        if dados[:4] != bytes.fromhex('5aa53412'):
            raise SystemExit('A base não é uma watch face v2 (não começa com 5A A5 34 12).')
        self.cabecalho = bytearray(dados[:0xA8])
        if dados[0x18] or dados[0x1D]:
            raise SystemExit('Cabeçalho com campos extras: este script só conhece faces sem eles.')
        self.telas = []
        h = 0xA8
        for _ in range(dados[0x1C]):
            tela = {'fundo': u32(dados, h), 'previa': u32(dados, h + 4), 'secoes': []}
            for s in range(10):
                qtd = u32(dados, h + 8 + 8 * s)
                tab = u32(dados, h + 12 + 8 * s)
                itens = []
                for i in range(qtd):
                    e = tab + 16 * i
                    pos, tam = u32(dados, e + 8), u32(dados, e + 12)
                    real = 0x14 + dados[pos + 3] + (u32(dados, pos) & 0xFFFFFF) if s == 5 else tam
                    itens.append({'id': dados[e:e + 8], 'pos': pos, 'tam': tam, 'dados': dados[pos:pos + real]})
                tela['secoes'].append(itens)
            self.telas.append(tela)
            h += 88
        self.pos_previa = u32(dados, 0x20)
        primeiro = min(i['pos'] for t in self.telas for sec in t['secoes'] for i in sec)
        self.previa = dados[self.pos_previa:primeiro]
        # Ordem original dos blocos no arquivo (a remontagem segue a mesma ordem).
        self.ordem = sorted(((i['pos'], t, s, n) for t, tela in enumerate(self.telas)
                             for s, sec in enumerate(tela['secoes']) for n, i in enumerate(sec)))
        self.ordem = [(t, s, n) for _, t, s, n in self.ordem]
        self.tamanho_original = len(dados)

    def montar(self, ordem=None):
        """Remonta o arquivo: cabeçalho, telas, tabelas, prévia e blocos, sem buracos."""
        ordem = ordem or self.ordem
        n_telas = len(self.telas)
        pos = 0xA8 + 88 * n_telas
        tabelas = []
        for tela in self.telas:
            linha = []
            for itens in tela['secoes']:
                linha.append(pos)
                pos += 16 * len(itens)
            tabelas.append(linha)
        pos_previa = pos
        pos += len(self.previa)
        posicoes = {}
        for chave in ordem:
            t, s, n = chave
            posicoes[chave] = pos
            pos += len(self.telas[t]['secoes'][s][n]['dados'])

        out = bytearray(pos)
        out[:0xA8] = self.cabecalho
        struct.pack_into('<I', out, 0x20, pos_previa)
        for t, tela in enumerate(self.telas):
            h = 0xA8 + 88 * t
            previa = pos_previa if tela['previa'] == self.pos_previa else tela['previa']
            struct.pack_into('<II', out, h, tela['fundo'], previa)
            for s, itens in enumerate(tela['secoes']):
                struct.pack_into('<II', out, h + 8 + 8 * s, len(itens), tabelas[t][s])
                for n, item in enumerate(itens):
                    e = tabelas[t][s] + 16 * n
                    out[e:e + 8] = item['id']
                    struct.pack_into('<II', out, e + 8, posicoes[(t, s, n)], item['tam'])
        out[pos_previa:pos_previa + len(self.previa)] = self.previa
        for chave, p in posicoes.items():
            t, s, n = chave
            d = self.telas[t]['secoes'][s][n]['dados']
            out[p:p + len(d)] = d
        return bytes(out)


# ---------------------------------------------------------------- imagens

def lvgl_bin(im, com_alfa):
    """Imagem do LVGL v8 (.bin): cabeçalho cf | w << 10 | h << 21 e os pixels."""
    w, h = im.size
    im = im.convert('RGBA')
    rgba = im.tobytes()
    px = bytearray()
    if com_alfa:
        cab = struct.pack('<I', 5 | w << 10 | h << 21)
        for i in range(0, len(rgba), 4):
            px += bytes((rgba[i + 2], rgba[i + 1], rgba[i], rgba[i + 3]))
    else:
        cab = struct.pack('<I', 4 | w << 10 | h << 21)
        for i in range(0, len(rgba), 4):
            px += struct.pack('<H', (rgba[i] >> 3) << 11 | (rgba[i + 1] >> 2) << 5 | rgba[i + 2] >> 3)
    return cab + bytes(px)


def ler_lvgl_bin(dados):
    h = u32(dados, 0)
    cf, w, alt = h & 31, (h >> 10) & 0x7FF, (h >> 21) & 0x7FF
    im = Image.new('RGBA', (w, alt))
    px = dados[4:]
    if cf == 5:
        im.putdata([(px[i + 2], px[i + 1], px[i], px[i + 3]) for i in range(0, w * alt * 4, 4)])
    elif cf == 4:
        cores = []
        for i in range(0, w * alt * 2, 2):
            v = px[i] | px[i + 1] << 8
            cores.append(((v >> 11) * 255 // 31, ((v >> 5) & 63) * 255 // 63, (v & 31) * 255 // 31, 255))
        im.putdata(cores)
    else:
        raise ValueError(f'formato de cor {cf} desconhecido')
    return im


RLE = bytes((0xE0, 0x21, 0xA5, 0x5A))


def rle_bgra(im):
    """Compressão das imagens da face (tipo 4, BGRA), do jeito das faces oficiais: repetição só para
    5 ou mais pixels iguais, trechos de até 127 e nenhum trecho literal de 1 pixel."""
    rgba = im.convert('RGBA').tobytes()
    px = [bytes((rgba[i + 2], rgba[i + 1], rgba[i], rgba[i + 3])) for i in range(0, len(rgba), 4)]
    # Grupos: ['R', pixels iguais] (5 ou mais) ou ['L', pixels].
    grupos = []
    i = 0
    while i < len(px):
        n = 1
        while i + n < len(px) and px[i + n] == px[i]:
            n += 1
        if n >= 5:
            grupos.append(['R', px[i:i + n]])
        elif grupos and grupos[-1][0] == 'L':
            grupos[-1][1] += px[i:i + n]
        else:
            grupos.append(['L', px[i:i + n]])
        i += n
    # Um literal de 1 pixel pega um pixel de uma repetição vizinha (ou a absorve, se ela tiver só 5).
    k = 0
    while k < len(grupos):
        tipo, pix = grupos[k]
        if tipo == 'L' and len(pix) == 1:
            prox = grupos[k + 1] if k + 1 < len(grupos) else None
            ant = grupos[k - 1] if k > 0 else None
            if prox and len(prox[1]) >= 6:
                pix.append(prox[1].pop(0))
            elif ant and len(ant[1]) >= 6:
                pix.insert(0, ant[1].pop())
            elif prox:
                grupos[k][1] = pix + prox[1]
                del grupos[k + 1]
                if k + 1 < len(grupos) and grupos[k + 1][0] == 'L':
                    grupos[k][1] += grupos.pop(k + 1)[1]
            elif ant:
                grupos[k][1] = ant[1] + pix
                del grupos[k - 1]
                k -= 1
            else:
                raise ValueError('imagem de 1 pixel')
            continue
        k += 1
    out = bytearray(RLE + struct.pack('<I', len(px) * 4 << 4 | 4))
    for tipo, pix in grupos:
        minimo = 5 if tipo == 'R' else 2
        while pix:
            n = min(127, len(pix))
            if 0 < len(pix) - n < minimo:
                n = len(pix) - minimo
            if tipo == 'R':
                out += bytes((n,)) + pix[0]
            else:
                out += bytes((0x80 | n,)) + b''.join(pix[:n])
            pix = pix[n:]
    return bytes(out)


def ler_imagem_face(dados):
    """Imagem da face: 12 bytes de cabeçalho (…, largura, altura, tamanho) e RLE tipo 4."""
    w, h = struct.unpack_from('<HH', dados, 4)
    p = dados[12:12 + u32(dados, 8)]
    if p[:4] != RLE or u32(p, 4) & 15 != 4:
        raise ValueError('imagem da face sem a compressão conhecida')
    out = bytearray()
    o = 8
    while o < len(p):
        c = p[o]
        o += 1
        n = c & 0x7F
        if c & 0x80:
            out += p[o:o + 4 * n]
            o += 4 * n
        else:
            out += p[o:o + 4] * n
            o += 4
    if len(out) != w * h * 4:
        raise ValueError('imagem da face com tamanho errado')
    im = Image.new('RGBA', (w, h))
    im.putdata([(out[i + 2], out[i + 1], out[i], out[i + 3]) for i in range(0, len(out), 4)])
    return im


def imagem_face(im, cabecalho_original):
    dados = rle_bgra(im)
    w, h = im.size
    return bytes(cabecalho_original[:4]) + struct.pack('<HHI', w, h, len(dados)) + dados


def fonte(tam):
    for nome in ('DejaVuSans-Bold.ttf', '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',
                 '/Library/Fonts/Arial Bold.ttf', 'C:/Windows/Fonts/arialbd.ttf'):
        try:
            return ImageFont.truetype(nome, tam)
        except OSError:
            pass
    raise SystemExit('Fonte não encontrada: instale a DejaVu Sans (pacote fonts-dejavu).')


def texto_centro(draw, caixa, texto, tam, cor):
    f = fonte(tam)
    x0, y0, x1, y1 = caixa
    b = draw.textbbox((0, 0), texto, font=f)
    draw.text(((x0 + x1 - (b[2] - b[0])) / 2 - b[0], (y0 + y1 - (b[3] - b[1])) / 2 - b[1]), texto, font=f, fill=cor)


def pilula(l, a, fundo, contorno, raio, larg=2, escala=4):
    """Retângulo arredondado suave (desenhado 4× maior e reduzido)."""
    im = Image.new('RGBA', (l * escala, a * escala), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((0, 0, l * escala - 1, a * escala - 1), raio * escala, fill=fundo,
                        outline=contorno, width=larg * escala)
    return im.resize((l, a), Image.LANCZOS)


def constantes_js():
    fonte_js = open(os.path.join(AQUI, 'src', 'calc.js'), encoding='utf-8').read()
    c = {m[0]: int(m[1]) for m in re.findall(r'^var ([A-Z_0-9]+) = (\d+);', fonte_js, re.M)}
    teclas = re.search(r"^var TECLAS = \[([^\]]+)\];", fonte_js, re.M).group(1)
    c['TECLAS'] = re.findall(r"'([^']+)'", teclas)
    return fonte_js, c


ROTULOS = {'c': 'C', 'r': '√', 'p': '%', 'd': '÷', 'x': '×', 's': '−', 'a': '+', 'b': '⌫', 'v': ',', 'i': '='}
SIMBOLOS = {'v': ',', 'm': '-', 'e': 'e', 'a': '+', 's': '−', 'x': '×', 'd': '÷'}


def imagens_do_app(c):
    """Todas as imagens do app: {nome: bytes .bin}."""
    arq = {}

    fundo = Image.new('RGB', (192, c['FUNDO_A']), CREME)
    d = ImageDraw.Draw(fundo)
    for x in range(0, 192, 14):
        d.line((x, 0, x, c['FUNDO_A']), fill=GRADE)
    for y in range(0, c['FUNDO_A'], 14):
        d.line((0, y, 192, y), fill=GRADE)
    arq['fundo.bin'] = lvgl_bin(fundo, False)

    l, a = c['TECLA_L'], c['TECLA_A']
    for k in c['TECLAS']:
        cor = AMARELO if k in 'dxsa' else VERDE if k == 'i' else ROSA if k in 'crpb' else BRANCO
        im = pilula(l, a, cor, MARROM, 12)
        tam = {'b': 20, 'v': 34}.get(k, 24)
        texto_centro(ImageDraw.Draw(im), (0, 0, l, a - (8 if k == 'v' else 0)), ROTULOS.get(k, k), tam, MARROM)
        arq[f'k_{k}.bin'] = lvgl_bin(im, True)
    arq['destaque.bin'] = lvgl_bin(pilula(l, a, MARROM + (90,), MARROM + (90,), 12), True)

    for prefixo, w, h, tam, cor in (('g_', c['VISOR_L'], c['VISOR_A'], 20, MARROM),
                                     ('s_', c['LINHA_L'], c['LINHA_A'], 11, MARROM_CLARO)):
        for s in [str(i) for i in range(10)] + list('vme') + (list('asxd') if prefixo == 's_' else []):
            im = Image.new('RGBA', (w, h), (0, 0, 0, 0))
            texto = SIMBOLOS.get(s, s)
            dr = ImageDraw.Draw(im)
            if s == 'v':
                # A vírgula fica embaixo, como na escrita.
                f = fonte(tam)
                b = dr.textbbox((0, 0), ',', font=f)
                dr.text(((w - (b[2] - b[0])) / 2 - b[0], h - (b[3] - b[1]) - b[1] - 1), ',', font=f, fill=cor)
            else:
                f = fonte(tam if s != 'e' else tam - 4)
                b = dr.textbbox((0, 0), texto, font=f)
                x = (w - f.getlength(texto)) / 2 if s.isdigit() else (w - (b[2] - b[0])) / 2 - b[0]
                dr.text((x, (h - (b[3] - b[1])) / 2 - b[1] if not s.isdigit() else (h - tam) / 2 - 1), texto, font=f, fill=cor)
            arq[f'{prefixo}{s}.bin'] = lvgl_bin(im, True)

    im = Image.new('RGBA', (c['ERRO_L'], c['VISOR_A']), (0, 0, 0, 0))
    texto_centro(ImageDraw.Draw(im), (0, 0, c['ERRO_L'], c['VISOR_A']), 'Erro', 22, (178, 34, 34))
    arq['erro.bin'] = lvgl_bin(im, True)

    im = pilula(56, 26, AMARELO, MARROM, 13)
    texto_centro(ImageDraw.Draw(im), (0, 0, 56, 26), 'sair', 15, MARROM)
    arq['sair.bin'] = lvgl_bin(im, True)
    return arq


def botao_calc(l, a):
    im = pilula(l, a, AMARELO, MARROM, a // 2, larg=max(1, round(a / 20)))
    texto_centro(ImageDraw.Draw(im), (0, 0, l, a), 'CALC', round(a / 2), MARROM)
    return im


# ---------------------------------------------------------------- montagem

def montar(base, saida):
    dados = open(base, 'rb').read()
    face = Face(dados)
    if face.montar() != dados:
        raise SystemExit('Remontar a base sem mudanças não reproduz o arquivo: formato inesperado, nada foi gerado.')
    if len(face.telas) != 2:
        raise SystemExit('A base precisa ter a tela normal e a AOD (2 telas).')

    tela = face.telas[0]
    acoes = [i for i in tela['secoes'][9] if len(i['dados']) >= 0x30 and u32(i['dados'], 0x28) == ABRIR_APP]
    if len(acoes) != 1:
        raise SystemExit('A base precisa ter exatamente um botão que abre um app (como o PLAY do quiz).')
    acao = acoes[0]
    apps = tela['secoes'][5]
    alvo = u32(acao['dados'], 0x2C)
    if alvo >> 24 != TIPO_APP or (alvo & 0xFFFFFF) >= len(apps):
        raise SystemExit('O botão da base não aponta para um app válido.')
    n_img = u32(acao['dados'], 0x20)
    if n_img >> 24 != TIPO_IMAGEM or (n_img & 0xFFFFFF) >= len(tela['secoes'][2]):
        raise SystemExit('O botão da base não tem uma imagem válida.')
    img_botao = tela['secoes'][2][n_img & 0xFFFFFF]
    antes = ler_imagem_face(img_botao['dados'])

    fonte_js, c = constantes_js()
    arquivos = imagens_do_app(c)
    nome_js = 'calc.js'
    novos = []
    for nome in sorted(arquivos) + [nome_js]:
        conteudo = arquivos[nome] if nome != nome_js else fonte_js.encode('utf-8')
        n = nome.encode('ascii')
        if len(conteudo) >= 1 << 24 or len(n) > 255:
            raise SystemExit(f'{nome} grande demais')
        bloco = struct.pack('<I', len(conteudo) | len(n) << 24) + bytes(16) + n + conteudo
        # Tamanho na tabela: como nas faces oficiais (imagem: dados + 12; script: o bloco inteiro).
        tam = len(conteudo) + 12 if nome.endswith('.bin') else len(bloco)
        novos.append({'id': struct.pack('<II', TIPO_APP << 24 | len(novos), 0), 'pos': 0, 'tam': tam,
                      'dados': bloco, 'nome': nome})

    # Os blocos dos apps antigos estão juntos no arquivo; os novos entram no mesmo lugar.
    chaves_apps = [k for k in face.ordem if k[0] == 0 and k[1] == 5]
    inicio = face.ordem.index(chaves_apps[0])
    if face.ordem[inicio:inicio + len(chaves_apps)] != sorted(chaves_apps, key=lambda k: apps[k[2]]['pos']):
        raise SystemExit('Os apps da base não estão juntos no arquivo: formato inesperado.')
    ordem = face.ordem[:inicio] + [(0, 5, n) for n in range(len(novos))] + face.ordem[inicio + len(chaves_apps):]
    tela['secoes'][5] = novos

    d = bytearray(acao['dados'])
    struct.pack_into('<I', d, 0x2C, TIPO_APP << 24 | (len(novos) - 1))
    acao['dados'] = bytes(d)

    calc = botao_calc(*antes.size)
    img_botao['dados'] = imagem_face(calc, img_botao['dados'])
    img_botao['tam'] = len(img_botao['dados'])

    # Prévia: o botão CALC desenhado sobre o PLAY, na escala da prévia.
    previa = ler_imagem_face(face.previa)
    elem = next(i for i in tela['secoes'][0] if u32(i['dados'], 0) == u32(acao['id'], 0))
    ex, ey = struct.unpack_from('<HH', elem['dados'], 4)
    k = previa.width / 192
    pequeno = botao_calc(round(calc.width * k), round(calc.height * k))
    caixa = (round(ex * k), round(ey * k))
    # Apaga o PLAY antigo com a cor do fundo logo acima dele e desenha o CALC.
    cor_fundo = previa.getpixel((caixa[0] + pequeno.width // 2, max(0, caixa[1] - 3)))
    previa.paste(Image.new('RGBA', pequeno.size, cor_fundo), caixa)
    previa.alpha_composite(pequeno, caixa)
    face.previa = imagem_face(previa, face.previa)

    face.cabecalho[0x28:0x68] = NOVO_ID.ljust(0x40, b'\0')
    face.cabecalho[0x68:0xA8] = NOVO_NOME.encode('utf-8').ljust(0x40, b'\0')

    resultado = face.montar(ordem)
    conferir(resultado, dados, fonte_js, arquivos)
    os.makedirs(os.path.dirname(os.path.abspath(saida)), exist_ok=True)
    open(saida, 'wb').write(resultado)
    return resultado, previa, calc, arquivos


def conferir(novo, base, fonte_js, arquivos):
    """Remonta o arquivo gerado e confere que só mudou o que devia."""
    f = Face(novo)
    b = Face(base)
    if f.montar() != novo:
        raise SystemExit('ERRO: a face gerada não se remonta igual.')
    if f.tamanho_original != len(novo) or len(f.telas) != 2:
        raise SystemExit('ERRO: estrutura da face gerada.')
    # A tela AOD e todos os itens da tela normal, exceto apps, botão e sua imagem, ficam iguais.
    for s in range(10):
        for n, (x, y) in enumerate(zip(f.telas[1]['secoes'][s], b.telas[1]['secoes'][s])):
            if x['dados'] != y['dados'] or x['tam'] != y['tam']:
                raise SystemExit(f'ERRO: a tela AOD mudou (seção {s}, item {n}).')
    for s in (0, 1, 3, 4, 6, 7, 8):
        if [i['dados'] for i in f.telas[0]['secoes'][s]] != [i['dados'] for i in b.telas[0]['secoes'][s]]:
            raise SystemExit(f'ERRO: a seção {s} da tela normal mudou.')
    apps = f.telas[0]['secoes'][5]
    nomes = []
    for n, item in enumerate(apps):
        d = item['dados']
        fs, fl = u32(d, 0) & 0xFFFFFF, d[3]
        nome = d[20:20 + fl].decode('ascii')
        conteudo = d[20 + fl:]
        nomes.append(nome)
        if u32(item['id'], 0) != TIPO_APP << 24 | n or len(conteudo) != fs or any(d[4:20]):
            raise SystemExit(f'ERRO: app {nome}.')
        if nome.endswith('.bin'):
            if conteudo != arquivos[nome] or item['tam'] != fs + 12:
                raise SystemExit(f'ERRO: imagem {nome}.')
            ler_lvgl_bin(conteudo)
        elif conteudo.decode('utf-8') != fonte_js or item['tam'] != len(d):
            raise SystemExit('ERRO: script.')
    acao = [i for i in f.telas[0]['secoes'][9] if len(i['dados']) >= 0x30 and u32(i['dados'], 0x28) == ABRIR_APP][0]
    if nomes[u32(acao['dados'], 0x2C) & 0xFFFFFF] != 'calc.js':
        raise SystemExit('ERRO: o botão não abre o calc.js.')
    usados = set(re.findall(r"'([a-z_0-9]+\.bin)'", fonte_js))
    usados |= {f'k_{k}.bin' for k in constantes_js()[1]['TECLAS']}
    usados |= {f'{p}{s}.bin' for p in ('g_', 's_') for s in [str(i) for i in range(10)] + list('vme')}
    usados |= {f's_{s}.bin' for s in 'asxd'}
    faltando = usados - set(nomes)
    if faltando:
        raise SystemExit(f'ERRO: o script usa imagens que não estão na face: {sorted(faltando)}')
    if novo[0x28:0x31] != NOVO_ID:
        raise SystemExit('ERRO: ID.')


# ---------------------------------------------------------------- prévia

def desenhar_cena(objs, arquivos):
    tela = Image.new('RGBA', (192, 490), (0, 0, 0, 255))
    por_id = {o['id']: o for o in objs}

    def pos(o):
        x, y = o['x'], o['y']
        p = o['pai']
        while p:
            x += por_id[p]['x']
            y += por_id[p]['y']
            p = por_id[p]['pai']
        return x, y

    def visivel(o):
        while o:
            if o['oculto']:
                return False
            o = por_id.get(o['pai'])
        return True

    for o in objs:
        if o['tipo'] == 'img' and visivel(o):
            tela.alpha_composite(ler_lvgl_bin(arquivos[o['arquivo']]), pos(o))
    return tela


def mascara_capsula(im, cor=(30, 30, 30)):
    """Recorta como a tela em cápsula da Mi Band 8 (raio 96: o caso mais apertado)."""
    m = Image.new('L', (192 * 4, 490 * 4), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, 192 * 4 - 1, 490 * 4 - 1), 96 * 4, fill=255)
    m = m.resize((192, 490), Image.LANCZOS)
    fundo = Image.new('RGBA', (192, 490), cor + (255,))
    fundo.paste(im, (0, 0), m)
    return fundo


def previas(pasta, previa, calc, arquivos):
    cenas_json = os.path.join(pasta, 'cenas.json')
    subprocess.run(['node', '--no-warnings', os.path.join(AQUI, 'test', 'calc-test.mjs'), cenas_json], check=True)
    cenas = json.load(open(cenas_json))
    telas = [mascara_capsula(desenhar_cena(cenas[n], arquivos)) for n in ('inicio', 'conta', 'resultado', 'raiz', 'erro')]
    face = mascara_capsula(previa.resize((192, 490), Image.LANCZOS))
    folha = Image.new('RGB', (40 + 212 * 6, 560), (30, 30, 30))
    d = ImageDraw.Draw(folha)
    rotulos = ['Face (prévia)', 'Calculadora', '1234,5 +', '9 + √16 × 2 =', '√2', '5 ÷ 0 =']
    for i, (im, r) in enumerate(zip([face] + telas, rotulos)):
        folha.paste(im.convert('RGB'), (20 + 212 * i, 20))
        texto_centro(d, (20 + 212 * i, 520, 212 + 212 * i, 550), r, 16, (230, 230, 230))
    folha.save(os.path.join(pasta, 'previa-calculadora-face.png'))
    os.remove(cenas_json)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--base', required=True, help='face 谁是学霸 (.bin) baixada para a sua Mi Band 8')
    p.add_argument('--saida', default=os.path.join(AQUI, 'dist', 'Calculadora-face.bin'))
    a = p.parse_args()
    resultado, previa, calc, arquivos = montar(a.base, a.saida)
    previas(os.path.dirname(os.path.abspath(a.saida)), previa, calc, arquivos)
    print(f'{a.saida}: {len(resultado)} bytes ({len(resultado) // 1024} KB), {len(arquivos) + 1} arquivos no app')


if __name__ == '__main__':
    sys.exit(main())
