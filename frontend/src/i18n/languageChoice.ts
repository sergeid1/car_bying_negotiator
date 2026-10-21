import { type Language, languages } from "./format";

/**
 * The Buyer's language choice (ticket 146). It is remembered in the browser like the theme choice in `theme.ts`, and
 * a signed-in Buyer's choice is also saved on the Buyer Account. public/theme-init.js reads the same storage key to
 * set `<html lang>` before the first paint; keep the two in step.
 */
export const LANGUAGE_STORAGE_KEY = "otdog-language";

export function isLanguage(value: unknown): value is Language {
  return typeof value === "string" && Object.hasOwn(languages, value);
}

/** Storage can be unavailable (private windows, blocked site data); then nothing is remembered. */
export function readStoredLanguage(): Language | undefined {
  try {
    const stored = window.localStorage.getItem(LANGUAGE_STORAGE_KEY);
    return isLanguage(stored) ? stored : undefined;
  } catch {
    return undefined;
  }
}

export function storeLanguage(language: Language) {
  try {
    window.localStorage.setItem(LANGUAGE_STORAGE_KEY, language);
  } catch {
    // The choice still applies for this visit.
  }
}

/** A first visit follows the browser when Spanish comes before English in its list, and is English otherwise. */
export function browserLanguage(preferred: readonly string[] = navigator.languages ?? [navigator.language]): Language {
  for (const tag of preferred) {
    const primary = tag.toLowerCase().split("-")[0];
    if (primary === "es") return "es";
    if (primary === "en") return "en";
  }
  return "en";
}

export function initialLanguage(): Language {
  return readStoredLanguage() ?? browserLanguage();
}
