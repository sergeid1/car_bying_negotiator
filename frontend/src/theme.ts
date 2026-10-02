/**
 * The Buyer's appearance choice. "system" follows the device; "light" and "dark" pin a theme.
 * public/theme-init.js applies a stored choice before the first paint so the page never flashes;
 * keep its storage key and attribute in step with this file.
 */
export type ThemeChoice = "system" | "light" | "dark";

export const THEME_CHOICES: ThemeChoice[] = ["system", "light", "dark"];
export const THEME_STORAGE_KEY = "deal-xray-theme";

/** Storage can be unavailable (private windows, blocked site data); the device theme is the fallback. */
export function readThemeChoice(): ThemeChoice {
  try {
    const stored = window.localStorage.getItem(THEME_STORAGE_KEY);
    return stored === "light" || stored === "dark" ? stored : "system";
  } catch {
    return "system";
  }
}

export function applyThemeChoice(choice: ThemeChoice) {
  const root = document.documentElement;
  if (choice === "system") root.removeAttribute("data-theme");
  else root.setAttribute("data-theme", choice);
  try {
    if (choice === "system") window.localStorage.removeItem(THEME_STORAGE_KEY);
    else window.localStorage.setItem(THEME_STORAGE_KEY, choice);
  } catch {
    // The choice still applies for this visit.
  }
}
