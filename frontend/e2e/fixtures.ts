import BaseAxeBuilder from "@axe-core/playwright";
import { expect as baseExpect, test as baseTest, type Locator, type Page } from "@playwright/test";
import { englishCopy, translatePattern, translateSelector } from "../src/i18n/catalogueText";

export type { Locator, Page } from "@playwright/test";

/**
 * The browser gate in more than one language (ticket 146). Each journey is written once, in English. The Spanish
 * projects open the site in Spanish and read every selector and text expectation through the catalogues, so the same
 * journey walks the Spanish screens. While it walks, every text the page shows is recorded, and the test fails if
 * any of it is English catalogue copy.
 */
export function language(): "en" | "es" {
  return baseTest.info().project.metadata?.language === "es" ? "es" : "en";
}

const spanish = () => language() === "es";

const escape = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/**
 * English text in the page's language. The English stays as an alternative, because some text on a Spanish screen is
 * still English by design: what the backend sends (findings are ticket 147's), and what the Buyer or dealer wrote.
 * The English-copy check below is what fails when catalogue copy shows up in English.
 */
function translate(value: unknown, whole = false): unknown {
  if (!spanish()) return value;
  if (typeof value === "string") {
    const spanishValue = translateSelector(value, whole);
    if (spanishValue === value) return value;
    const english = whole ? `^${escape(value.replace(/\s+/g, " ").trim())}$` : escape(value);
    return new RegExp(`${english}|${typeof spanishValue === "string" ? (whole ? `^${escape(spanishValue)}$` : escape(spanishValue)) : spanishValue.source}`,
      whole ? "" : "i");
  }
  if (value instanceof RegExp) {
    const spanishPattern = translatePattern(value);
    return spanishPattern.source === value.source ? value : new RegExp(`(?:${value.source})|(?:${spanishPattern.source})`, value.flags);
  }
  return value;
}

function withName(options: unknown) {
  if (!options || typeof options !== "object") return options;
  const given = options as Record<string, unknown>;
  const out = { ...given };
  if ("name" in given) out.name = translate(given.name, Boolean(given.exact));
  if ("hasText" in given) out.hasText = translate(given.hasText);
  if ("hasNotText" in given) out.hasNotText = translate(given.hasNotText);
  return out;
}

