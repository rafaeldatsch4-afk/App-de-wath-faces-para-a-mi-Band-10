"""Desenho no estilo "vidro líquido": papel de parede colorido e desfocado, painéis de vidro fosco com
borda clara, texto branco com sombra suave. Tudo é desenhado aqui com o Pillow; o vidro dos painéis
fica "assado" no fundo (a banda não desfoca nada em tempo real)."""
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

ESCALA = 4  # desenha 4× maior e reduz, para bordas suaves

FUNDO_BASE = (8, 12, 38)
MANCHAS = [  # (cor, centro x, centro y, raio) numa tela de 192 × 490
    ((47, 107, 255), 10, 40, 150),
    ((130, 70, 255), 190, 190, 150),
    ((255, 79, 154), 0, 350, 140),
    ((255, 158, 66), 185, 480, 130),
    ((40, 200, 255), 150, 20, 90),
]
LARANJA = (255, 159, 10)
VERDE = (48, 209, 88)
VERMELHO_CLARO = (255, 130, 130)


def fonte(tam):
    for nome in ('DejaVuSans-Bold.ttf', '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',
                 '/Library/Fonts/Arial Bold.ttf', 'C:/Windows/Fonts/arialbd.ttf'):
        try:
            return ImageFont.truetype(nome, tam)
        except OSError:
            pass
    raise SystemExit('Fonte não encontrada: instale a DejaVu Sans (pacote fonts-dejavu).')


def papel_de_parede(l=192, a=490):
    im = Image.new('RGB', (l, a), FUNDO_BASE)
    for cor, x, y, r in MANCHAS:
        camada = Image.new('RGB', (l, a), cor)
        m = Image.new('L', (l, a), 0)
        ImageDraw.Draw(m).ellipse((x - r, y - r, x + r, y + r), fill=210)
        im = Image.composite(camada, im, m.filter(ImageFilter.GaussianBlur(r * 0.45)))
    return im


def mascara_arredondada(tam, raio):
    l, a = tam
    m = Image.new('L', (l * ESCALA, a * ESCALA), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, l * ESCALA - 1, a * ESCALA - 1), raio * ESCALA, fill=255)
    return m.resize((l, a), Image.LANCZOS)


