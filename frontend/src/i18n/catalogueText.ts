import { en } from "./en";
import { es } from "./es";

/**
 * Test support (ticket 146): reads the English and Spanish catalogues side by side, so the browser gate can run its
 * English-written journeys in Spanish and can tell when an English catalogue string reaches a Spanish screen.
 * Nothing in the app imports this module.
 */
export type Pair = { path: string; en: string; es: string };

function walk(english: unknown, spanish: unknown, path: string, out: Pair[]) {
  if (typeof english === "string") {
    out.push({ path, en: english, es: typeof spanish === "string" ? spanish : english });
    return;
  }
  if (!english || typeof english !== "object") return;
  const e = english as Record<string, unknown>;
  const s = (spanish ?? {}) as Record<string, unknown>;
  if (e.kind === "plural" && e.forms) {
    const ef = e.forms as Record<string, string>;
    const sf = ((s.forms ?? {}) as Record<string, string>);
    for (const [category, text] of Object.entries(ef)) walk(text, sf[category] ?? sf.other, `${path}.${category}`, out);
    return;
  }
  for (const key of Object.keys(e)) walk(e[key], s[key], path ? `${path}.${key}` : key, out);
}

export const pairs: readonly Pair[] = (() => { const out: Pair[] = []; walk(en, es, "", out); return out; })();

const PLACEHOLDER = /\{(\w+)\}/g;
const escape = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
const normalize = (text: string) => text.replace(/\s+/g, " ").trim();

/** How much of a message is fixed wording; a message that is mostly placeholders matches almost anything. */
function literalLetters(template: string) {
  return template.replace(PLACEHOLDER, "").replace(/[^\p{L}]/gu, "").length;
}

type Template = { pair: Pair; names: string[]; pattern: RegExp };

function templateOf(text: string, flags = "") {
  const names: string[] = [];
  let source = "";
  let last = 0;
  for (const match of text.matchAll(PLACEHOLDER)) {
    source += escape(text.slice(last, match.index)) + "(.+?)";
    names.push(match[1]);
    last = match.index + match[0].length;
  }
  source += escape(text.slice(last));
  return { names, pattern: new RegExp(`^${source}$`, flags) };
}

const exact = new Map<string, Set<string>>();
const exactIgnoringCase = new Map<string, Set<string>>();
const templates: Template[] = [];
const add = (map: Map<string, Set<string>>, key: string, value: string) => {
  if (!map.has(key)) map.set(key, new Set());
  map.get(key)!.add(value);
};
for (const pair of pairs) {
  if (/\{\w+\}/.test(pair.en)) {
    if (literalLetters(pair.en) >= 4) templates.push({ pair, ...templateOf(normalize(pair.en)) });
  } else {
    add(exact, normalize(pair.en), normalize(pair.es));
    add(exactIgnoringCase, normalize(pair.en).toLowerCase(), normalize(pair.es));
  }
}

/**
 * The Spanish texts an English text can be: a whole message, or a filled one whose values are translated too. A
 * filled message must be mostly its own wording, so a short frame such as "{a} from {b}" does not swallow a sentence.
 */
export function spanishFor(text: string, depth = 0, ignoreCase = false): string[] {
  const key = normalize(text);
  const found = new Set(exact.get(key) ?? (ignoreCase ? exactIgnoringCase.get(key.toLowerCase()) : undefined) ?? []);
  if (found.size === 0 && depth > 0) found.add(key);
  // A whole message wins over a frame that happens to fit it, as "Continue with email" over "Continue with {provider}".
  if (depth < 2 && !exact.has(key)) for (const { pair, names, pattern } of templates) {
    const match = (ignoreCase ? new RegExp(pattern.source, "i") : pattern).exec(key);
    if (!match) continue;
    // A frame open at both ends must be a fair share of the text; one fixed at either end is specific enough.
    const open = /^\{\w+\}/.test(pair.en) && /\{\w+\}$/.test(pair.en);
    if (open && literalLetters(pair.en) < key.replace(/[^\p{L}]/gu, "").length * 0.5) continue;
    let fills = [pair.es];
    names.forEach((name, index) => {
      // A value can be catalogue copy or data such as a state name the server sends, so both readings are kept.
      const values = [...new Set([...spanishFor(match[index + 1], depth + 1), match[index + 1]])].slice(0, 4);
      fills = fills.flatMap(partial => values.map(value => partial.replaceAll(`{${name}}`, value))).slice(0, 16);
    });
    for (const filled of fills) found.add(normalize(filled));
  }
  return depth > 0 ? [...found] : [...found].filter(candidate => candidate !== key || exact.has(key));
}

/** Whether a Spanish candidate still holds two lowercase English words in a row from the text it translates. */
function untranslated(candidate: string, english: string) {
  const words = normalize(english).split(" ");
  return words.some((word, index) => index > 0 && /^\p{Ll}{3,}$/u.test(word) && /^\p{Ll}{3,}$/u.test(words[index - 1])
    && candidate.includes(`${words[index - 1]} ${word}`));
}

const alternation = (texts: string[]) => texts.map(escape).join("|");
const openPlaceholders = (spanish: string) => escape(normalize(spanish)).replace(/\\\{\w+\\\}/g, ".+?");

/**
 * The messages an English fragment comes from, such as "Add more" or "Has campaign 21V001000 been repaired": first
 * the messages holding it word for word, then the messages whose fixed wording holds most of its words in order,
 * where the rest are a placeholder's value.
 */
