import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { en } from "./en";
import * as format from "./format";
import { LanguageProvider, useMessages } from "./LanguageProvider";
import { fillIn, fillInNodes, plural, translation } from "./messages";

/**
 * The `@ts-expect-error` lines are the proof that parity is enforced: `npm run build` type-checks this file, and an
 * expected error that does not occur fails the build. Each one is a catalogue that must not compile.
 */
const english = { greeting: "Hello, {name}.", count: plural({ one: "{count} car", other: "{count} cars" }), nested: { title: "Title" } } as const;
const check = translation<typeof english>();

describe("catalogue parity", () => {
  it("accepts a translation with every id and the same placeholders", () => {
    const spanish = check({ greeting: "Hola, {name}.", count: plural({ one: "{count} auto", many: "{count} de autos", other: "{count} autos" }), nested: { title: "Título" } });
    expect(spanish.greeting).toBe("Hola, {name}.");
  });

  it("rejects a missing id, an extra id, and mismatched placeholders", () => {
    // @ts-expect-error a translation missing an id fails the type check
    check({ greeting: "Hola, {name}.", count: plural({ other: "{count} autos" }) });
    // @ts-expect-error a translation with an id English does not have fails the type check
    check({ greeting: "Hola, {name}.", count: plural({ other: "{count} autos" }), nested: { title: "Título", extra: "Más" } });
    // @ts-expect-error a renamed placeholder fails the type check
    check({ greeting: "Hola, {nombre}.", count: plural({ other: "{count} autos" }), nested: { title: "Título" } });
    // @ts-expect-error a dropped placeholder fails the type check
    check({ greeting: "Hola.", count: plural({ other: "{count} autos" }), nested: { title: "Título" } });
    // @ts-expect-error a plural message replaced by plain text fails the type check
    check({ greeting: "Hola, {name}.", count: "autos", nested: { title: "Título" } });
    expect(true).toBe(true);
  });

  it("checks the shipped English catalogue against itself", () => {
    expect(translation<typeof en>()(en)).toBe(en);
  });
});

describe("filling messages", () => {
  it("fills placeholders with text and numbers in the language's number style", () => {
    expect(fillIn("en-US", english.greeting, { name: "Ana" })).toBe("Hello, Ana.");
    // @ts-expect-error a missing value fails the type check
    fillIn("en-US", english.greeting, {});
    expect(fillIn("en-US", "{miles} miles", { miles: 38000 })).toBe("38,000 miles");
  });

  it("chooses the plural form with Intl.PluralRules", () => {
    expect(fillIn("en-US", english.count, { count: 1 })).toBe("1 car");
    expect(fillIn("en-US", english.count, { count: 2 })).toBe("2 cars");
    const spanish = plural({ one: "{count} auto", many: "{count} de autos", other: "{count} autos" });
    expect(fillIn("es-US", spanish, { count: 1 })).toBe("1 auto");
    expect(fillIn("es-US", plural({ one: "{count} auto", other: "{count} autos" }), { count: 1000000 })).toBe("1,000,000 autos");
  });

  it("keeps a sentence whole around a node such as a link", () => {
    render(<p>{fillInNodes("en-US", "Read {link} first.", { link: <a href="/guide">the guide</a> })}</p>);
    expect(screen.getByText(/Read/).textContent).toBe("Read the guide first.");
    expect(screen.getByRole("link", { name: "the guide" })).toHaveAttribute("href", "/guide");
  });
});

describe("the language provider", () => {
  function Probe() {
    const { m, language } = useMessages();
    return <p>{language}: {m.signingNote}</p>;
  }

  it("serves English by default and sets the page language", () => {
    document.documentElement.lang = "";
    render(<LanguageProvider><Probe /></LanguageProvider>);
    expect(screen.getByText(/^en:/)).toHaveTextContent(en.signingNote);
    expect(document.documentElement.lang).toBe("en");
  });

  it("formats money, dates and lists through the shared formatters", () => {
    expect(format.money(2921200)).toBe("$29,212.00");
    expect(format.money(-150, { currency: "USD" })).toBe("-$1.50");
    expect(format.date(Date.UTC(2026, 9, 5), { month: "short", day: "numeric", year: "numeric", timeZone: "UTC" })).toBe("Oct 5, 2026");
    expect(format.list(["fees", "add-ons", "taxes"])).toBe("fees, add-ons, and taxes");
  });
});
