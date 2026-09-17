#!/usr/bin/env python3
"""Exercise the packaged parser under container resource and access restrictions."""
import struct
import subprocess
import sys
import uuid
import zlib


def utf(value):
    data = value.encode("ascii")
    return struct.pack(">H", len(data)) + data


def chunk(name, data):
    return struct.pack(">I", len(data)) + name + data + struct.pack(">I", zlib.crc32(name + data))


image = sys.argv[1]
png = b"\x89PNG\r\n\x1a\n"
png += chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 2, 8, 6, 0, 0, 0))
png += chunk(b"IDAT", zlib.compress(bytes(18)))
png += chunk(b"IEND", b"")
request = utf("inspect")
request += struct.pack(">iiqiiqiq", 20 * 1024 * 1024, 25, 40_000_000, 1, 1, 10_000, 144, 40_000_000)
request += utf("heif-dec") + struct.pack(">qii", 10_000, 0, len(png)) + png
container_name = "ticket20-parser-check-" + uuid.uuid4().hex
command = [
    "docker", "run", "--rm", "--name", container_name, "-i", "--network", "none", "--read-only",
    "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--memory", "768m",
    "--cpus", "1", "--pids-limit", "64", "--tmpfs", "/tmp:rw,noexec,nosuid,size=128m",
    "--entrypoint", "java", image, "-Xmx128m", "-Djava.awt.headless=true", "-Duser.home=/tmp",
    "-Dloader.main=com.dealanalyzer.document.DocumentParserWorker",
    "-cp", "/app/application.jar", "org.springframework.boot.loader.launch.PropertiesLauncher",
]
try:
    result = subprocess.run(command, input=request, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
except subprocess.TimeoutExpired:
    subprocess.run(["docker", "rm", "-f", container_name], stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL, timeout=10, check=False)
    raise SystemExit("Container parser smoke check exceeded its deadline")
expected = b"\0" + utf("image/png") + struct.pack(">iii", 1, 2, 2) + bytes(3)
if result.returncode != 0 or result.stdout != expected:
    raise SystemExit("Container parser smoke check failed; no valid bounded response received")
print("Container parser passed with network disabled, read-only root, no capabilities and resource quotas")
