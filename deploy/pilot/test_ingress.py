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
            try:
                context = ssl._create_unverified_context()  # Synthetic self-signed local fixture only.

                def status(secret):
                    headers = {} if secret is None else {"X-Origin-Verify": secret}
                    request = urllib.request.Request(f"https://127.0.0.1:{port}/api/v1/test", headers=headers)
                    try:
                        with urllib.request.urlopen(request, context=context, timeout=2) as response:
                            return response.status, response.headers
                    except urllib.error.HTTPError as error:
                        return error.code, error.headers

                for attempt in range(50):
                    try:
                        result = status(None)
                        break
                    except (urllib.error.URLError, ConnectionError):
                        time.sleep(0.1)
                else:
                    logs = subprocess.run(["docker", "logs", name], capture_output=True, text=True)
                    self.fail("Local ingress did not start: " + logs.stdout + logs.stderr)
                self.assertEqual(result[0], 403)
                self.assertEqual(status("forged")[0], 403)
                accepted, headers = status("a" * 64)
                self.assertEqual(accepted, 502)  # Accepted header reaches deliberately absent upstream.
                self.assertEqual(headers.get("Cache-Control"), "no-store")
            finally:
                subprocess.run(["docker", "rm", "--force", name], check=True, capture_output=True)


if __name__ == "__main__":
    unittest.main()
