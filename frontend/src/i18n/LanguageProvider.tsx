import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { en } from "./en";
import { es } from "./es";
import { activeLanguage, type Language, languages, locale, setActiveLanguage } from "./format";
import { initialLanguage, storeLanguage } from "./languageChoice";
import { fillIn, fillInNodes, type Message, type Values } from "./messages";

export type Messages = typeof en;

/**
 * Every shipped catalogue. Components see English's literal types, which carry the placeholder names; `translation`
 * holds every other catalogue to those ids and placeholders (ticket 145), so it can stand in for English here.
 */
const catalogues: Record<Language, Messages> = { en, es: es as unknown as Messages };

type LanguageState = {
  language: Language;
  /** The Buyer chose a language: remember it in the browser and, when signed in, on the Buyer Account. */
  choose: (language: Language) => void;
  /** A language saved on the Buyer Account applies on sign-in, without saving it back. */
  adopt: (language: Language) => void;
  /** Saves a choice on the Buyer Account while signed in; set by the account screen, cleared on sign-out. */
  setAccountSaver: (save: ((language: Language) => void) | undefined) => void;
};

const fixed = (language: Language): LanguageState => ({ language, choose: () => {}, adopt: () => {}, setAccountSaver: () => {} });

const LanguageContext = createContext<LanguageState>(fixed("en"));

/**
 * Sets the page language for everything below it, including `<html lang>` (WCAG 3.1.1). Given a `language`, the
 * language is fixed, as in tests; otherwise it starts from the remembered choice or the browser (ticket 146).
 */
export function LanguageProvider({ language: given, children }: { language?: Language; children: ReactNode }) {
  const [chosen, setChosen] = useState<Language>(() => given ?? initialLanguage());
  const language = given ?? chosen;
  const accountSaver = useRef<((language: Language) => void) | undefined>(undefined);
  // Set before the children render, so module-level formatters agree with the components calling them.
  if (activeLanguage() !== language) setActiveLanguage(language);
  useEffect(() => { document.documentElement.lang = language; }, [language]);

  const choose = useCallback((next: Language) => {
    setChosen(next);
    storeLanguage(next);
    accountSaver.current?.(next);
  }, []);
  const adopt = useCallback((next: Language) => { setChosen(next); storeLanguage(next); }, []);
  const setAccountSaver = useCallback((save?: (language: Language) => void) => { accountSaver.current = save; }, []);
  const state = useMemo(() => given ? fixed(given) : { language, choose, adopt, setAccountSaver },
    [given, language, choose, adopt, setAccountSaver]);
  return <LanguageContext.Provider value={state}>{children}</LanguageContext.Provider>;
}

/** The current language and the ways to change it. */
export function useLanguageChoice() {
  return useContext(LanguageContext);
}

/** The current language's messages, and helpers that fill a message's placeholders with text or with nodes. */
export function useMessages() {
  const { language } = useContext(LanguageContext);
  const tag = locale(language);
  return {
    m: catalogues[language] ?? en,
    language,
    languageName: languages[language].name,
    fill: <M extends Message>(message: M, values: Values<M>) => fillIn(tag, message, values),
    fillNodes: <M extends Message>(message: M, values: Values<M, ReactNode>) => fillInNodes(tag, message, values),
  };
}
