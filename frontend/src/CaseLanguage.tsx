import { useEffect, useState } from "react";
import { useMessages } from "./i18n/LanguageProvider";
import { findingCode } from "./i18n/findings";

export function CaseLanguage({ caseId, token }: { caseId: string; token: string }) {
  const { language } = useMessages();
  const [selected, setSelected] = useState("");
  const [status, setStatus] = useState("");
  const [busy, setBusy] = useState(false);
  const path = `/api/v1/cases/${encodeURIComponent(caseId)}`;
  useEffect(() => {
    let current = true;
    fetch(path, { headers: { "Case-Access-Token": token } }).then(async response => {
      if (!response.ok) throw new Error();
      const value = await response.json();
      if (current) setSelected(value.language ?? "en");
    }).catch(() => { if (current) setStatus("caseLanguageFailed"); });
    return () => { current = false; };
  }, [path, token]);
  return <div className="stack">
    <label>{findingCode(language, "caseLanguage")}<select value={selected} disabled={busy || !selected} onChange={async event => {
      const next = event.target.value;
      setBusy(true); setStatus("");
      try {
        const response = await fetch(`${path}/language`, { method: "PUT", headers: {
          "Case-Access-Token": token, "Content-Type": "application/json",
        }, body: JSON.stringify({ language: next }) });
        if (!response.ok) throw new Error();
        setSelected(next); setStatus("caseLanguageSaved");
      } catch { setStatus("caseLanguageFailed"); } finally { setBusy(false); }
    }}><option value="en">{findingCode(language, "englishName")}</option><option value="es">{findingCode(language, "spanishName")}</option></select></label>
    <p>{findingCode(language, "caseLanguageNote")}</p>
    {status && <p role="status">{findingCode(language, status)}</p>}
  </div>;
}
