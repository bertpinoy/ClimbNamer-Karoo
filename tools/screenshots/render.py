"""Renders the Karoo screen mockups in screens.html to PNGs in docs/screenshots.

Needs Google Chrome (headless) and Pillow:  python tools/screenshots/render.py
"""
import subprocess
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE: Path = Path(__file__).resolve().parent
OUT: Path = HERE.parent.parent / "docs" / "screenshots"
CHROME: str = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
SCREENS: list[tuple[str, str]] = [
    ("next", "Next climb in the data field"),
    ("alert", "Alert before the climb"),
    ("climbing", "On the climb"),
]
W: int = 480
H: int = 800


def render(name: str, profile: str) -> Path:
    out: Path = OUT / f"karoo-{name}.png"
    url: str = (HERE / "screens.html").as_uri() + f"?s={name}"
    subprocess.run(
        [CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", f"--user-data-dir={profile}",
         "--force-device-scale-factor=1", f"--window-size={W},{H}", f"--screenshot={out}", url],
        check=True, capture_output=True, timeout=60,
    )
    img: Image.Image = Image.open(out).convert("RGB").crop((0, 0, W, H))
    img.save(out)
    return out


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    name: str = "segoeuib.ttf" if bold else "segoeui.ttf"
    return ImageFont.truetype(str(Path(r"C:\Windows\Fonts") / name), size)


def overview(images: list[Path]) -> Path:
    """The three screens side by side in a device frame, with captions (for posts and the README)."""
    pad: int = 60
    gap: int = 50
    frame: int = 14
    top: int = 130
    cap: int = 90
    width: int = pad * 2 + len(images) * (W + 2 * frame) + (len(images) - 1) * gap
    height: int = top + H + 2 * frame + cap + 30
    canvas: Image.Image = Image.new("RGB", (width, height), (12, 18, 32))
    draw: ImageDraw.ImageDraw = ImageDraw.Draw(canvas)
    draw.text((pad, 40), "ClimbNamer", font=font(48, True), fill=(255, 107, 26))
    title_w: float = draw.textlength("ClimbNamer", font=font(48, True))
    draw.text((pad + title_w + 24, 56), "climb names on your Hammerhead Karoo", font=font(30), fill=(216, 221, 230))
    for i, (path, (_, caption)) in enumerate(zip(images, SCREENS)):
        x: int = pad + i * (W + 2 * frame + gap)
        draw.rounded_rectangle((x, top, x + W + 2 * frame, top + H + 2 * frame), radius=36, fill=(42, 42, 42))
        canvas.paste(Image.open(path), (x + frame, top + frame))
        tw: float = draw.textlength(caption, font=font(30))
        draw.text((x + frame + (W - tw) / 2, top + H + 2 * frame + 30), caption, font=font(30), fill=(216, 221, 230))
    out: Path = OUT / "karoo-overview.png"
    canvas.save(out)
    return out


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as profile:
        images: list[Path] = [render(name, profile) for name, _ in SCREENS]
    for p in images + [overview(images)]:
        print(p.relative_to(HERE.parent.parent), Image.open(p).size)


if __name__ == "__main__":
    main()