/** English copy inside a CSS attribute selector, such as `[aria-label='Buyer Account']`, read in the page's language. */
function translateCss(selector: unknown) {
  if (!spanish() || typeof selector !== "string") return selector;
  return selector.replace(/\[(aria-label|title|alt|placeholder)=(['"])(.*?)\2\]/g, (whole, name: string, quote: string, value: string) => {
    const spanishValue = translateSelector(value, true);
    if (typeof spanishValue === "string") return `[${name}=${quote}${spanishValue}${quote}]`;
    const options = spanishValue.source.replace(/^\^\(\?:|\)\$$/g, "").split("|").map(option => option.replace(/\\(.)/g, "$1"));
    return `:is(${options.map(option => `[${name}=${quote}${option}${quote}]`).join(", ")})`;
  });
}

/** `@axe-core/playwright`, with its include and exclude selectors read in the page's language. */
export class AxeBuilder extends BaseAxeBuilder {
  include(selector: Parameters<BaseAxeBuilder["include"]>[0]) { return super.include(translateCss(selector) as typeof selector); }
  exclude(selector: Parameters<BaseAxeBuilder["exclude"]>[0]) { return super.exclude(translateCss(selector) as typeof selector); }
}

const TEXT_QUERIES = new Set(["getByText", "getByLabel", "getByPlaceholder", "getByTitle", "getByAltText"]);

function translatedArguments(method: string, args: unknown[]) {
  if (!spanish()) return args;
  if (method === "getByRole") return [args[0], withName(args[1]), ...args.slice(2)];
  if (TEXT_QUERIES.has(method)) {
    const options = args[1] as { exact?: boolean } | undefined;
    return [translate(args[0], Boolean(options?.exact)), ...args.slice(1)];
  }
  if (method === "filter") return [withName(args[0])];
  if (method === "locator") return [translateCss(args[0]), withName(args[1])];
  return args;
}

/** A locator, or a frame locator: anything a chained query can start from. Bundling renames the classes, so check the shape. */
const isLocator = (value: unknown) => typeof value === "object" && value !== null && !(value instanceof Promise)
  && typeof (value as { getByRole?: unknown }).getByRole === "function";

function wrap<T extends object>(target: T): T {
  return new Proxy(target, {
    get(object, property, receiver) {
      const value = Reflect.get(object, property, receiver);
      if (typeof value !== "function" || typeof property !== "string") return value;
      return (...args: unknown[]) => {
        const result = value.apply(object, translatedArguments(property, args));
        return isLocator(result) ? wrap(result as object) : result;
      };
    },
  });
}

/** A page a spec opens itself, such as a second browser context's, read in the project's language like `page`. */
export function localized<T extends Page>(page: T): T {
  return spanish() ? wrap(page) : page;
}

const WHOLE_TEXT = new Set(["toHaveText", "toHaveAccessibleName", "toHaveAccessibleDescription", "toHaveTitle"]);
const PART_TEXT = new Set(["toContainText"]);
const COPY_ATTRIBUTES = new Set(["aria-label", "title", "alt", "placeholder", "aria-description"]);

function translatedExpectation(matcher: string, args: unknown[]) {
  if (!spanish()) return args;
  const each = (value: unknown, whole: boolean) => Array.isArray(value) ? value.map(item => translate(item, whole)) : translate(value, whole);
  if (WHOLE_TEXT.has(matcher)) return [each(args[0], true), ...args.slice(1)];
  if (PART_TEXT.has(matcher)) return [each(args[0], false), ...args.slice(1)];
  if (matcher === "toHaveAttribute" && COPY_ATTRIBUTES.has(String(args[0])) && args.length > 1) return [args[0], translate(args[1], true), ...args.slice(2)];
  return args;
}

function wrapMatchers<T extends object>(matchers: T): T {
  return new Proxy(matchers, {
    get(object, property, receiver) {
      const value = Reflect.get(object, property, receiver);
      if (property === "not" || property === "resolves" || property === "rejects") return wrapMatchers(value as object);
      if (typeof value !== "function" || typeof property !== "string") return value;
      return (...args: unknown[]) => value.apply(object, translatedExpectation(property, args));
    },
  });
}

export const expect = new Proxy(baseExpect, {
  apply(target, self, args: Parameters<typeof baseExpect>) {
    return wrapMatchers(Reflect.apply(target, self, args) as object);
  },
}) as typeof baseExpect;

/** Records each text a Spanish page shows, as text content or a copy attribute, for the English-copy check. */
function recordTexts() {
  const sent = new Set<string>();
  let queued = false;
  const report = (window as unknown as { __otdogSeen?: (texts: string[]) => void }).__otdogSeen;
  function snapshot() {
    queued = false;
    const fresh: string[] = [];
    const add = (raw: string | null) => {
      const text = raw?.replace(/\s+/g, " ").trim();
      if (text && text.length <= 600 && !sent.has(text)) { sent.add(text); fresh.push(text); }
    };
    for (const element of Array.from(document.querySelectorAll("body *"))) {
      if (element instanceof HTMLScriptElement || element instanceof HTMLStyleElement) continue;
      add(element.textContent);
      for (const name of ["aria-label", "title", "alt", "placeholder", "aria-description"]) add(element.getAttribute(name));
    }
    add(document.title);
    if (fresh.length > 0) report?.(fresh);
  }
  const schedule = () => { if (!queued) { queued = true; setTimeout(snapshot, 120); } };
  new MutationObserver(schedule).observe(document, { subtree: true, childList: true, characterData: true, attributes: true });
  document.addEventListener("DOMContentLoaded", schedule);
}

export const test = baseTest.extend<{ englishCheck: void }>({
  page: async ({ page }, use) => {
    await use(localized(page));
  },
  englishCheck: [async ({ page }, use) => {
    if (!spanish()) { await use(); return; }
    const seen = new Set<string>();
    await page.exposeBinding("__otdogSeen", (_source, texts: string[]) => { for (const text of texts) seen.add(text); });
    // Text the API sends, such as a finding or a printed label, is the backend's copy (ticket 147), not the catalogue's.
    const served = new Set<string>();
    const collect = (value: unknown) => {
      if (typeof value === "string") served.add(value.replace(/\s+/g, " ").trim());
      else if (value && typeof value === "object") Object.values(value).forEach(collect);
    };
    page.on("response", response => {
      if (!new URL(response.url()).pathname.startsWith("/api/")) return;
      response.json().then(collect, () => {});
    });
    await page.addInitScript(recordTexts);
    await use();
    // Let the last change reach the record before reading it.
    await page.waitForTimeout(200).catch(() => {});
    const english = englishCopy(seen).filter(text => !served.has(text));
    baseExpect(english, "English catalogue copy on a Spanish screen").toEqual([]);
  }, { auto: true }],
});

