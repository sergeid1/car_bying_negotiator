import { KeyboardEvent, useEffect, useRef, useState } from "react";
import { applyThemeChoice, readThemeChoice, THEME_CHOICES, type ThemeChoice } from "./theme";
import { useLanguageChoice, useMessages } from "./i18n/LanguageProvider";
import { type Language, languages } from "./i18n/format";

function ThemeIcon({ choice }: { choice: ThemeChoice }) {
  const common = { width: 18, height: 18, viewBox: "0 0 24 24", fill: "none", stroke: "currentColor",
    strokeWidth: 1.8, strokeLinecap: "round" as const, strokeLinejoin: "round" as const, "aria-hidden": true };
  if (choice === "light") return <svg {...common}><circle cx="12" cy="12" r="4" /><path d="M12 2.5v2M12 19.5v2M4.6 4.6l1.4 1.4M18 18l1.4 1.4M2.5 12h2M19.5 12h2M4.6 19.4 6 18M18 6l1.4-1.4" /></svg>;
  if (choice === "dark") return <svg {...common}><path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z" /></svg>;
  return <svg {...common}><rect x="3" y="4.5" width="18" height="12" rx="2" /><path d="M8.5 20h7M12 16.5V20" /></svg>;
}

function Check() {
  return <svg className="theme-check" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7.5" /></svg>;
}

const LANGUAGES = Object.keys(languages) as Language[];

/**
 * The header's theme and language menu (ticket 144, decision 8): follow the device or pin light or dark, and choose
 * the language. Each group is its own set of radio items; arrow keys, Home and End move through both groups.
 */
export function ThemeLanguageMenu() {
  const { m, fill, language, languageName } = useMessages();
  const { choose } = useLanguageChoice();
  const LABELS: Record<ThemeChoice, string> = { system: m.theme.system, light: m.theme.light, dark: m.theme.dark };
  const [choice, setChoice] = useState<ThemeChoice>(readThemeChoice);
  const [open, setOpen] = useState(false);
  const container = useRef<HTMLDivElement>(null);
  const toggle = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    container.current?.querySelector<HTMLButtonElement>('[aria-checked="true"]')?.focus();
    const closeOutside = (event: PointerEvent) => {
      if (!container.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener("pointerdown", closeOutside);
    return () => document.removeEventListener("pointerdown", closeOutside);
  }, [open]);

  function close() {
    setOpen(false);
    toggle.current?.focus();
  }

  function pickTheme(next: ThemeChoice) {
    setChoice(next);
    applyThemeChoice(next);
    close();
  }

  function pickLanguage(next: Language) {
    choose(next);
    close();
  }

  function navigate(event: KeyboardEvent<HTMLDivElement>) {
    const items = Array.from(container.current?.querySelectorAll<HTMLButtonElement>('[role="menuitemradio"]') ?? []);
    const current = items.indexOf(document.activeElement as HTMLButtonElement);
    const move = (index: number) => { event.preventDefault(); items[(index + items.length) % items.length]?.focus(); };
    if (event.key === "ArrowDown") move(current + 1);
    else if (event.key === "ArrowUp") move(current - 1);
    else if (event.key === "Home") move(0);
    else if (event.key === "End") move(items.length - 1);
    else if (event.key === "Escape" || event.key === "Tab") {
      if (event.key === "Escape") { event.preventDefault(); toggle.current?.focus(); }
      setOpen(false);
    }
  }

  return <div className="theme-menu" ref={container}>
    <button type="button" ref={toggle} className="theme-toggle" aria-haspopup="menu" aria-expanded={open}
      aria-label={fill(m.theme.current, { choice: LABELS[choice], language: languageName })} onClick={() => setOpen(value => !value)}>
      <ThemeIcon choice={choice} />
    </button>
    {open && <div role="menu" aria-label={m.theme.menu} className="theme-options" onKeyDown={navigate}>
      <div role="group" className="theme-group" aria-labelledby="theme-group-label">
        <p id="theme-group-label" className="theme-group-label" role="presentation">{m.theme.themeGroup}</p>
        {THEME_CHOICES.map(option => <button key={option} type="button" role="menuitemradio"
          aria-checked={option === choice} tabIndex={option === choice ? 0 : -1} onClick={() => pickTheme(option)}>
          <span className={`theme-swatch theme-swatch-${option}`} aria-hidden="true" />
          <span>{LABELS[option]}</span>
          {option === choice && <Check />}
        </button>)}
      </div>
      <div role="group" className="theme-group" aria-labelledby="language-group-label">
        <p id="language-group-label" className="theme-group-label" role="presentation">{m.theme.languageGroup}</p>
        {LANGUAGES.map(option => <button key={option} type="button" role="menuitemradio" lang={option}
          aria-checked={option === language} tabIndex={-1} onClick={() => pickLanguage(option)}>
          <span className="theme-language-code" aria-hidden="true">{option}</span>
          <span>{languages[option].name}</span>
          {option === language && <Check />}
        </button>)}
      </div>
    </div>}
  </div>;
}
