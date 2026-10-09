import { FormEvent, useState } from "react";
import { isChannelCode, ReferralDisclosure } from "./AcquisitionChannel";
import { GUIDE_LIST, guideLink } from "./ExplainerGuides";

/**
 * The referral partner kit, reachable without a case at a stable `?page=referral-partners` link.
 * Its link builder works entirely in the page: it fetches nothing and writes nothing to cookies or
 * browser storage. Copying a link uses the clipboard only when the partner presses Copy.
 */
const PARAMETER = "page";
const SLUG = "referral-partners";

export const partnerKitLink = `?${PARAMETER}=${SLUG}`;

/** Whether the address bar names the kit. An unknown page shows the usual start screen. */
export function requestedPartnerKit(): boolean {
  return new URL(window.location.href).searchParams.get(PARAMETER) === SLUG;
}

/** Invented partner and wording, shown only as a labelled example of the Buyer's panel. */
const EXAMPLE_DISCLOSURE =
  "Example Independent Inspections, a vehicle inspection service, suggested otdog to you. It is not paid for this referral and does not pay otdog.";

type PartnerLink = { label: string; address: string };

function partnerLinks(code: string): PartnerLink[] {
  const base = `${window.location.origin}${window.location.pathname}`;
  return [
    { label: "Start screen", address: `${base}?channel=${code}` },
    ...GUIDE_LIST.map(guide => ({ label: `Guide: ${guide.title}`, address: `${base}${guideLink(guide.slug)}&channel=${code}` })),
  ];
}

export function ReferralPartnerKit() {
  return <article className="panel guide partner-kit" aria-labelledby="partner-kit-heading">
    <p className="step">Referral partners</p>
    <h1 id="partner-kit-heading">Referral partner kit</h1>
    <p className="lede">
      For consumer educators and independent inspection services who want to point Buyers holding a
      dealer quote to otdog.
    </p>

    <section aria-labelledby="partner-who-heading">
      <h2 id="partner-who-heading">Who can be a referral partner</h2>
      <ul>
        <li><strong>Consumer educators</strong>, such as writers, teachers, and counsellors who explain car buying.</li>
        <li><strong>Independent inspection services</strong>, who examine a vehicle for the Buyer before purchase.</li>
      </ul>
      <p>
        Dealers, lenders, and parties affiliated with a dealer cannot be referral partners. otdog
        does not sell leads to dealers and does not contact dealers for the Buyer.
      </p>
    </section>

    <section aria-labelledby="partner-product-heading">
      <h2 id="partner-product-heading">What otdog does</h2>
      <p>
        otdog is a tool on the Buyer's side of a used-car purchase. We don't work with dealers. We
        help people. It helps the Buyer find cars, check dealer quotes, draft their own emails to dealers,
        and check the paperwork presented at the desk. The Buyer sends every email from their own email
        and makes every decision; otdog never contacts a dealer itself.
      </p>
      <p>
        It reviews the dealer's own paperwork for financial consistency. When a Buyer uploads a quote, it reads each line, sorts it into the parts of the out-the-door
        total, and asks the Buyer to confirm every amount against the page it came from. It then checks
        that the parts add up to the total, compares the selling price with a market range where enough
        evidence exists, and turns its findings into questions the Buyer can edit and copy for the dealer.
      </p>
      <p>
        It is not a legal, tax, or mechanical review, and it does not promise any price or negotiating
        outcome. Please describe it to Buyers in the same terms: as help the Buyer uses, not as a
        negotiator or a service that deals with the dealer for them.
      </p>
    </section>

    <section aria-labelledby="partner-link-heading">
      <h2 id="partner-link-heading">How a referral link works</h2>
      <p>
        The operator gives you a channel code. Your links carry it as <code>channel=</code> in the
        address. When a Buyer follows one and starts a Buying Case, the page reads the code once, removes
        it from the address bar, and sends it with the new case. The code is the only thing recorded
        about how the Buyer arrived.
      </p>
      <p>
        There are no cookies, browser-storage identifiers, tracking scripts, or records of the site the Buyer came from, and
        nothing follows the Buyer to other sites. You receive no information about any individual Buyer,
        case, or document.
      </p>
    </section>

    <section aria-labelledby="partner-buyer-heading">
      <h2 id="partner-buyer-heading">What Buyers are told</h2>
      <p>
        A Buyer who starts a case from your link sees a disclosure naming the referral before uploading
        any paperwork. The operator agrees its wording with you when your code is configured.
      </p>
      <figure className="guide-example">
        <figcaption>
          Example only: an invented partner and wording, shown in the same panel a referred Buyer sees.
        </figcaption>
        <ReferralDisclosure text={EXAMPLE_DISCLOSURE} />
      </figure>
    </section>

    <section aria-labelledby="partner-terms-heading">
      <h2 id="partner-terms-heading">Terms of the arrangement</h2>
      <ul>
        <li>
          otdog works only for Buyers. It does not work with dealers: it has no dealer customers or
          partners, takes no money from dealers, and shares nothing about a Buyer with any dealer.
        </li>
        <li>No dealer, lender, or other commercial party pays for placement, leads, or referrals.</li>
        <li>Partners are not paid for referrals, and partners do not pay for placement or referrals.</li>
        <li>Every Buyer who arrives through a partner's link is shown a disclosure of the referral.</li>
        <li>
          No partner relationship affects any market range, rating, finding, or suggested boundary, or the
          price offered to the Buyer.
        </li>
        <li>Dealers, lenders, and dealer-affiliated parties cannot be partners.</li>
        <li>
          The operator can deactivate a code at any time. Links that carry it then start cases with no
          referral recorded and no disclosure shown.
        </li>
      </ul>
    </section>

    <LinkBuilder />

    <nav className="actions" aria-label="More from otdog">
      {GUIDE_LIST.map(guide => <a key={guide.slug} href={guideLink(guide.slug)}>{guide.title}</a>)}
      <a href="./">Back to the start</a>
    </nav>
  </article>;
}

