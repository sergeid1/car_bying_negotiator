"""Root-only host step. Fetch an exact SSM parameter without printing its contents."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def render(values, target, source):
    secret = values.pop("ORIGIN_SECRET")
    if not re.fullmatch(r"[a-f0-9]{64}", secret):
        raise ValueError("Origin secret must be 32 random bytes encoded as hex")
    files = {
        "tls.crt": values.pop("TLS_CERTIFICATE"),
        "tls.key": values.pop("TLS_PRIVATE_KEY"),
        "nginx.conf": (source / "nginx.conf.template").read_text().replace("@ORIGIN_SECRET@", secret),
    }
    lines = []
    for key, value in values.items():
        if not re.fullmatch(r"[A-Z][A-Z0-9_]*", key) or not isinstance(value, str) or any(c in value for c in "\r\n\x00"):
            raise ValueError("Invalid runtime environment entry")
        lines.append(f"{key}={value}")
    files["backend.env"] = "\n".join(lines) + "\n"
    target.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(target, 0o700)
    for name, content in files.items():
        temporary = target / (name + ".new")
        with temporary.open("w", encoding="utf-8") as output:
            os.chmod(temporary, 0o600)
            output.write(content)
        temporary.replace(target / name)


if __name__ == "__main__":
    if os.geteuid() != 0 or len(sys.argv) != 3:
        sys.exit("Run as root: load-runtime.py REGION PARAMETER_NAME")
    os.umask(0o077)
    result = subprocess.run([
        "aws", "ssm", "get-parameter", "--region", sys.argv[1], "--name", sys.argv[2],
        "--with-decryption", "--query", "Parameter.Value", "--output", "text",
    ], capture_output=True, text=True)
    if result.returncode:
        sys.exit("Runtime parameter fetch failed; contents withheld")
    try:
        render(json.loads(result.stdout), Path("/run/otdog-pilot"), Path(__file__).resolve().parent)
    except Exception:
        sys.exit("Runtime configuration invalid; contents withheld")
