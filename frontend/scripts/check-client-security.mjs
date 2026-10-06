import { readFile, readdir } from "node:fs/promises";
import { resolve, relative } from "node:path";

const root = resolve(process.argv[2] ?? "dist");
const origin = "https://buyer.invalid";
const failures = [];
const fail = (file, reason) => failures.push(`${relative(root, file)}: ${reason}`);
function local(file, value) {
  value = value.replace(/&#(?:x([\da-f]+)|(\d+));?/gi,
    (_all, hex, decimal) => String.fromCodePoint(parseInt(hex ?? decimal, hex ? 16 : 10)))
    .replace(/&colon;/gi, ":").replace(/&sol;/gi, "/");
  try {
    if (new URL(value, origin).origin !== origin) fail(file, `non-origin resource: ${value}`);
  } catch { fail(file, `invalid resource URL: ${value}`); }
}

// React's namespace identifiers and diagnostic documentation are not resource requests.
// Keep this list narrow; do not exempt arbitrary URLs belonging to a vendor.
const identifiers = new Set([
  "http://www.w3.org/1999/xhtml", "http://www.w3.org/2000/svg",
  "http://www.w3.org/1998/Math/MathML", "http://www.w3.org/1999/xlink",
  "http://www.w3.org/XML/1998/namespace",
]);
async function inspect(directory) {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const file = resolve(directory, entry.name);
    if (entry.isDirectory()) { await inspect(file); continue; }
    if (!/\.(html|css|js)$/.test(file)) continue;
    const source = await readFile(file, "utf8");
    if (file.endsWith(".html")) {
      for (const match of source.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)) {
        if (!/\bsrc\s*=/i.test(match[1]) || match[2].trim()) fail(file, "inline script");
      }
      if (/<style\b|\sstyle\s*=|\son\w+\s*=/i.test(source)) fail(file, "inline style or event handler");
      for (const match of source.matchAll(/<(?:script|link|img|iframe|base)\b[^>]*?\b(?:src|href)\s*=\s*(?:"([^"]+)"|'([^']+)'|([^\s>]+))/gi)) local(file, match[1] ?? match[2] ?? match[3]);
    } else if (file.endsWith(".css")) {
      for (const match of source.matchAll(/url\(\s*["']?([^\s"')]+)|@import\s+["']([^"']+)["']/gi)) local(file, match[1] ?? match[2]);
    } else {
      // Vite emits string literals for imports, resource URLs and API URL prefixes.
      // Decode escaped scheme characters without executing bundle content.
      for (const match of source.matchAll(/"(?:\\[\s\S]|[^"\\])*"|'(?:\\[\s\S]|[^'\\])*'|`(?:\\[\s\S]|[^`\\])*`/g)) {
        const value = match[0].slice(1, -1)
          .replace(/\\u\{([\da-f]+)\}|\\u([\da-f]{4})|\\x([\da-f]{2})/gi,
            (_all, point, unicode, hex) => String.fromCodePoint(parseInt(point ?? unicode ?? hex, 16)))
          .replace(/\\([/:'"`])/g, "$1");
        if (/^(?:[a-z][a-z\d+.-]*:|\/\/)/i.test(value)
            && !identifiers.has(value) && value !== "https://react.dev/errors/"
            // Download text links do not execute scripts or make network connections.
            && !value.startsWith("data:text/plain;charset=utf-8,")) local(file, value);
      }
      if (/\beval\s*\(|\bnew\s+Function\s*\(/.test(source)) fail(file, "dynamic code execution");
    }
  }
}
await readFile(resolve(root, "index.html")); // A missing build must fail, not check an empty directory.
await inspect(root);
if (failures.length) {
  console.error(failures.join("\n"));
  process.exitCode = 1;
} else console.log("Built client security check passed (same-origin resources, external scripts/styles).");
