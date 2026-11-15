"""Regenerate the compile-time client jar with Forge access transformers applied."""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
PY = sys.executable

MC = r"C:\Users\Administrator\Desktop\.minecraft"
CLIENT = os.path.join(MC, r"libraries\net\minecraft\client\1.21.11\client-1.21.11-official.jar")
UNIVERSAL = os.path.join(MC, r"libraries\net\minecraftforge\forge\1.21.11-61.0.6\forge-1.21.11-61.0.6-universal.jar")
FORGE_CLIENT = os.path.join(MC, r"libraries\net\minecraftforge\forge\1.21.11-61.0.6\forge-1.21.11-61.0.6-client.jar")
EVENTBUS = os.path.join(MC, r"libraries\net\minecraftforge\eventbus\7.0-beta.12\eventbus-7.0-beta.12.jar")

DST = os.path.join(ROOT, "build", "libs", "client-1.21.11-at.jar")
SPEC = os.path.join(HERE, "at_widen.json")

argv = [
    PY,
    os.path.join(HERE, "at_patch.py"),
    CLIENT,
    DST,
    SPEC,
    ";".join([UNIVERSAL, FORGE_CLIENT, EVENTBUS]),
]
print("running:", " ".join(argv))
proc = subprocess.run(argv, text=True, capture_output=True)
print(proc.stdout)
if proc.stderr:
    print(proc.stderr)
sys.exit(proc.returncode)
