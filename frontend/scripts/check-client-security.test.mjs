import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";

const cases = [
  ["external HTML script", "index.html", '<script src="https://third-party.invalid/sdk.js"></script>'],
  ["unquoted HTML script", "index.html", '<script src=//third-party.invalid/sdk.js></script>'],
  ["encoded HTML origin", "index.html", '<script src="https&#58;//third-party.invalid/sdk.js"></script>'],
  ["inline script", "index.html", '<script>alert(1)</script>'],
  ["inline theme style", "index.html", '<style>html{color:red}</style>'],
  ["external stylesheet", "index.html", '<link rel="stylesheet" href="//third-party.invalid/theme.css">'],
  ["external CSS font", "bundle.css", '@font-face{src:url(https://third-party.invalid/font.woff2)}'],
  ["external CSS import", "bundle.css", '@import "https://third-party.invalid/theme.css";'],
  ["external bundle script", "bundle.js", 'import("https://third-party.invalid/sdk.js")'],
  ["external connection", "bundle.js", 'fetch("https://third-party.invalid/api")'],
  ["escaped connection", "bundle.js", 'fetch("\\x68ttps://third-party.invalid/api")'],
  ["template connection", "bundle.js", 'fetch(`https://third-party.invalid/${path}`)'],
  ["eval", "bundle.js", 'eval("code")'],
];

async function fixture(file, content) {
  const directory = await mkdtemp(join(tmpdir(), "client-security-"));
  try {
    await writeFile(join(directory, "index.html"), '<script src="/theme-init.js"></script><script type="module" src="/bundle.js"></script><link rel="stylesheet" href="/bundle.css">');
    await writeFile(join(directory, file), content);
    return spawnSync(process.execPath, ["scripts/check-client-security.mjs", directory], { encoding: "utf8" });
  } finally { await rm(directory, { recursive: true, force: true }); }
}
for (const [name, file, content] of cases) {
  test(`rejects ${name}`, async () => {
    const result = await fixture(file, content);
    assert.equal(result.status, 1, result.stdout + result.stderr);
    assert.match(result.stderr, /non-origin resource|inline script|inline style|dynamic code/);
  });
}
test("accepts self-hosted resources and framework identifiers", async () => {
  const result = await fixture("bundle.js", 'fetch("/api/v1/intake-options");const namespace="http://www.w3.org/2000/svg";const diagnostic="https://react.dev/errors/";');
  assert.equal(result.status, 0, result.stdout + result.stderr);
});
