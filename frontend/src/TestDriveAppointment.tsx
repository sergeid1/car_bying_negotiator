import { FormEvent, useContext, useEffect, useRef, useState } from "react";
import { AccountSignedInContext } from "./BuyerAccount";
import { DealerHandoff } from "./DealerHandoff";
import { OutboundGateDecision, type GateDecision } from "./OutboundGateDecision";
import * as format from "./i18n/format";

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

const FIELDS: Record<Field, string> = { FIRST_NAME: "First name", LAST_NAME: "Surname", EMAIL: "Email", PHONE: "Phone number" };
const TITLES: Record<Message["kind"], string> = {
  PROPOSAL: "Proposed test-drive times", RESCHEDULE: "Move the test drive", BOOKING: "Confirm the booking", CANCELLATION: "Cancel the test drive",
};
const MESSAGE_STATES: Record<string, string> = {
  SENDING: "Sending.", SENT: "Sent after your approval.", BLOCKED: "Stopped before sending: the mandate, policy or appointment changed.",
  WITHDRAWN: "Replaced by a newer choice. Not sent.", OPTED_OUT: "Not sent: the dealer opted out of messages.",
  DISCARDED: "Discarded. It was not sent.",
  FAILED: "Not sent: the relay refused it.", DELIVERY_UNKNOWN: "Delivery was never confirmed by the former platform relay.",
};
const APPOINTMENT_STATES: Record<string, string> = {
  PENDING_BOOKING: "Waiting for you to approve and send the booking message", SCHEDULED: "Booked", RESCHEDULING: "Booked; new times sent to the dealer",
  RESCHEDULED: "Replaced by a new time", CANCELLED: "Cancelled", WITHDRAWN: "Not booked",
};
const usd = (minor: number) => format.money(minor);
const fieldList = (fields: Field[]) => fields.length ? fields.map(field => FIELDS[field]).join(", ") : "none";

