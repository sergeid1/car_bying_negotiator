/**
 * First-party channel attribution. The arrival link may carry `?channel=<code>`; the code is read
 * once, removed from the address bar, held only in memory, and sent with case creation. Nothing is
 * written to cookies or browser storage, and no referrer or other arrival detail is read.
 */
const PARAMETER = "channel";
const CODE = /^[a-z0-9][a-z0-9-]{0,31}$/;

/** Ticket 42's code format, which the service also enforces. */
export function isChannelCode(value: string) {
  return CODE.test(value);
}

let read = false;
let held: string | undefined;

/** The arrival channel code, read from the address bar the first time only. */
export function arrivalChannel(): string | undefined {
  if (!read) {
    read = true;
    const url = new URL(window.location.href);
    const value = url.searchParams.get(PARAMETER);
    if (value !== null) {
      url.searchParams.delete(PARAMETER);
      window.history.replaceState(window.history.state, "", `${url.pathname}${url.search}${url.hash}`);
    }
    // The service checks its allow list; a malformed code is simply not sent.
    held = value !== null && isChannelCode(value) ? value : undefined;
  }
  return held;
}

/** Called once a case has been created, so the code is attributed to that case only. */
export function forgetArrivalChannel() {
  read = true;
  held = undefined;
}

export function ReferralDisclosure({ text }: { text: string }) {
  return (
    <section className="panel" aria-labelledby="referral-heading">
      <h2 id="referral-heading">How you reached us</h2>
      <p>{text}</p>
      <p className="muted">
        This referral does not change the analysis, the report, or the price offered to you.
      </p>
    </section>
  );
}
