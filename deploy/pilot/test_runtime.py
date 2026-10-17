import importlib.util
from pathlib import Path
import tempfile
import unittest

source = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("runtime", source / "load-runtime.py")
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class RuntimeTest(unittest.TestCase):
    def values(self):
        return {
            "ORIGIN_SECRET": "a" * 64, "TLS_CERTIFICATE": "certificate\n",
            "TLS_PRIVATE_KEY": "private\n", "DATABASE_PASSWORD": "literal $ # ' quotes",
        }

    def test_secrets_are_separated_and_private(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "runtime"
            runtime.render(self.values(), target, source)
            self.assertEqual(target.stat().st_mode & 0o777, 0o700)
            for file in target.iterdir():
                self.assertEqual(file.stat().st_mode & 0o777, 0o600)
            self.assertEqual((target / "backend.env").read_text(),
                             "DATABASE_PASSWORD=literal $ # ' quotes\nORIGIN_SECRET=" + "a" * 64 + "\n")
            self.assertIn('"' + "a" * 64 + '" 1;', (target / "nginx.conf").read_text())
            self.assertNotIn("@ORIGIN_SECRET@", (target / "nginx.conf").read_text())

    def test_header_injection_and_env_injection_fail_before_writes(self):
        for key, value in [("ORIGIN_SECRET", '"; injected'), ("DATABASE_PASSWORD", "value\nSERVER_PORT=80")]:
            with tempfile.TemporaryDirectory() as directory:
                values = self.values()
                values[key] = value
                target = Path(directory) / "runtime"
                with self.assertRaises(ValueError):
                    runtime.render(values, target, source)
                self.assertFalse(target.exists())


if __name__ == "__main__":
    unittest.main()