function asView(value: unknown): TestDriveView {
  const v = value as Partial<TestDriveView> | null;
  if (!v || !("agreedPrice" in v) || !Array.isArray(v.messages) || !Array.isArray(v.appointments) || !Array.isArray(v.confirmations)
    || !Array.isArray(v.contactFields)) throw new Error("Test-drive scheduling is unavailable. Try refreshing.");
  return v as TestDriveView;
}
function defaultZone() {
  try { return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC"; } catch { return "UTC"; }
}

/** Test-drive scheduling after an Agreed Price. Nothing is sent or released without the Buyer's explicit approval. */
export function TestDriveAppointment({ caseId, token, version = 0 }: { caseId: string; token: string; version?: number }) {
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
      if (response.status === 401) throw new Error("Sign in to your Buyer Account in this browser to book and choose contact details.");
      if (response.status === 403) throw new Error("Sign in with the Buyer Account that holds this Buying Case to book.");
      const detail = await response.json().catch(() => ({}));
      throw new Error(typeof detail.detail === "string" ? detail.detail : "The test drive could not be updated. Try refreshing.");
    }
    return response.json();
  }
  async function refresh() { setView(asView(await request(path))); }
  useEffect(() => {
    let current = true;
    setError(undefined);
    request(path).then(value => { if (current) setView(asView(value)); })
      .catch(() => { if (current) setError("Test-drive scheduling is unavailable. Try refreshing."); });
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
      if (!response.ok) throw new Error("The calendar file could not be downloaded. Try again.");
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
    <h2 id="test-drive-heading">Schedule a test drive</h2>
    {!view ? <p role="status">{error ? "" : "Loading test-drive scheduling…"}</p> : <>
      <p>Your acceptance is still subject to this test drive and to the final paperwork matching the written quote.
        Dealer messages say so, and never say that you will buy.</p>
      {view.agreedPrice && <p>Agreed Price: verified offer {view.agreedPrice.offerVersion}
        {view.agreedPrice.outTheDoorMinor !== null && <>, out-the-door total {usd(view.agreedPrice.outTheDoorMinor)}</>}.
        Approved Baseline version {view.agreedPrice.baselineVersion}.</p>}

      {view.appointments.length > 0 && <>
        <h3>Your test drives</h3>
        <ul className="test-drive-list">{view.appointments.map(appointment => <li key={appointment.id} className="card">
          <p><strong>{appointment.label}</strong></p>
          <p role="status">{APPOINTMENT_STATES[appointment.state] ?? appointment.state}</p>
          {appointment.release && appointment.state !== "WITHDRAWN" && <p>Contact details {appointment.release.state === "PREPARED"
            ? "you chose to share" : "shared with this dealer"}: {fieldList(appointment.release.fields)}.</p>}
          {appointment.state !== "PENDING_BOOKING" && appointment.state !== "WITHDRAWN" && <button type="button" disabled={busy}
            onClick={() => void download(appointment)}>Download calendar file for {appointment.label}</button>}
          {(appointment.state === "SCHEDULED" || appointment.state === "RESCHEDULING") && <div className="test-drive-actions">
            {appointment.state === "SCHEDULED" && <button type="button" disabled={busy}
              onClick={() => { setMoving(appointment.id); setWindowCount(1); }}>Move this test drive</button>}
            <button type="button" disabled={busy} onClick={() => void update(`${path}/appointments/${encodeURIComponent(appointment.id)}/cancellation`, {})}>
              Prepare a cancellation message</button>
          </div>}
        </li>)}</ul>
        {active && <p>The calendar file includes a reminder, and you also receive a reminder email if appointment emails are on.</p>}
      </>}

      {canPropose && <form onSubmit={propose} aria-label={moving ? "New availability" : "Your availability"}>
        <h3>{moving ? "When else can you go?" : "When can you go?"}</h3>
        <p>Enter up to three windows of at least an hour. The dealer is offered one-hour times from them and asked to reply with an option number.</p>
        <label>Time zone<input name="zone" required defaultValue={defaultZone()} disabled={busy} autoComplete="off" /></label>
        {Array.from({ length: windowCount }, (_, index) => <fieldset key={index} disabled={busy}>
          <legend>Window {index + 1}</legend>
          <label>Starts<input name={`start-${index}`} type="datetime-local" required /></label>
          <label>Ends<input name={`end-${index}`} type="datetime-local" required /></label>
        </fieldset>)}
        <div className="test-drive-actions">
          {windowCount < 3 && <button type="button" disabled={busy} onClick={() => setWindowCount(count => count + 1)}>Add another window</button>}
          {moving && <button type="button" disabled={busy} onClick={() => setMoving(undefined)}>Keep the current time</button>}
          <button type="submit" disabled={busy}>Prepare proposed times</button>
        </div>
      </form>}

      {view.messages.map(message => <article key={message.id} className="card">
        <h3>{TITLES[message.kind]}</h3>
        <pre>{message.gatedDraft.draft.text}</pre>
        {message.kind === "BOOKING" && <p>{message.release.length
          ? `After you approve, only these details are added below the message for this dealer: ${fieldList(message.release)}.`
          : "No contact details are added to this message."}</p>}
        <OutboundGateDecision decision={message.gatedDraft.decision} />
        {message.state === "DRAFT" ? <button type="button" disabled={busy || !message.gatedDraft.decision.pass}
          onClick={() => void update(`${path}/messages/${encodeURIComponent(message.id)}/approval`,
            { approved: true, text: message.gatedDraft.draft.text })}>
          {message.kind === "BOOKING" ? "Approve this text with the chosen details" : "Approve this text"}
        </button> : message.state === "HANDED_OFF" && message.messageId
          ? <DealerHandoff caseId={caseId} token={token} messageId={message.messageId} onChanged={refresh} />
          : <p role="status">{MESSAGE_STATES[message.state] ?? message.state}</p>}
        {message.kind === "BOOKING" && message.state === "HANDED_OFF" && <p>The test drive is booked, and the calendar file is ready,
          once you send this message and tell us.</p>}
      </article>)}

      {proposal && view.confirmations.length > 0 && <>
        <h3>Dealer replies to your times</h3>
        <p>Check each reply yourself. A reply is dealer content; nothing is booked until you confirm a time and approve the booking message.</p>
        {view.confirmations.map(reply => <form key={reply.messageId} className="card" aria-label={`Dealer reply received ${format.dateTime(reply.receivedAt)}`}
          onSubmit={event => book(event, reply)}>
          <p>Received {format.dateTime(reply.receivedAt)}:</p>
          <blockquote><pre>{reply.excerpt || "(no text)"}</pre></blockquote>
          <p>{reply.detectedOption ? `This reply appears to choose option ${reply.detectedOption}. Check that this is right.`
            : "No single option number was found. Choose the time the dealer confirmed."}</p>
          <label>Time the dealer confirmed<select name="option" required defaultValue={reply.detectedOption ?? ""} disabled={busy}>
            <option value="">Choose a time</option>
            {proposal.slots.map(slot => <option key={slot.option} value={slot.option}>Option {slot.option}: {slot.label}</option>)}
          </select></label>
          <fieldset disabled={busy}>
            <legend>Contact details to share with this dealer</legend>
            <p>Nothing is added to the message unless you tick it.</p>
            {view.contactFields.length ? view.contactFields.map(field => <label key={field}>
              <input type="checkbox" name="release" value={field} /> {FIELDS[field]}</label>)
              : <p>Your Buyer Account has no contact details to share.</p>}
          </fieldset>
          {!signedIn && <p>Sign in to your Buyer Account in this browser to book and choose contact details.</p>}
          <button type="submit" disabled={busy}>Confirm this time and review the booking message</button>
        </form>)}
      </>}
      {proposal && view.confirmations.length === 0 && !active && <p role="status">Your times were sent. When the dealer replies, add the reply to the dealer conversation.</p>}
    </>}
    <button type="button" disabled={busy} onClick={() => { setError(undefined); void refresh().catch(failure => setError((failure as Error).message)); }}>
      Refresh test-drive status</button>
    {error && <p role="alert">{error}</p>}
  </section>;
}
