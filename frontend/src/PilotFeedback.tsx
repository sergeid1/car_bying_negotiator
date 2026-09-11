import { useId, useState } from "react";

type Credentials = { caseId: string; token: string };
const answers = [["YES", "Yes"], ["NO", "No"], ["UNSURE", "Not sure"]];
function Select({ label, value, onChange, options, required = false }: {
  label: string; value: string; onChange: (value: string) => void; options: string[][]; required?: boolean;
}) {
  const id = useId();
  return <label htmlFor={id}>{label}<select id={id} value={value} required={required} onChange={e => onChange(e.target.value)}>
    <option value="">{required ? "Choose a response" : "Prefer not to answer"}</option>
    {options.map(([key, text]) => <option key={key} value={key}>{text}</option>)}
  </select></label>;
}
async function submit({ caseId, token }: Credentials, kind: string, body: object) {
  const response = await fetch(`/api/v1/cases/${encodeURIComponent(caseId)}/feedback/${kind}`, {
    method: "POST", headers: { "Content-Type": "application/json", "Case-Access-Token": token }, body: JSON.stringify(body),
  });
  if (!response.ok) throw new Error(response.status === 409
    ? "Feedback was already saved for this finding or outcome stage. Saved responses cannot be replaced."
    : "Feedback could not be saved. Check your access and retry.");
}
export function FindingFeedback(props: Credentials & { reportVersion: number; findingPointer: string }) {
  const [accurate, setAccurate] = useState("");
  const [understandable, setUnderstandable] = useState("");
  const [useful, setUseful] = useState("");
  const [busy, setBusy] = useState(false), [saved, setSaved] = useState(false), [error, setError] = useState("");
  return <form onSubmit={async e => {
    e.preventDefault(); setBusy(true); setError("");
    try { await submit(props, "findings", { reportVersion: props.reportVersion, findingPointer: props.findingPointer, accurate, understandable, useful }); setSaved(true); }
    catch (error) { setError((error as Error).message); } finally { setBusy(false); }
  }}>
    <fieldset disabled={busy || saved}><legend>Optional feedback on this finding</legend>
      <Select label="Accurate" value={accurate} onChange={setAccurate} options={answers} required />
      <Select label="Understandable" value={understandable} onChange={setUnderstandable} options={answers} required />
      <Select label="Useful" value={useful} onChange={setUseful} options={answers} required />
      <button type="submit">Send finding feedback</button>
    </fieldset>
    {saved && <p role="status">Finding feedback saved.</p>}{error && <p role="alert">{error}</p>}
  </form>;
}

export function OutcomeFeedback(props: Credentials) {
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
      else setPriceMessage("There is no price question for this case.");
    } catch { setPriceMessage("Price question unavailable. Try again later."); }
  }
  return <section className="panel" aria-labelledby={`${id}-heading`}>
    <h3 id={`${id}-heading`}>Optional outcome feedback</h3>
    <p>Share any answers you choose. Terms are Buyer reported and unverified. Feedback does not change your report or approve a deal. Do not enter names or dealer details.</p>
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
      try { await submit(props, "outcomes", body); setSaved(true); }
      catch (error) { setError((error as Error).message); } finally { setBusy(false); }
    }}>
      <label>Outcome stage<select disabled={busy} value={stage} onChange={e => { setStage(e.target.value); setValues({}); setSaved(false); setError(""); }}>
          <option value="FINAL">Final decision</option><option value="REVISED">Revised offer</option>
        </select></label>
      <fieldset disabled={busy || saved}><legend>Usefulness and deal outcome</legend>
        {optional("Action taken (optional)", "action", [["NEGOTIATED", "Negotiated"], ["REQUESTED_REVISION", "Requested a revised offer"], ["WALKED_AWAY", "Walked away"], ["PURCHASED", "Purchased"], ["NO_ACTION", "No action"]])}
        {optional("Was the report useful? (optional)", "useful")}
        {optional("Did you trust the report? (optional)", "trust")}
        {optional("Did you understand your decision? (optional)", "understoodDecision", answers.slice(0, 2))}
        {optional("Did you use this for a real purchase decision? (optional)", "liveUse", answers.slice(0, 2))}
        {values.liveUse && optional("How would you feel if this service were unavailable? (optional)", "disappointment", [["VERY_DISAPPOINTED", "Very disappointed"], ["SOMEWHAT_DISAPPOINTED", "Somewhat disappointed"], ["NOT_DISAPPOINTED", "Not disappointed"]])}
        <details><summary>Share revised or final terms (optional)</summary>
          {[["sellingPriceMinor", "Selling price (USD)"], ["outTheDoorMinor", "Out-the-door total (USD)"], ["aprPercent", "APR (%)"], ["termMonths", "Loan term (months)"]].map(([key, label]) =>
            <label key={key}>{label}<input type="number" min={key === "termMonths" ? 1 : 0} max={key === "termMonths" ? 120 : key === "aprPercent" ? 100 : 1000000}
              step={key === "termMonths" ? 1 : 0.01} value={values[key] ?? ""} onChange={e => set(key)(e.target.value)} /></label>)}
        </details>
        <button type="button" onClick={loadPrice}>View optional price question</button>
        {price && optional(`Would you pay $${(price.priceMinor / 100).toFixed(2)} once for this service? (optional)`, "willingToPay", answers.slice(0, 2))}
        {priceMessage && <p role="status">{priceMessage}</p>}
        <p>Each outcome stage can be submitted once. Leave any question blank to skip it.</p>
        <button type="submit" disabled={!Object.values(values).some(Boolean)}>Send outcome feedback</button>
      </fieldset>
      {saved && <p role="status">Outcome feedback saved.</p>}{error && <p role="alert">{error}</p>}
    </form>
  </section>;
}
