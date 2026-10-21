import { FormEvent, useContext, useEffect, useRef, useState } from "react";
import { AccountSignedInContext } from "./BuyerAccount";
import { DealerHandoff } from "./DealerHandoff";
import { OutboundGateDecision, type GateDecision } from "./OutboundGateDecision";
import * as format from "./i18n/format";
import { useMessages, type Messages } from "./i18n/LanguageProvider";

type Field = "FIRST_NAME" | "LAST_NAME" | "EMAIL" | "PHONE";
type Slot = { option: number; startsAt: string; endsAt: string; label: string };
type Message = { id: string; kind: "PROPOSAL" | "RESCHEDULE" | "BOOKING" | "CANCELLATION"; appointmentId: string | null;
  slots: Slot[]; release: Field[]; state: string; messageId?: string | null; createdAt: string; sentAt: string | null;
  gatedDraft: { draft: { text: string }; decision: GateDecision } };
type Release = { fields: Field[]; state: string; decidedAt: string; releasedAt: string | null };
type Appointment = { id: string; option: number; startsAt: string; endsAt: string; label: string; state: string;
  replacesAppointmentId: string | null; release: Release | null };
type Confirmation = { proposalId: string; messageId: string; receivedAt: string; excerpt: string; detectedOption: number | null };
export type TestDriveView = {
  agreedPrice: null | { id: string; offerVersion: number; baselineVersion: number; outTheDoorMinor: number | null };
  dealerAddress: string | null; contactFields: Field[]; messages: Message[]; appointments: Appointment[]; confirmations: Confirmation[];
};

type Copy = Messages["dealer"]["testDrive"];

const usd = (minor: number) => format.money(minor);
const fieldList = (t: Copy, fields: Field[]) => fields.length ? fields.map(field => t.fields[field]).join(", ") : t.noFields;

