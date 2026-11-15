"""Package compiled classes + resources into a loadable mod jar."""
import os
import sys
import zipfile

ROOT = r"E:\xy001004\betterdisplay-1.21.11"
CLASSES = os.path.join(ROOT, "build", "classes")
RESOURCES = os.path.join(ROOT, "src", "main", "resources")
OUT_DIR = os.path.join(ROOT, "dist")
OUT_JAR = os.path.join(OUT_DIR, "betterdisplay-1.21.11-1.2.3-mcui.jar")

# Entries that must not ship (editor backups, nested archives, stale copies).
SKIP_SUFFIXES = (".old", ".old.png", ".rar", ".zip")


def add_tree(zf, base, prefix=""):
    for dirpath, _dirs, files in os.walk(base):
        for name in files:
            if name.lower().endswith(SKIP_SUFFIXES):
                continue
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, base).replace("\\", "/")
            # nested archives are template leftovers, never loaded at runtime
            if name.endswith(".rar") or name.endswith(".zip"):
                continue
            zf.write(full, prefix + rel)


def main():
    if not os.path.isdir(CLASSES):
        print(f"missing classes dir: {CLASSES}")
        return 1
    os.makedirs(OUT_DIR, exist_ok=True)
    if os.path.exists(OUT_JAR):
        os.remove(OUT_JAR)
    with zipfile.ZipFile(OUT_JAR, "w", zipfile.ZIP_DEFLATED) as zf:
        add_tree(zf, CLASSES)
        add_tree(zf, RESOURCES)
    with zipfile.ZipFile(OUT_JAR) as zf:
        names = zf.namelist()
    print(f"wrote {OUT_JAR}")
    print(f"entries: {len(names)}")
    for key in ("META-INF/mods.toml", "pack.mcmeta", "com/daoyou/betterdisplay/BetterDisplayMod.class",
                "com/daoyou/betterdisplay/RemoteUiRenderer.class",
                "assets/betterdisplay/textures/gui/profilebg.png"):
        print(f"  {'OK ' if key in names else 'MISSING'} {key}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