function LinkBuilder() {
  const [code, setCode] = useState("");
  const [built, setBuilt] = useState<string>();
  const [invalid, setInvalid] = useState(false);

  function build(event: FormEvent) {
    event.preventDefault();
    const value = code.trim();
    setInvalid(!isChannelCode(value));
    setBuilt(isChannelCode(value) ? value : undefined);
  }

  return <section className="guide-start" aria-labelledby="partner-builder-heading">
    <h2 id="partner-builder-heading">Build your referral links</h2>
    <p>
      Enter the channel code the operator gave you. Referrals are counted only after the operator has
      configured that code; until then, a case started from your link is recorded with no referral.
      The links are composed in this page, and nothing you enter is sent or kept.
    </p>
    <form className="partner-builder" onSubmit={build} noValidate>
      <label>
        Channel code
        <input value={code} onChange={event => setCode(event.target.value)} autoComplete="off"
          spellCheck={false} aria-invalid={invalid} aria-describedby="partner-code-format" />
      </label>
      <p id="partner-code-format" className="muted">
        1 to 32 lower-case letters, digits, and hyphens, starting with a letter or digit.
      </p>
      <div className="actions"><button type="submit">Build links</button></div>
    </form>
    {invalid && <p role="alert">That is not a channel code. Check it against the format above.</p>}
    {built && <ul className="partner-links" aria-label={`Referral links for ${built}`}>
      {partnerLinks(built).map(link => <CopyableLink key={link.address} {...link} />)}
    </ul>}
  </section>;
}

function CopyableLink({ label, address }: PartnerLink) {
  const [status, setStatus] = useState("");
  return <li>
    <label>
      {label}
      <input readOnly value={address} onFocus={event => event.currentTarget.select()} />
    </label>
    <button type="button" className="btn-secondary" aria-label={`Copy ${label} link`} onClick={async () => {
      try { await navigator.clipboard.writeText(address); setStatus("Link copied."); }
      catch { setStatus("Copy is unavailable. Select the link and copy it."); }
    }}>Copy</button>
    <p role="status">{status}</p>
  </li>;
}
