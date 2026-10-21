import { createContext, useContext, useEffect, type ReactNode } from "react";
import { en } from "./en";
import { activeLanguage, type Language, languages, locale, setActiveLanguage } from "./format";
import { fillIn, fillInNodes, type Message, type Values } from "./messages";

export type Messages = typeof en;

/** English is the only shipped language and the fallback for everything (ticket 145). */
const catalogues: Record<Language, Messages> = { en };

const LanguageContext = createContext<Language>("en");

/** Sets the page language for everything below it, including `<html lang>` (WCAG 3.1.1). */
export function LanguageProvider({ language = "en", children }: { language?: Language; children: ReactNode }) {
  // Set before the children render, so module-level formatters agree with the components calling them.
  if (activeLanguage() !== language) setActiveLanguage(language);
  useEffect(() => { document.documentElement.lang = language; }, [language]);
  return <LanguageContext.Provider value={language}>{children}</LanguageContext.Provider>;
}

/** The current language's messages, and helpers that fill a message's placeholders with text or with nodes. */
export function useMessages() {
  const language = useContext(LanguageContext);
  const tag = locale(language);
  return {
    m: catalogues[language] ?? en,
    language,
    languageName: languages[language].name,
    fill: <M extends Message>(message: M, values: Values<M>) => fillIn(tag, message, values),
    fillNodes: <M extends Message>(message: M, values: Values<M, ReactNode>) => fillInNodes(tag, message, values),
  };
}
