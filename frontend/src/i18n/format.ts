/**
 * Shared formatters for money, numbers, dates and lists in the current language (ticket 145). Amounts stay in US
 * dollars in every language; only the way they are written changes. The language provider sets the active language
 * before its children render, so a module-level helper formats the same way as the component that calls it.
 */
export const languages = {
  en: { locale: "en-US", name: "English" },
} as const;

export type Language = keyof typeof languages;

let active: Language = "en";

export function setActiveLanguage(language: Language) { active = language; }

export function activeLanguage(): Language { return active; }

/** The locale the formatters use: US conventions for the language, since every case is a US purchase. */
export function locale(language: Language = active) { return languages[language].locale; }

/** An amount held in minor units (cents), written in the currency the amount carries. */
export function money(minor: number, { currency, ...options }: Intl.NumberFormatOptions = {}) {
  return new Intl.NumberFormat(locale(), { style: "currency", currency: currency ?? "USD", ...options }).format(minor / 100);
}

export function number(value: number, options?: Intl.NumberFormatOptions) {
  return value.toLocaleString(locale(), options);
}

type When = string | number | Date;

/** A calendar date; with no options, the browser's default date form for the language. */
export function date(value: When, options?: Intl.DateTimeFormatOptions) {
  return new Date(value).toLocaleDateString(locale(), options);
}

/** A date with its time; with no options, the browser's default form for the language. */
export function dateTime(value: When, options?: Intl.DateTimeFormatOptions) {
  return new Date(value).toLocaleString(locale(), options);
}

/** A list of items joined the way the language joins them: "a, b, and c". */
export function list(items: readonly string[], type: Intl.ListFormatType = "conjunction") {
  return new Intl.ListFormat(locale(), { style: "long", type }).format(items);
}
