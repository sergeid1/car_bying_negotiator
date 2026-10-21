import { useMessages } from "./i18n/LanguageProvider";
import { dealerReadingCopy, findingCode } from "./i18n/findings";

/** A separate presentation: callers keep the English value for editing, approval and outbound delivery. */
export function DealerReadingCopy({ english }: { english: string }) {
  const { language } = useMessages();
  if (String(language) !== "es") return null;
  const copy = dealerReadingCopy(english);
  return <aside lang="es">
    <p><strong>{findingCode(language, "readingCopy")}</strong></p>
    {copy ? <pre>{copy}</pre> : <p>{findingCode(language, "readingUnavailable")}</p>}
  </aside>;
}
