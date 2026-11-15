"""Build helper: drives javac with a clean argument vector.

PowerShell mangles long/quoted argument lists here, so the compile is done from
Python where the argv is passed exactly as constructed.
"""
import os
import subprocess
import sys

ROOT = r"E:\xy001004\betterdisplay-1.21.11"
SRC = os.path.join(ROOT, "src", "main", "java")
RES = os.path.join(ROOT, "src", "main", "resources")
OUT = os.path.join(ROOT, "build", "classes")
CP_FILE = os.path.join(ROOT, "build", "classpath.txt")
JAVAC = r"C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot\bin\javac.exe"


def java_sources():
    files = []
    for base, _dirs, names in os.walk(SRC):
        for name in names:
            if name.endswith(".java"):
                files.append(os.path.join(base, name))
    return sorted(files)


def main():
    os.makedirs(OUT, exist_ok=True)
    cp = open(CP_FILE, encoding="utf-8").read().strip()
    patched = os.path.join(ROOT, "build", "libs", "client-1.21.11-at.jar")
    if os.path.exists(patched):
        cp = patched + ";" + cp
    sources = java_sources()
    argv = [
        JAVAC,
        "-J-Duser.language=en",
        "-J-Duser.country=US",
        "-encoding", "UTF-8",
        "-proc:none",
        "--release", "21",
        "-Xlint:-options",
        "-Xmaxerrs", "200",
        "-cp", cp,
        "-d", OUT,
    ] + sources
    print(f"javac {len(sources)} files")
    proc = subprocess.run(argv, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if proc.stdout:
        print(proc.stdout)
    if proc.stderr:
        print(proc.stderr)
    print(f"EXIT={proc.returncode}")
    return proc.returncode


if __name__ == "__main__":
    sys.exit(main())