def borda(tam, raio, alfa_cima=200, alfa_baixo=50, larg=1.5):
    """Borda clara do vidro: mais forte em cima (luz vindo de cima), como no vidro líquido."""
    l, a = tam
    E = ESCALA
    anel = Image.new('L', (l * E, a * E), 0)
    d = ImageDraw.Draw(anel)
    d.rounded_rectangle((0, 0, l * E - 1, a * E - 1), raio * E, outline=255, width=round(larg * E))
    anel = anel.resize((l, a), Image.LANCZOS)
    degrade = Image.linear_gradient('L').resize((l, a)).point(lambda v: alfa_cima + (alfa_baixo - alfa_cima) * v // 255)
    out = Image.new('RGBA', (l, a), (255, 255, 255, 0))
    out.putalpha(ImageChops.multiply(anel, degrade))
    return out


def painel(fundo, caixa, raio, tinta=(255, 255, 255), forca=0.20, sombra=True):
    """Assa um painel de vidro fosco em `fundo` (RGB) na caixa (x0, y0, x1, y1)."""
    x0, y0, x1, y1 = caixa
    tam = (x1 - x0, y1 - y0)
    m = mascara_arredondada(tam, raio)
    if sombra:
        s = Image.new('L', fundo.size, 0)
        s.paste(m, (x0, y0 + 4))
        s = s.filter(ImageFilter.GaussianBlur(6)).point(lambda v: v * 70 // 255)
        fundo = Image.composite(Image.new('RGB', fundo.size, (0, 0, 0)), fundo, s)
    pedaco = fundo.crop(caixa).filter(ImageFilter.GaussianBlur(6))
    pedaco = Image.blend(pedaco, Image.new('RGB', tam, tinta), forca)
    # Brilho suave no alto do painel.
    brilho = Image.linear_gradient('L').resize(tam).point(lambda v: max(0, 60 - v * 60 // 110))
    pedaco = Image.composite(Image.new('RGB', tam, (255, 255, 255)), pedaco, brilho)
    fundo = fundo.copy()
    fundo.paste(pedaco, (x0, y0), m)
    b = borda(tam, raio)
    rgba = fundo.convert('RGBA')
    rgba.alpha_composite(b, (x0, y0))
    return rgba.convert('RGB')


def texto(tam_caixa, txt, tam_fonte, cor=(255, 255, 255), alfa=255, largura_max=None, sombra=True,
          dy=0, tabular=False):
    """Texto branco com sombra suave numa imagem RGBA transparente do tamanho da caixa. Com
    `tabular`, centraliza pela largura de avanço (igual em todos os algarismos) e pela altura da
    fonte, para os números ficarem alinhados e com espaços iguais."""
    l, a = tam_caixa
    E = ESCALA
    f = fonte(tam_fonte * E)
    caixa = ImageDraw.Draw(Image.new('L', (1, 1))).textbbox((0, 0), txt, font=f)
    if tabular:
        sobe, desce = f.getmetrics()
        x0, topo = 0, f.getbbox('0')[1]
        tl, ta = round(f.getlength(txt)), f.getbbox('0')[3] - topo
    else:
        x0, topo = caixa[0], caixa[1]
        tl, ta = caixa[2] - caixa[0], caixa[3] - caixa[1]
    glifo = Image.new('L', (tl + 2 * E, ta + 2 * E), 0)
    ImageDraw.Draw(glifo).text((E - x0, E - topo), txt, font=f, fill=255)
    gl, ga = glifo.size[0] // E, glifo.size[1] // E
    if largura_max and gl > largura_max:
        gl = largura_max  # estreita os algarismos grandes para caberem na casa
    glifo = glifo.resize((max(1, gl), max(1, ga)), Image.LANCZOS)
    x = (l - gl) // 2
    y = (a - ga) // 2 + dy
    out = Image.new('RGBA', (l, a), (0, 0, 0, 0))
    if sombra:
        s = Image.new('L', (l, a), 0)
        s.paste(glifo, (x, y + 2))
        s = s.filter(ImageFilter.GaussianBlur(2)).point(lambda v: v * 90 // 255)
        out.putalpha(s)
    corpo = Image.new('RGBA', (l, a), cor + (0,))
    m = Image.new('L', (l, a), 0)
    m.paste(glifo, (x, y))
    corpo.putalpha(m.point(lambda v: v * alfa // 255))
    out.alpha_composite(corpo)
    return out


def algarismo_grande(tam_caixa, txt):
    """Algarismo do relógio: branco translúcido com degradê e borda clara (vidro)."""
    l, a = tam_caixa
    base = texto(tam_caixa, txt, round(a * 0.98), largura_max=l - 6, sombra=True)
    # Degradê vertical: o vidro deixa passar mais cor embaixo.
    alfa = base.getchannel('A')
    corpo = base.copy()
    grad = Image.linear_gradient('L').resize((l, a)).point(lambda v: 255 - v * 70 // 255)
    r, g, b, _ = corpo.split()
    corpo = Image.merge('RGBA', (r, g, b, ImageChops.multiply(alfa, grad)))
    return corpo


def coracao(tam, cor=(255, 255, 255)):
    l, a = tam
    E = ESCALA
    m = Image.new('L', (l * E, a * E), 0)
    d = ImageDraw.Draw(m)
    w, h = l * E, a * E
    r = w * 0.26
    d.ellipse((w * 0.08, h * 0.12, w * 0.08 + 2 * r, h * 0.12 + 2 * r), fill=255)
    d.ellipse((w * 0.92 - 2 * r, h * 0.12, w * 0.92, h * 0.12 + 2 * r), fill=255)
    d.polygon([(w * 0.1, h * 0.42), (w * 0.9, h * 0.42), (w * 0.5, h * 0.9)], fill=255)
    return icone(m, tam, cor)


def chama(tam, cor=(255, 255, 255)):
    l, a = tam
    E = ESCALA
    w, h = l * E, a * E
    m = Image.new('L', (w, h), 0)
    d = ImageDraw.Draw(m)
    d.ellipse((w * 0.1, h * 0.3, w * 0.9, h * 0.98), fill=255)
    d.polygon([(w * 0.12, h * 0.6), (w * 0.5, h * 0.0), (w * 0.88, h * 0.6)], fill=255)
    d.ellipse((w * 0.33, h * 0.58, w * 0.67, h * 0.92), fill=0)
    return icone(m, tam, cor)


def passos(tam, cor=(255, 255, 255)):
    l, a = tam
    E = ESCALA
    w, h = l * E, a * E
    m = Image.new('L', (w, h), 0)
    d = ImageDraw.Draw(m)
    for (cx, cy) in ((0.3, 0.34), (0.7, 0.6)):
        d.ellipse((w * (cx - 0.19), h * (cy - 0.32), w * (cx + 0.19), h * (cy + 0.16)), fill=255)
        d.ellipse((w * (cx - 0.15), h * (cy + 0.2), w * (cx + 0.15), h * (cy + 0.38)), fill=255)
    return icone(m, tam, cor)


def raio_icone(tam, cor=(255, 255, 255)):
    l, a = tam
    E = ESCALA
    w, h = l * E, a * E
    m = Image.new('L', (w, h), 0)
    ImageDraw.Draw(m).polygon([(w * 0.58, h * 0.06), (w * 0.22, h * 0.56), (w * 0.48, h * 0.56),
                               (w * 0.4, h * 0.94), (w * 0.78, h * 0.42), (w * 0.52, h * 0.42)], fill=255)
    return icone(m, tam, cor)


def ponto_duplo(tam):
    l, a = tam
    E = ESCALA
    w, h = l * E, a * E
    m = Image.new('L', (w, h), 0)
    d = ImageDraw.Draw(m)
    r = w * 0.2
    for cy in (0.36, 0.66):
        d.ellipse((w / 2 - r, h * cy - r, w / 2 + r, h * cy + r), fill=255)
    return icone(m, tam, (255, 255, 255))


def icone(m4, tam, cor):
    m = m4.resize(tam, Image.LANCZOS)
    out = Image.new('RGBA', tam, (0, 0, 0, 0))
    s = Image.new('L', tam, 0)
    s.paste(m, (0, 1))
    out.putalpha(s.filter(ImageFilter.GaussianBlur(1.5)).point(lambda v: v * 80 // 255))
    corpo = Image.new('RGBA', tam, cor + (0,))
    corpo.putalpha(m)
    out.alpha_composite(corpo)
    return out


def botao_vidro(tam, txt, tam_fonte):
    """Botão de vidro sobre um painel já assado no fundo: só a borda e o texto."""
    l, a = tam
    out = borda(tam, a // 2, 230, 80, 2)
    out.alpha_composite(texto(tam, txt, tam_fonte))
    return out


def pontilhar_565(im):
    """Pontilhado ordenado (Bayer 4 × 4) antes de reduzir para RGB565, para os degradês não
    virarem faixas."""
    bayer = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5]
    l, a = im.size
    canais = []
    for passo, c in zip((8, 4, 8), im.convert('RGB').split()):
        tile = Image.new('L', (4, 4))
        tile.putdata([v * passo // 16 for v in bayer])
        desloc = Image.new('L', (l, a))
        for y in range(0, a, 4):
            for x in range(0, l, 4):
                desloc.paste(tile, (x, y))
        canais.append(ImageChops.add(c, desloc))
    return Image.merge('RGB', canais)
