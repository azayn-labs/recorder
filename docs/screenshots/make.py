"""Builds the README mockups from raw on-device screenshots (Galaxy Z Fold 7 cover screen, 1080x2520).

    python3 docs/screenshots/make.py <raw-dir> <out-dir>

Raw captures are taken with fictional sample data; the status bar is repainted so no real
notifications, carrier names or times leak into the images.
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

RAW, OUT = Path(sys.argv[1]), Path(sys.argv[2])
ROOT = Path(__file__).resolve().parents[2]
FONT = ROOT / "android/app/src/main/res/font/inter.ttf"
LOGO = ROOT / "docs/logo.png"
STATUS_H = 102  # status bar inset on the cover screen, px

# Azayn light palette (see android/.../Theme.kt)
GROUND = (237, 239, 245)
TEXT = (26, 29, 41)
TEXT2 = (74, 79, 99)
BLUE = (47, 155, 255)
INDIGO = (79, 70, 229)
VIOLET = (124, 58, 237)


def font(size, weight="Bold"):
    f = ImageFont.truetype(str(FONT), size)
    f.set_variation_by_name(weight)
    return f


def lerp(a, b, t):
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def ground(w, h):
    """Cool grey ground with the blue -> violet aurora wash pooling at the top."""
    img = Image.new("RGB", (w, h), GROUND)
    wash = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(wash)
    top = int(h * 0.55)
    for y in range(top):
        t = y / top
        c = lerp(BLUE, VIOLET, min(t * 1.4, 1))
        a = round(80 * (1 - t) ** 1.6)
        d.line([(0, y), (w, y)], fill=c + (a,))
    img.paste(wash, (0, 0), wash)
    return img


def clean_status_bar(shot):
    """Repaint the status bar: extend the app's own background up, then draw a neutral 12:00."""
    im = shot.convert("RGB").copy()
    w = im.width
    px = im.load()
    # The app's ground only varies top to bottom, so sample the empty left margin just below the
    # bar and extend that gradient up, one flat colour per row (no content can leak in).
    a, b = px[8, STATUS_H + 2], px[8, STATUS_H + 30]
    d = ImageDraw.Draw(im)
    for y in range(STATUS_H + 2):
        t = (STATUS_H + 2 - y) / 28
        c = tuple(max(0, min(255, round(ca + (ca - cb) * t * 0.35))) for ca, cb in zip(a, b))
        d.line([(0, y), (w, y)], fill=c)
    lum = sum(px[w // 2, STATUS_H // 2]) / 3
    ink = (245, 246, 250) if lum < 128 else (26, 29, 41)
    d.text((44, STATUS_H // 2), "12:00", font=font(38, "SemiBold"), fill=ink, anchor="lm")
    # wifi: three arcs; battery: rounded body + cap
    cx, cy = w - 170, STATUS_H // 2 + 14
    for r in (30, 20, 10):
        d.arc([cx - r, cy - r, cx + r, cy + r], 225, 315, fill=ink, width=6)
    d.ellipse([cx - 4, cy - 4, cx + 4, cy + 4], fill=ink)
    bx, by = w - 118, STATUS_H // 2 - 13
    d.rounded_rectangle([bx, by, bx + 56, by + 26], radius=7, outline=ink, width=4)
    d.rounded_rectangle([bx + 7, by + 7, bx + 49, by + 19], radius=3, fill=ink)
    d.rounded_rectangle([bx + 58, by + 8, bx + 63, by + 18], radius=2, fill=ink)
    return im


def phone(shot, width):
    """Screenshot inside a slim dark device frame with rounded corners and a soft shadow."""
    scale = width / shot.width
    sw, sh = width, round(shot.height * scale)
    screen = shot.resize((sw, sh), Image.LANCZOS)
    bezel = max(8, round(width * 0.028))
    r_screen = round(width * 0.075)
    fw, fh = sw + 2 * bezel, sh + 2 * bezel
    pad = round(width * 0.08)  # room for the shadow
    out = Image.new("RGBA", (fw + 2 * pad, fh + 2 * pad), (0, 0, 0, 0))
    shadow = Image.new("RGBA", out.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle(
        [pad, pad + round(width * 0.03), pad + fw, pad + fh + round(width * 0.03)], radius=r_screen + bezel, fill=(20, 24, 60, 70))
    shadow = shadow.filter(ImageFilter.GaussianBlur(round(width * 0.035)))
    out.alpha_composite(shadow)
    ImageDraw.Draw(out).rounded_rectangle([pad, pad, pad + fw, pad + fh], radius=r_screen + bezel, fill=(18, 20, 28, 255))
    mask = Image.new("L", (sw, sh), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, sw, sh], radius=r_screen, fill=255)
    out.paste(screen, (pad + bezel, pad + bezel), mask)
    return out


def wrap(text, f, width, d):
    words, lines, line = text.split(), [], ""
    for w in words:
        test = (line + " " + w).strip()
        if d.textlength(test, font=f) <= width:
            line = test
        else:
            lines.append(line)
            line = w
    lines.append(line)
    return lines


def card(title, caption, shots, name, w=1000, h=1700):
    """A feature tile: title and caption on top, one or two phones below."""
    img = ground(w, h).convert("RGBA")
    d = ImageDraw.Draw(img)
    tf, cf = font(58, "Bold"), font(32, "Regular")
    y = 80
    for line in wrap(title, tf, w - 140, d):
        d.text((w // 2, y), line, font=tf, fill=TEXT, anchor="ma")
        y += 70
    y += 10
    for line in wrap(caption, cf, w - 160, d):
        d.text((w // 2, y), line, font=cf, fill=TEXT2, anchor="ma")
        y += 44
    y += 30
    if len(shots) == 1:
        p = phone(shots[0], 500)
        img.alpha_composite(p, ((w - p.width) // 2, y - round(500 * 0.08)))
    else:
        ps = [phone(s, 420) for s in shots]
        gap = -40
        total = sum(p.width for p in ps) + gap * (len(ps) - 1)
        x = (w - total) // 2
        for i, p in enumerate(ps):
            img.alpha_composite(p, (x, y - round(420 * 0.08) + i * 60))
            x += p.width + gap
    if name:
        img.convert("RGB").save(OUT / name, optimize=True)
    return img


def notification_card(shade, name, w=1000, h=1700):
    """The live notification, cropped from the shade and shown large on its own."""
    img = ground(w, h).convert("RGBA")
    d = ImageDraw.Draw(img)
    tf, cf = font(58, "Bold"), font(32, "Regular")
    y = 80
    for line in wrap("Live notification while recording", tf, w - 140, d):
        d.text((w // 2, y), line, font=tf, fill=TEXT, anchor="ma")
        y += 70
    y += 10
    for line in wrap("Who and which app, a running timer, and Stop or Discard right from the shade. "
                     "It's an Android Live Update, pinned to the top of the shade.", cf, w - 160, d):
        d.text((w // 2, y), line, font=cf, fill=TEXT2, anchor="ma")
        y += 44
    # Crop just the notification card (no carrier names or other shade content).
    crop = shade.convert("RGB").crop((30, 296, 1050, 572))
    scale = (w - 120) / crop.width
    crop = crop.resize((w - 120, round(crop.height * scale)), Image.LANCZOS)
    mask = Image.new("L", crop.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, crop.width, crop.height], radius=round(crop.height * 0.28), fill=255)
    cy = y + (h - y - crop.height) // 2 - 40
    sh = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle([60, cy + 18, 60 + crop.width, cy + 18 + crop.height], radius=round(crop.height * 0.28), fill=(20, 24, 60, 90))
    img.alpha_composite(sh.filter(ImageFilter.GaussianBlur(24)))
    img.paste(crop, (60, cy), mask)
    img.convert("RGB").save(OUT / name, optimize=True)


def setup_card(shot, shade, name, w=1000, h=1700):
    """The on-phone setup screen, with the pairing-code notification floating over it."""
    img = card("No computer, no root",
               "A two-minute setup pairs the app with your phone's own Wireless debugging. "
               "Type the code right in the notification.", [shot], None, w, h)
    crop = shade.convert("RGB").crop((39, 296, 1041, 578))
    cw = w - 140
    crop = crop.resize((cw, round(crop.height * cw / crop.width)), Image.LANCZOS)
    r = round(crop.height * 0.22)
    mask = Image.new("L", crop.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, crop.width, crop.height], radius=r, fill=255)
    y = h - crop.height - 120
    sh = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle([70, y + 18, 70 + crop.width, y + 18 + crop.height], radius=r, fill=(20, 24, 60, 110))
    img.alpha_composite(sh.filter(ImageFilter.GaussianBlur(24)))
    img.paste(crop, (70, y), mask)
    img.convert("RGB").save(OUT / name, optimize=True)


def hero(shots, name, w=2000, h=1100):
    img = ground(w, h).convert("RGBA")
    d = ImageDraw.Draw(img)
    logo = Image.open(LOGO).convert("RGBA")
    logo.thumbnail((150, 150))
    x0 = 110
    img.alpha_composite(logo, (x0, 150))
    d.text((x0, 340), "Azayn Recorder", font=font(92, "ExtraBold"), fill=TEXT)
    d.text((x0, 455), "Every call on your phone, recorded automatically.", font=font(34, "Medium"), fill=TEXT2)
    bullets = [
        "Phone, WhatsApp, Instagram and Facebook calls",
        "No computer or root: two-minute setup",
        "Sorted by app and contact, renamable",
        "Player, voice recorder, live notification",
        "Light and dark, no ads, open source",
    ]
    y = 560
    for b in bullets:
        d.ellipse([x0, y + 14, x0 + 18, y + 32], fill=INDIGO)
        d.text((x0 + 40, y), b, font=font(34, "Regular"), fill=TEXT)
        y += 66
    ps = [phone(s, 330) for s in shots]
    xs = [1040, 1330, 1620]
    ys = [190, 100, 190]
    for i in (0, 2, 1):  # middle phone on top
        img.alpha_composite(ps[i], (xs[i] - 26, ys[i] - 26))
    img.convert("RGB").save(OUT / name, optimize=True)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    shades = {"05-notification-shade", "12-pairing-notification"}
    s = {p.stem: clean_status_bar(Image.open(p)) for p in RAW.glob("*.png") if p.stem not in shades}
    hero([s["01-recordings"], s["02-contact"], s["10-recordings-dark"]], "hero.png")
    card("Every call, sorted by app and contact",
         "Phone, WhatsApp, Instagram and Facebook calls land in one list, grouped by who you talked to.",
         [s["01-recordings"]], "01-recordings.png")
    card("Rename what matters",
         "Give an important call a real name. It keeps its date and stays easy to find in search.",
         [s["02-contact"]], "02-contact.png")
    card("Built-in player",
         "Seek, skip 10 seconds, rename, share or delete without leaving the app.",
         [s["03-player"]], "03-player.png")
    card("One-tap voice recorder",
         "The Record button sits in the middle of the bar. It keeps recording with the screen off.",
         [s["04-voice-recording"]], "04-voice-recorder.png")
    notification_card(Image.open(RAW / "05-notification-shade.png"), "05-notification.png")
    setup_card(s["09-setup"], Image.open(RAW / "12-pairing-notification.png"), "09-setup.png")
    card("Your rules",
         "Restart the recorder or let it start after a reboot, pause everything, or turn recording off per app.",
         [s["06-settings-top"]], "06-settings.png")
    card("Never record some people",
         "Pick contacts or type a name or username. Their calls are skipped.",
         [s["07-settings-mid"]], "07-never-record.png")
    card("Light and dark",
         "Follows your phone, or pick one in Settings. The Azayn look, shared with Health and Pay.",
         [s["01-recordings"], s["10-recordings-dark"]], "08-light-dark.png")


if __name__ == "__main__":
    main()
