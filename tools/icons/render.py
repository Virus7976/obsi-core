"""Makes an Obsi app's launcher icon from icon.html.

    python render.py tube  C:/path/to/app/src/main/res  [--preview out.png]

Writes:
  mipmap-<density>/ic_obsi_glass_fg.png   the adaptive-icon foreground, full bleed
  drawable/ic_obsi_glass_mono.xml         the one-colour layer for themed icons
  mipmap-anydpi-v26/ic_obsi_glass.xml     the adaptive icon that joins them
  drawable-nodpi/ic_obsi_glass_preview.png  a rounded preview for the in-app icon picker
Needs Chrome and Pillow. Nothing else.
"""
import os
import subprocess
import sys
import tempfile

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

# The same marks as icon.html, as solid shapes for the themed-icon layer: each cut line is a hole.
MONO = {
    "tube": "M-6,-8.5L9.5,0L-6,8.5Z",
    "read": "M-7.5,-9.5H7.5V9.5H-7.5Z M-4.6,-5.3V-3.7H4.6V-5.3Z M-4.6,-0.8V0.8H4.6V-0.8Z M-4.6,3.7V5.3H1.8V3.7Z",
    "bible": "M-8,-9.5H6Q8,-9.5 8,-7.5V9.5H-6Q-8,9.5 -8,7.5Z M-0.8,-6.8V5.8H0.8V-6.8Z M-4.6,-3V-1.4H4.6V-3Z",
    "learn": "M0,-8.5L11.5,-2.8L0,3L-11.5,-2.8Z M-6.8,0.6V5.6Q0,10.4 6.8,5.6V0.6L0,4Z M8.7,-1.8H10.3V6.2H8.7Z M11.3,7.3A1.8,1.8 0 1 1 7.7,7.3A1.8,1.8 0 1 1 11.3,7.3Z",
}
OFFSET = {"tube": (-1.2, 0), "read": (0, 0), "bible": (0, 0), "learn": (0, -1)}


def shot(app, mode, size, out):
    url = "file:///" + os.path.join(HERE, "icon.html").replace("\\", "/") + f"?app={app}&mode={mode}&px={size}"
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1", "--default-background-color=00000000",
                    f"--window-size={size + 64},{size + 240}", "--virtual-time-budget=1500", f"--screenshot={out}", url],
                   check=True, capture_output=True)
    # Chrome's page is a little shorter than its window, so the window is taller and the picture is cropped.
    im = Image.open(out).convert("RGBA").crop((0, 0, size, size))
    if im.getchannel("A").getbbox() is None:
        raise SystemExit("Chrome drew nothing.")
    return im


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def main():
    app, res = sys.argv[1], sys.argv[2]
    preview = sys.argv[sys.argv.index("--preview") + 1] if "--preview" in sys.argv else None
    tmp = tempfile.mkdtemp()
    fg = shot(app, "fg", 864, os.path.join(tmp, "fg.png"))
    for name, px in DENSITIES.items():
        d = os.path.join(res, f"mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        fg.resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_obsi_glass_fg.png"), optimize=True)

    tile = shot(app, "tile", 512, os.path.join(tmp, "tile.png"))
    nodpi = os.path.join(res, "drawable-nodpi")
    os.makedirs(nodpi, exist_ok=True)
    tile.resize((192, 192), Image.LANCZOS).save(os.path.join(nodpi, "ic_obsi_glass_preview.png"), optimize=True)
    if preview:
        tile.save(preview, optimize=True)

    dx, dy = OFFSET[app]
    s = 2.0
    write(os.path.join(res, "drawable", "ic_obsi_glass_mono.xml"), f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Made by obsi-core/tools/icons/render.py. The app mark alone, for themed icons. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <group android:translateX="{54 + dx * s:g}" android:translateY="{54 + dy * s:g}" android:scaleX="{s:g}" android:scaleY="{s:g}">
        <path android:fillColor="#FF000000" android:fillType="evenOdd" android:pathData="{MONO[app]}" />
    </group>
</vector>
""")
    write(os.path.join(res, "mipmap-anydpi-v26", "ic_obsi_glass.xml"), """<?xml version="1.0" encoding="utf-8"?>
<!-- Made by obsi-core/tools/icons/render.py. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/obsi_glass_bg" />
    <foreground android:drawable="@mipmap/ic_obsi_glass_fg" />
    <monochrome android:drawable="@drawable/ic_obsi_glass_mono" />
</adaptive-icon>
""")
    write(os.path.join(res, "values", "obsi_glass.xml"), """<?xml version="1.0" encoding="utf-8"?>
<!-- Made by obsi-core/tools/icons/render.py. Under the foreground, which covers it. -->
<resources>
    <color name="obsi_glass_bg">#FF140A2E</color>
</resources>
""")
    print(app, "icon written to", res)


if __name__ == "__main__":
    main()
