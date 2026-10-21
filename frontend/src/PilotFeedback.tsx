import { useId, useState } from "react";
import { useMessages, type Messages } from "./i18n/LanguageProvider";

type Credentials = { caseId: string; token: string };
type Copy = Messages["paperwork"]["pilotFeedback"];
const answersFrom = (t: Copy) => [["YES", t.yes], ["NO", t.no], ["UNSURE", t.unsure]];
const entries = (labels: Record<string, string>) => Object.entries(labels);
function Select({ label, value, onChange, options, required = false }: {
  label: string; value: string; onChange: (value: string) => void; options: string[][]; required?: boolean;
}) {
  const { m: { paperwork: { pilotFeedback: t } } } = useMessages();
  const id = useId();
  return <label htmlFor={id}>{label}<select id={id} value={value} required={required} onChange={e => onChange(e.target.value)}>
    <option value="">{required ? t.chooseResponse : t.preferNot}</option>
    {options.map(([key, text]) => <option key={key} value={key}>{text}</option>)}
  </select></label>;
}
async function submit({ caseId, token }: Credentials, kind: string, body: object, t: Copy) {
  const response = await fetch(`/api/v1/cases/${encodeURIComponent(caseId)}/feedback/${kind}`, {
    method: "POST", headers: { "Content-Type": "application/json", "Case-Access-Token": token }, body: JSON.stringify(body),
  });
  if (!response.ok) throw new Error(response.status === 409 ? t.alreadySaved : t.saveFailed);
}
export function FindingFeedback(props: Credentials & { reportVersion: number; findingPointer: string }) {
  const { m: { paperwork: { pilotFeedback: t } } } = useMessages();
  const answers = answersFrom(t);
  const [accurate, setAccurate] = useState("");
  const [understandable, setUnderstandable] = useState("");
  const [useful, setUseful] = useState("");
  const [busy, setBusy] = useState(false), [saved, setSaved] = useState(false), [error, setError] = useState("");
  return <form onSubmit={async e => {
    e.preventDefault(); setBusy(true); setError("");
    try { await submit(props, "findings", { reportVersion: props.reportVersion, findingPointer: props.findingPointer, accurate, understandable, useful }, t); setSaved(true); }
    catch (error) { setError((error as Error).message); } finally { setBusy(false); }
  }}>
    <fieldset disabled={busy || saved}><legend>{t.findingLegend}</legend>
      <Select label={t.accurate} value={accurate} onChange={setAccurate} options={answers} required />
      <Select label={t.understandable} value={understandable} onChange={setUnderstandable} options={answers} required />
      <Select label={t.useful} value={useful} onChange={setUseful} options={answers} required />
      <button type="submit">{t.sendFinding}</button>
    </fieldset>
    {saved && <p role="status">{t.findingSaved}</p>}{error && <p role="alert">{error}</p>}
  </form>;
}

/** `headless` drops the panel and heading so the report's collapsible list can supply them. */
export function OutcomeFeedback(props: Credentials & { headless?: boolean }) {
  const { m: { paperwork: { pilotFeedback: t, common } }, fill } = useMessages();
  const answers = answersFrom(t);
  const [stage, setStage] = useState("FINAL");
  const [values, setValues] = useState<Record<string, string>>({});
  const [price, setPrice] = useState<{ priceMinor: number; variant: string }>();
  const [busy, setBusy] = useState(false), [saved, setSaved] = useState(false), [error, setError] = useState("");
  const [priceMessage, setPriceMessage] = useState("");
  const set = (key: string) => (value: string) => setValues(old => ({ ...old, [key]: value }));
  const id = useId();
  function optional(label: string, key: string, options: string[][] = answers) {
    return <Select label={label} value={values[key] ?? ""} onChange={set(key)} options={options} />;
  }
  async function loadPrice() {
    setPriceMessage("");
    try {
      const response = await fetch(`/api/v1/cases/${encodeURIComponent(props.caseId)}/feedback`, { headers: { "Case-Access-Token": props.token } });
      if (!response.ok) throw new Error();
      const feedback = await response.json();
      if (feedback.experiment) setPrice(feedback.experiment);
      else setPriceMessage(t.noPriceQuestion);
    } catch { setPriceMessage(t.priceUnavailable); }
  }
  const body = <>
    <p>{t.intro}</p>
    <form onSubmit={async e => {
      e.preventDefault(); setBusy(true); setError("");
      const body: Record<string, unknown> = { stage };
      for (const [key, value] of Object.entries(values)) {
        if (!value || key === "disappointment" && !values.liveUse) continue;
        if (["sellingPriceMinor", "outTheDoorMinor"].includes(key)) { body[key] = Math.round(Number(value) * 100); body.currency = "USD"; }
        else if (["aprPercent", "termMonths"].includes(key)) body[key] = Number(value);
        else if (["understoodDecision", "liveUse", "willingToPay"].includes(key)) body[key] = value === "YES";
        else body[key] = value;
      }
      try { await submit(props, "outcomes", body, t); setSaved(true); }
      catch (error) { setError((error as Error).message); } finally { setBusy(false); }
    }}>
      <label>{t.stage}<select disabled={busy} value={stage} onChange={e => { setStage(e.target.value); setValues({}); setSaved(false); setError(""); }}>
          <option value="FINAL">{t.finalDecision}</option><option value="REVISED">{t.revisedOffer}</option>
        </select></label>
      <fieldset disabled={busy || saved}><legend>{t.outcomeLegend}</legend>
        {optional(t.action, "action", entries(t.actions))}
        {optional(t.reportUseful, "useful")}
        {optional(t.trust, "trust")}
        {optional(t.understoodDecision, "understoodDecision", answers.slice(0, 2))}
        {optional(t.liveUse, "liveUse", answers.slice(0, 2))}
        {values.liveUse && optional(t.disappointment, "disappointment", entries(t.disappointments))}
        <details><summary>{t.terms}</summary>
          {entries(t.termFields).map(([key, label]) =>
            <label key={key}>{label}<input type="number" min={key === "termMonths" ? 1 : 0} max={key === "termMonths" ? 120 : key === "aprPercent" ? 100 : 1000000}
              step={key === "termMonths" ? 1 : 0.01} value={values[key] ?? ""} onChange={e => set(key)(e.target.value)} /></label>)}
        </details>
        <button className="btn-secondary" type="button" onClick={loadPrice}>{t.viewPrice}</button>
        {price && optional(fill(t.willingToPay, { price: `$${(price.priceMinor / 100).toFixed(2)}` }), "willingToPay", answers.slice(0, 2))}
        {priceMessage && <p role="status">{priceMessage}</p>}
        <p>{t.onceEach}</p>
        <button type="submit" disabled={!Object.values(values).some(Boolean)}>{t.sendOutcome}</button>
      </fieldset>
      {saved && <p role="status">{t.outcomeSaved}</p>}{error && <p role="alert">{error}</p>}
    </form>
  </>;
  return props.headless ? body : <section className="panel" aria-labelledby={`${id}-heading`}>
    <h3 id={`${id}-heading`}>{common.outcomeFeedback}</h3>{body}
  </section>;
}
