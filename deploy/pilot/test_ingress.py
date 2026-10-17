"""Local Docker test with synthetic TLS only; no cloud account access."""
from pathlib import Path
import socket
import ssl
import subprocess
import tempfile
import time
import unittest
import urllib.error
import urllib.request
import uuid

from test_runtime import runtime, source


class IngressTest(unittest.TestCase):
    def test_origin_secret_is_required_before_proxying(self):
        name = "otdog-ingress-test-" + uuid.uuid4().hex[:12]
        with tempfile.TemporaryDirectory(prefix="otdog-ingress-") as directory:
            target = Path(directory)
            subprocess.run([
                "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                "-subj", "/CN=localhost", "-keyout", str(target / "key.pem"),
                "-out", str(target / "cert.pem"),
            ], check=True, capture_output=True)
            runtime.render({
                "ORIGIN_SECRET": "a" * 64,
                "TLS_CERTIFICATE": (target / "cert.pem").read_text(),
                "TLS_PRIVATE_KEY": (target / "key.pem").read_text(),
            }, target / "runtime", source)
            with socket.socket() as sock:
                sock.bind(("127.0.0.1", 0))
                port = sock.getsockname()[1]
            args = [
                "docker", "run", "-d", "--name", name,
                "-p", f"127.0.0.1:{port}:443",
                "-v", f"{target / 'runtime/nginx.conf'}:/etc/nginx/nginx.conf:ro",
                "-v", f"{target / 'runtime/tls.crt'}:/run/tls.crt:ro",
                "-v", f"{target / 'runtime/tls.key'}:/run/tls.key:ro",
                "-v", f"{source / 'proxy.inc'}:/run/otdog-proxy.inc:ro",
                "nginx:1.28.0",
            ]
            subprocess.run(args, check=True, capture_output=True)
            # A header-echo upstream on the proxy's own loopback stands in for the service.
            (target / "echo.conf").write_text(
                "events {}\nhttp { server { listen 127.0.0.1:8080; location / { return 200 "
                "'address=$http_x_otdog_client_address;verify=$http_x_origin_verify;"
                "forwarded=$http_x_forwarded_for$http_forwarded'; } } }\n")
            subprocess.run([
                "docker", "run", "-d", "--name", name + "-echo", "--network", "container:" + name,
                "-v", f"{target / 'echo.conf'}:/etc/nginx/nginx.conf:ro", "nginx:1.28.0",
            ], check=True, capture_output=True)
            try:
                context = ssl._create_unverified_context()  # Synthetic self-signed local fixture only.

                def status(secret, extra=None):
                    headers = {} if secret is None else {"X-Origin-Verify": secret}
                    headers.update(extra or {})
                    request = urllib.request.Request(f"https://127.0.0.1:{port}/api/v1/test", headers=headers)
                    try:
                        with urllib.request.urlopen(request, context=context, timeout=2) as response:
                            return response.status, response.headers, response.read().decode()
                    except urllib.error.HTTPError as error:
                        return error.code, error.headers, ""

                for attempt in range(50):
                    try:
                        result = status(None)[:2]
                        break
                    except (urllib.error.URLError, ConnectionError):
                        time.sleep(0.1)
                else:
                    logs = subprocess.run(["docker", "logs", name], capture_output=True, text=True)
                    self.fail("Local ingress did not start: " + logs.stdout + logs.stderr)
                self.assertEqual(result[0], 403)
                self.assertEqual(status("forged")[0], 403)
                visitor = {"X-Otdog-Client-Address": "203.0.113.5", "X-Forwarded-For": "192.0.2.1",
                           "Forwarded": "for=192.0.2.1"}
                self.assertEqual(status(None, visitor)[0], 403)
                self.assertEqual(status("forged", visitor)[0], 403)
                for attempt in range(50):
                    accepted, headers, body = status("a" * 64, visitor)
                    if accepted != 502:
                        break
                    time.sleep(0.1)
                self.assertEqual(accepted, 200)
                self.assertEqual(headers.get("Cache-Control"), "no-store")
                # The Worker's visitor address and secret pass; forwarding headers are cleared.
                self.assertEqual(body, "address=203.0.113.5;verify=" + "a" * 64 + ";forwarded=")
                self.assertEqual(status("a" * 64)[2], "address=;verify=" + "a" * 64 + ";forwarded=")
            finally:
                subprocess.run(["docker", "rm", "--force", name + "-echo", name], check=True, capture_output=True)


if __name__ == "__main__":
    unittest.main()
