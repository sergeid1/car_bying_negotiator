import { describe, expect, it } from "vitest";
import { pairs } from "./catalogueText";
import { glossary, type GlossaryTerm, withEnglish } from "./glossary";

/** Messages that read the same in Spanish: names, units, example data and frames made only of placeholders. */
const SAME_IN_SPANISH = new Set([
  "2024 Toyota RAV4 XLE", "Example Motors", "2021 Honda Accord EX-L", "Desk Check", "nhtsa.gov/recalls", "Subtotal", "No",
  "Frontal", "VIN", "VIN {vin}", "Toto: ", "{miles} mi", "{size} MB", "{size} KB", "{years} {make} {model}",
]);
const isFrame = (text: string) => !/\p{L}{2}/u.test(text.replace(/\{\w+\}/g, ""));
const isStateName = (path: string) => /\.stateNames\./.test(path);

/** Text the Buyer approves and sends to a dealer stays in English (ticket 144, working default 4). */
const SENT_TO_DEALERS = new Set(["messages.quoteRequest.subject"]);

const ENGLISH_WORDS = /(?<![\p{L}])(the|your|you|with|this|that|and|of|is|are|for|from|to|not|please)(?![\p{L}])/iu;

/** One screen's copy: each top-level component object of a journey file, or a root group such as `home`. */
function screens() {
  const grouped = new Map<string, { en: string; es: string }>();
  for (const pair of pairs) {
    const parts = pair.path.split(".");
    const key = parts.slice(0, parts.length > 2 ? 2 : 1).join(".");
    const screen = grouped.get(key) ?? { en: "", es: "" };
    screen.en += ` ${pair.en.replace(/\{\w+\}/g, " ")}`;
    screen.es += ` ${pair.es}`;
    grouped.set(key, screen);
  }
  return grouped;
}

describe("the Spanish catalogue", () => {
  it("translates every message except names, units, example data and frames", () => {
    const same = pairs.filter(pair => pair.en === pair.es && !isFrame(pair.en) && !isStateName(pair.path) && !SAME_IN_SPANISH.has(pair.en));
    expect(same.map(pair => pair.path)).toEqual([]);
  });

  it("holds no English sentence fragments outside the glossary's English terms in parentheses", () => {
    const mixed = pairs.filter(pair => pair.en !== pair.es && !SENT_TO_DEALERS.has(pair.path)
      && ENGLISH_WORDS.test(pair.es.replace(/\([^)]*\)/g, "").replace(/\{\w+\}/g, "").replace(/“[^”]*”/g, "")));
    expect(mixed.map(pair => `${pair.path}: ${pair.es}`)).toEqual([]);
  });

  it("uses each glossary term on every screen that names it, with the dealer's English term where the glossary says", () => {
    const problems: string[] = [];
    for (const [id, entry] of Object.entries(glossary)) {
      const term: GlossaryTerm = entry;
      const english = new RegExp(`(?<![\\p{L}])${term.english.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}(?![\\p{L}])`, "iu");
      for (const [screen, copy] of screens()) {
        if (!english.test(copy.en)) continue;
        const spanish = copy.es.toLowerCase();
        if (![term.spanish, ...(term.forms ?? [])].some(form => spanish.includes(form.toLowerCase()))) problems.push(`${screen}: no “${term.spanish}”`);
        if (term.showEnglish && !spanish.includes(`(${term.english.toLowerCase()}`)) problems.push(`${screen}: no “(${term.english})”`);
      }
      expect(withEnglish(id as keyof typeof glossary)).toBe(`${term.spanish} (${term.english})`);
    }
    expect(problems).toEqual([]);
  });
});
