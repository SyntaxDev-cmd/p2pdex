#!/usr/bin/env python3
"""
Laboratório: aplica a marca de um cliente/revenda num dos apps antes de compilar.

  python3 tools/lab_brand.py --app android --logo logo.png [--icon icone.png] [--background fundo.jpg]
         [--panel-url https://...] [--panel-key X] [--reseller CODIGO] [--color "#1E88E5"]

- logo: aparece no login/topo (res/drawable-nodpi/app_logo.png)
- icon: ícone do app (mipmaps) — se não vier, usa a logo
- banner da Android TV (320x180) gerado com a logo centralizada
- background: fundo do app (res/drawable-nodpi/app_background.jpg)
- painel/chave/código de revenda gravados em assets/config.json (editável depois com MT Manager)
Só mexe nos arquivos de marca; o código do app não é alterado.
"""
import argparse, json, os, sys
from PIL import Image, ImageOps

def open_img(path):
    im = Image.open(path)
    im = ImageOps.exif_transpose(im)
    return im.convert("RGBA")

def fit(im, w, h, pad=0.0):
    """Encaixa a imagem (sem cortar) num quadro w x h transparente."""
    bw, bh = int(w * (1 - 2 * pad)), int(h * (1 - 2 * pad))
    c = im.copy(); c.thumbnail((bw, bh), Image.LANCZOS)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.paste(c, ((w - c.width) // 2, (h - c.height) // 2), c)
    return out

def clear(folder, name):
    """Remove app_logo.* / app_background.* antigos (2 extensões = erro de recurso duplicado)."""
    for f in os.listdir(folder):
        if os.path.splitext(f)[0] == name: os.remove(os.path.join(folder, f))

def hex_rgb(s):
    s = s.strip().lstrip("#")
    if len(s) != 6: raise ValueError("cor deve ser #RRGGBB")
    return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--app", required=True, help="pasta do projeto: android ou sports/android")
    ap.add_argument("--logo"); ap.add_argument("--icon"); ap.add_argument("--background")
    ap.add_argument("--panel-url", default=""); ap.add_argument("--panel-key", default="")
    ap.add_argument("--reseller", default=""); ap.add_argument("--color", default="#0B1020")
    a = ap.parse_args()
    res = os.path.join(a.app, "app/src/main/res")
    assets = os.path.join(a.app, "app/src/main/assets")
    if not os.path.isdir(res): sys.exit(f"projeto não encontrado: {a.app}")
    bg = hex_rgb(a.color) + (255,)

    if a.logo:
        logo = open_img(a.logo)
        l = logo.copy(); l.thumbnail((1024, 1024), Image.LANCZOS)
        clear(os.path.join(res, "drawable-nodpi"), "app_logo")
        l.save(os.path.join(res, "drawable-nodpi/app_logo.png"), optimize=True)
        banner = Image.new("RGBA", (320, 180), bg)
        b = fit(logo, 320, 180, pad=0.12); banner.paste(b, (0, 0), b)
        banner.save(os.path.join(res, "drawable-xhdpi/banner.png"), optimize=True)
        print("✓ logo e banner da TV")

    src = a.icon or a.logo
    if src:
        icon = open_img(src)
        for d, s in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
            canvas = Image.new("RGBA", (s, s), (0, 0, 0, 0) if a.icon else bg)
            i = fit(icon, s, s, pad=0.0 if a.icon else 0.1); canvas.paste(i, (0, 0), i)
            canvas.save(os.path.join(res, f"mipmap-{d}/ic_launcher.png"), optimize=True)
        print("✓ ícones")

    if a.background:
        im = open_img(a.background).convert("RGB")
        im.thumbnail((1920, 1920), Image.LANCZOS)
        clear(os.path.join(res, "drawable-nodpi"), "app_background")
        im.save(os.path.join(res, "drawable-nodpi/app_background.jpg"), quality=85, optimize=True)
        print("✓ fundo")

    cfg_path = os.path.join(assets, "config.json")
    cfg = {}
    if os.path.exists(cfg_path):
        with open(cfg_path, encoding="utf-8") as f: cfg = json.load(f)
    if a.panel_url: cfg["panel_url"] = a.panel_url.strip().rstrip("/")
    if a.panel_key: cfg["panel_key"] = a.panel_key.strip()
    if a.reseller: cfg["reseller_code"] = a.reseller.strip().upper()
    os.makedirs(assets, exist_ok=True)
    with open(cfg_path, "w", encoding="utf-8") as f: json.dump(cfg, f, ensure_ascii=False, indent=2)
    print("✓ config.json:", {k: v for k, v in cfg.items() if k in ("panel_url", "reseller_code")})

if __name__ == "__main__":
    main()
