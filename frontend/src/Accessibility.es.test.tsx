import type { ReactNode } from "react";
import { vi } from "vitest";

/**
 * The axe sweep again, in Spanish (ticket 146). Every test in `Accessibility.test.tsx` renders inside a Spanish
 * language provider, and its English queries are read through the catalogues, as the browser gate's are in
 * `e2e/fixtures.ts`. The English stays an alternative, for text the backend sends.
 */
vi.mock("@testing-library/react", async original => {
  const library = await original<typeof import("@testing-library/react")>();
  const { LanguageProvider } = await import("./i18n/LanguageProvider");
  const { translatePattern, translateSelector } = await import("./i18n/catalogueText");
  const escape = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

  // Testing Library matches a string whole and case-sensitively, so a translation is anchored the same way.
  function translate(value: unknown): unknown {
    if (typeof value === "string") {
      const spanish = translateSelector(value, true, false);
      if (spanish === value) return value;
      return new RegExp(`^(?:${escape(value)}|${typeof spanish === "string" ? escape(spanish) : spanish.source.replace(/^\^|\$$/g, "")})$`);
    }
    if (value instanceof RegExp) {
      const spanish = translatePattern(value);
      return spanish.source === value.source ? value : new RegExp(`(?:${value.source})|(?:${spanish.source})`, value.flags);
    }
    return value;
  }

  function translated<Q extends object>(queries: Q): Q {
    return new Proxy(queries, {
      get(target, property, receiver) {
        const query = Reflect.get(target, property, receiver);
        if (typeof query !== "function" || typeof property !== "string" || !/By(Role|Text|LabelText|PlaceholderText|Title|AltText)$/.test(property)) return query;
        return (first: unknown, options?: Record<string, unknown>, ...rest: unknown[]) => property.endsWith("ByRole")
          ? query.call(target, first, options && "name" in options ? { ...options, name: translate(options.name) } : options, ...rest)
          : query.call(target, translate(first), options, ...rest);
      },
    });
  }

  const Spanish = ({ children }: { children: ReactNode }) => <LanguageProvider language="es">{children}</LanguageProvider>;
  return {
    ...library,
    render: ((ui: Parameters<typeof library.render>[0], options?: Parameters<typeof library.render>[1]) =>
      library.render(ui, { wrapper: Spanish, ...options })) as typeof library.render,
    screen: translated(library.screen),
    within: ((element: HTMLElement) => translated(library.within(element))) as typeof library.within,
  };
});

await import("./Accessibility.test");
