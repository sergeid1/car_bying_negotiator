import { useMessages } from "./i18n/LanguageProvider";

/** Beside every result that reads the dealer's paperwork, because a numbers check is not a review of the contract. */
export function SigningNote() {
  const { m } = useMessages();
  return <p className="muted">{m.signingNote}</p>;
}