function holdingFragment(fragment: string, atStart = false): Pair[] {
  const lower = normalize(fragment).toLowerCase();
  const direct = pairs.filter(pair => pair.en !== pair.es
    && (atStart ? normalize(pair.en).toLowerCase().startsWith(lower) : normalize(pair.en).toLowerCase().includes(lower)));
  if (direct.length > 0) return direct.length <= 12 ? direct : [];
  const words = lower.split(" ");
  let best = 0;
  let found: Pair[] = [];
  for (const pair of pairs) {
    if (!/\{\w+\}/.test(pair.en) || pair.en === pair.es) continue;
    const segments = normalize(pair.en).toLowerCase().split(/\{\w+\}/);
    // Words covered, in order, by runs of the message's fixed wording; a lone word counts only if it is a long one.
    let covered = 0;
    for (let start = 0; start < words.length;) {
      let length = 0;
      for (let end = words.length; end > start; end--) {
        const run = words.slice(start, end).join(" ");
        if ((end - start > 1 || run.length >= 5) && segments.some(segment => segment.includes(run))) { length = end - start; break; }
      }
      covered += length;
      start += Math.max(length, 1);
    }
    if (covered < Math.ceil(words.length / 2)) continue;
    if (covered > best) { best = covered; found = [pair]; } else if (covered === best) found.push(pair);
  }
  return found.length <= 12 ? found : [];
}

/**
 * A selector's English text in Spanish, keeping Playwright's matching rules: a string matches case-insensitively as a
 * substring, or whole when `exact`. Text that is not in the catalogue, such as a dealer name, is returned unchanged.
 */
export function translateSelector(text: string, whole = false, ignoreCase = !whole): string | RegExp {
  let candidates = spanishFor(text, 0, ignoreCase);
  // A text can also be a fragment of a longer message, as "page 1 looks rotated" is: a frame such as "Page {page}"
  // fits it whole but leaves its English words untranslated.
  const fragment = !whole && (candidates.length === 0 || candidates.every(candidate => untranslated(candidate, text)));
  const holding = fragment ? holdingFragment(text).map(pair => openPlaceholders(pair.es)) : [];
  candidates = candidates.filter(candidate => candidate !== normalize(text) || candidates.length === 1);
  if (holding.length > 0) return new RegExp([...candidates.map(escape), ...holding].join("|"), "i");
  if (candidates.length === 0) return text;
  if (candidates.length === 1) return candidates[0];
  return new RegExp(whole ? `^(?:${alternation(candidates)})$` : alternation(candidates), whole ? "" : "i");
}

/**
 * A selector's English pattern in Spanish. A pattern of plain words is looked up as a whole message or a fragment of
 * one; an alternation is translated branch by branch; other pattern syntax is read as a gap between words.
 */
export function translatePattern(pattern: RegExp): RegExp {
  const anchoredStart = pattern.source.startsWith("^");
  const anchoredEnd = pattern.source.endsWith("$") && !pattern.source.endsWith("\\$");
  const body = pattern.source.replace(/^\^/, "").replace(/(?<!\\)\$$/, "");
  const ignoreCase = pattern.flags.includes("i");
  const syntax = /[.*+?()|[\]{}]/.test(body.replace(/\\./g, ""));
  if (syntax && body.includes("|")) {
    const translated = body.replace(/(?:\\.|[^.*+?()|[\]{}^$\\])+/g, words => {
      const literal = words.replace(/\\(.)/g, "$1");
      if (!/\p{L}{3}/u.test(literal)) return words;
      const spanish = translateSelector(literal, false, ignoreCase);
      return typeof spanish === "string" ? escape(spanish) : `(?:${spanish.source})`;
    });
    return new RegExp(`${anchoredStart ? "^" : ""}${translated}${anchoredEnd ? "$" : ""}`, pattern.flags);
  }
  const literal = normalize((syntax ? body.replace(/\\[sdwb]|[.*+?()[\]{}]/g, " ") : body).replace(/\\(.)/g, "$1"));
  const whole = syntax ? [] : spanishFor(literal, 0, ignoreCase);
  const fragment = !anchoredEnd && (whole.length === 0 || whole.every(candidate => untranslated(candidate, literal)));
  const holding = fragment ? holdingFragment(literal, anchoredStart && !syntax) : [];
  if (holding.length === 0) {
    if (whole.length === 0) return pattern;
    return new RegExp(`${anchoredStart ? "^" : ""}(?:${alternation(whole)})${anchoredEnd ? "$" : ""}`, pattern.flags);
  }
  const options = holding.map(pair => {
    if (!anchoredStart) return openPlaceholders(pair.es);
    // The Spanish wording up to its first placeholder, so a prefix pattern stays a prefix.
    const spanish = normalize(pair.es);
    const stop = spanish.search(/\{\w+\}/);
    return escape(stop >= 0 ? spanish.slice(0, stop) : spanish);
  });
  return new RegExp(`${anchoredStart ? "^" : ""}(?:${[...whole.map(escape), ...options].join("|")})`, pattern.flags);
}

/**
 * The texts that are English catalogue copy, not Spanish: each one is a whole English message (filled or not) that no
 * Spanish message also reads as. Brand names, amounts and the glossary's English terms are Spanish messages too, so
 * they pass.
 */
export function englishCopy(texts: Iterable<string>): string[] {
  const spanishExact = new Set(pairs.map(pair => normalize(pair.es)));
  const spanishTemplates = pairs.filter(pair => /\{\w+\}/.test(pair.es)).map(pair => templateOf(normalize(pair.es)).pattern);
  const englishExact = new Set(pairs.map(pair => normalize(pair.en)).filter(text => /\p{L}{2}/u.test(text)));
  const found: string[] = [];
  for (const raw of texts) {
    const text = normalize(raw);
    if (!text || text.length > 600 || spanishExact.has(text)) continue;
    const english = englishExact.has(text) || templates.some(({ pattern }) => pattern.test(text));
    if (english && !spanishTemplates.some(pattern => pattern.test(text))) found.push(text);
  }
  return found;
}