function asView(value: unknown, unavailable: string): TestDriveView {
  const v = value as Partial<TestDriveView> | null;
  if (!v || !("agreedPrice" in v) || !Array.isArray(v.messages) || !Array.isArray(v.appointments) || !Array.isArray(v.confirmations)
    || !Array.isArray(v.contactFields)) throw new Error(unavailable);
  return v as TestDriveView;
}
function defaultZone() {
  try { return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC"; } catch { return "UTC"; }
}

/** Test-drive scheduling after an Agreed Price. Nothing is sent or released without the Buyer's explicit approval. */
export function TestDriveAppointment({ caseId, token, version = 0 }: { caseId: string; token: string; version?: number }) {
  const { m: { dealer: { testDrive: t } }, fill } = useMessages();
  const messageStates: Record<string, string> = t.messageStates;
  const appointmentStates: Record<string, string> = t.appointmentStates;
  const [view, setView] = useState<TestDriveView>();
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [moving, setMoving] = useState<string>();
  const [windowCount, setWindowCount] = useState(1);
  const sharedSignedIn = useContext(AccountSignedInContext);
  const [discoveredSignedIn, setDiscoveredSignedIn] = useState(false);
  const signedIn = sharedSignedIn ?? discoveredSignedIn;
  const key = useRef(crypto.randomUUID());
  const path = `/api/v1/cases/${encodeURIComponent(caseId)}/appointment`;
  useEffect(() => {
    if (sharedSignedIn !== undefined) return;
    let current = true;
    fetch("/api/v1/account").then(async response => {
      const account = response.ok ? await response.json() : undefined;
      if (current) setDiscoveredSignedIn(typeof account?.contact?.email === "string");
    }).catch(() => { if (current) setDiscoveredSignedIn(false); });
    return () => { current = false; };
  }, [path, version, sharedSignedIn]);
  async function request(url: string, body?: unknown) {
    const response = await fetch(url, { method: body === undefined ? "GET" : "POST", headers: {
      "Case-Access-Token": token, "Content-Type": "application/json", "Idempotency-Key": key.current,
      ...(body !== undefined ? { "Account-Request": "1" } : {}),
    }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    if (!response.ok) {
      if (response.status === 401) throw new Error(t.signInToBook);
      if (response.status === 403) throw new Error(t.wrongAccount);
      const detail = await response.json().catch(() => ({}));
      throw new Error(typeof detail.detail === "string" ? detail.detail : t.updateFailed);
    }
    return response.json();
  }
  async function refresh() { setView(asView(await request(path), t.unavailable)); }
  useEffect(() => {
    let current = true;
    setError(undefined);
    request(path).then(value => { if (current) setView(asView(value, t.unavailable)); })
      .catch(() => { if (current) setError(t.unavailable); });
    return () => { current = false; };
    // The case, its token and relay changes define this view.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, token, version]);
  async function update(url: string, body: unknown) {
    setBusy(true); setError(undefined);
    try { await request(url, body); key.current = crypto.randomUUID(); setMoving(undefined); await refresh(); }
    catch (failure) { setError((failure as Error).message); }
    finally { setBusy(false); }
  }
  function propose(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const windows = Array.from({ length: windowCount }, (_, index) => ({ start: data.get(`start-${index}`), end: data.get(`end-${index}`) }));
    void update(`${path}/proposals`, { appointmentId: moving ?? null, timeZone: data.get("zone"), windows });
  }
  function book(event: FormEvent<HTMLFormElement>, reply: Confirmation) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    void update(`${path}/bookings`, { proposalId: reply.proposalId, messageId: reply.messageId, option: Number(data.get("option")),
      release: data.getAll("release") });
  }
  async function download(appointment: Appointment) {
    setBusy(true); setError(undefined);
    try {
      const response = await fetch(`${path}/appointments/${encodeURIComponent(appointment.id)}/calendar`, { headers: { "Case-Access-Token": token } });
      if (!response.ok) throw new Error(t.calendarFailed);
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement("a"); link.href = url; link.download = "test-drive.ics";
      link.click(); window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (failure) { setError((failure as Error).message); }
    finally { setBusy(false); }
  }

  if (view && !view.agreedPrice) return null;
  const active = view?.appointments.find(appointment => appointment.state === "SCHEDULED" || appointment.state === "RESCHEDULING");
  const proposal = view?.messages.filter(message => (message.kind === "PROPOSAL" || message.kind === "RESCHEDULE") && message.state === "SENT").at(-1);
  const canPropose = view && (!active || moving);
  return <section className="panel test-drive-panel" aria-labelledby="test-drive-heading">
    <h2 id="test-drive-heading">{t.heading}</h2>
    {!view ? <p role="status">{error ? "" : t.loading}</p> : <>
      <p>{t.subjectTo}</p>
      {view.agreedPrice && <p>{view.agreedPrice.outTheDoorMinor !== null
        ? fill(t.agreedPriceTotal, { offer: view.agreedPrice.offerVersion, total: usd(view.agreedPrice.outTheDoorMinor),
          baseline: view.agreedPrice.baselineVersion })
        : fill(t.agreedPrice, { offer: view.agreedPrice.offerVersion, baseline: view.agreedPrice.baselineVersion })}</p>}

      {view.appointments.length > 0 && <>
        <h3>{t.yourTestDrives}</h3>
        <ul className="test-drive-list">{view.appointments.map(appointment => <li key={appointment.id} className="card">
          <p><strong>{appointment.label}</strong></p>
          <p role="status">{appointmentStates[appointment.state] ?? appointment.state}</p>
          {appointment.release && appointment.state !== "WITHDRAWN" && <p>{fill(appointment.release.state === "PREPARED"
            ? t.contactChosen : t.contactShared, { fields: fieldList(t, appointment.release.fields) })}</p>}
          {appointment.state !== "PENDING_BOOKING" && appointment.state !== "WITHDRAWN" && <button type="button" disabled={busy}
            onClick={() => void download(appointment)}>{fill(t.download, { label: appointment.label })}</button>}
          {(appointment.state === "SCHEDULED" || appointment.state === "RESCHEDULING") && <div className="test-drive-actions">
            {appointment.state === "SCHEDULED" && <button type="button" disabled={busy}
              onClick={() => { setMoving(appointment.id); setWindowCount(1); }}>{t.move}</button>}
            <button type="button" disabled={busy} onClick={() => void update(`${path}/appointments/${encodeURIComponent(appointment.id)}/cancellation`, {})}>
              {t.prepareCancellation}</button>
          </div>}
        </li>)}</ul>
        {active && <p>{t.reminder}</p>}
      </>}

      {canPropose && <form onSubmit={propose} aria-label={moving ? t.newAvailability : t.yourAvailability}>
        <h3>{moving ? t.whenElse : t.when}</h3>
        <p>{t.windowsHelp}</p>
        <label>{t.timeZone}<input name="zone" required defaultValue={defaultZone()} disabled={busy} autoComplete="off" /></label>
        {Array.from({ length: windowCount }, (_, index) => <fieldset key={index} disabled={busy}>
          <legend>{fill(t.window, { number: index + 1 })}</legend>
          <label>{t.starts}<input name={`start-${index}`} type="datetime-local" required /></label>
          <label>{t.ends}<input name={`end-${index}`} type="datetime-local" required /></label>
        </fieldset>)}
        <div className="test-drive-actions">
          {windowCount < 3 && <button type="button" disabled={busy} onClick={() => setWindowCount(count => count + 1)}>{t.addWindow}</button>}
          {moving && <button type="button" disabled={busy} onClick={() => setMoving(undefined)}>{t.keepTime}</button>}
          <button type="submit" disabled={busy}>{t.prepareTimes}</button>
        </div>
      </form>}

      {view.messages.map(message => <article key={message.id} className="card">
        <h3>{t.titles[message.kind]}</h3>
        <pre>{message.gatedDraft.draft.text}</pre>
        {message.kind === "BOOKING" && <p>{message.release.length
          ? fill(t.bookingRelease, { fields: fieldList(t, message.release) })
          : t.noContactAdded}</p>}
        <OutboundGateDecision decision={message.gatedDraft.decision} />
        {message.state === "DRAFT" ? <button type="button" disabled={busy || !message.gatedDraft.decision.pass}
          onClick={() => void update(`${path}/messages/${encodeURIComponent(message.id)}/approval`,
            { approved: true, text: message.gatedDraft.draft.text })}>
          {message.kind === "BOOKING" ? t.approveWithDetails : t.approve}
        </button> : message.state === "HANDED_OFF" && message.messageId
          ? <DealerHandoff caseId={caseId} token={token} messageId={message.messageId} onChanged={refresh} />
          : <p role="status">{messageStates[message.state] ?? message.state}</p>}
        {message.kind === "BOOKING" && message.state === "HANDED_OFF" && <p>{t.bookedOnSend}</p>}
      </article>)}

      {proposal && view.confirmations.length > 0 && <>
        <h3>{t.replies}</h3>
        <p>{t.checkReplies}</p>
        {view.confirmations.map(reply => <form key={reply.messageId} className="card" aria-label={fill(t.replyLabel, { time: format.dateTime(reply.receivedAt) })}
          onSubmit={event => book(event, reply)}>
          <p>{fill(t.received, { time: format.dateTime(reply.receivedAt) })}</p>
          <blockquote><pre>{reply.excerpt || t.noText}</pre></blockquote>
          <p>{reply.detectedOption ? fill(t.detectedOption, { option: reply.detectedOption }) : t.noOption}</p>
          <label>{t.confirmedTime}<select name="option" required defaultValue={reply.detectedOption ?? ""} disabled={busy}>
            <option value="">{t.chooseTime}</option>
            {proposal.slots.map(slot => <option key={slot.option} value={slot.option}>{fill(t.option, { option: slot.option, label: slot.label })}</option>)}
          </select></label>
          <fieldset disabled={busy}>
            <legend>{t.contactLegend}</legend>
            <p>{t.nothingAdded}</p>
            {view.contactFields.length ? view.contactFields.map(field => <label key={field}>
              <input type="checkbox" name="release" value={field} /> {t.fields[field]}</label>)
              : <p>{t.noContactDetails}</p>}
          </fieldset>
          {!signedIn && <p>{t.signInToBook}</p>}
          <button type="submit" disabled={busy}>{t.confirm}</button>
        </form>)}
      </>}
      {proposal && view.confirmations.length === 0 && !active && <p role="status">{t.timesSent}</p>}
    </>}
    <button type="button" disabled={busy} onClick={() => { setError(undefined); void refresh().catch(failure => setError((failure as Error).message)); }}>
      {t.refresh}</button>
    {error && <p role="alert">{error}</p>}
  </section>;
}
