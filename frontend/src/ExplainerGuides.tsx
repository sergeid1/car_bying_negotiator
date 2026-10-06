import { useState, type ReactNode } from "react";
import { arrivalChannel } from "./AcquisitionChannel";

/**
 * First-party explainer guides. Each is reachable without a case at a stable `?guide=<slug>` link
 * and ends with a plain link that starts a case through the existing `channel` link parameter, so
 * the only acquisition datum stays the channel code. A guide reached through a referral partner's
 * link carries that partner's code instead of its own, so the referral is not lost on the way to
 * the intake. The guides are static: they fetch nothing,
 * load nothing from another site, and write nothing to cookies or browser storage.
 */
const PARAMETER = "guide";

type Guide = { title: string; summary: string; channel: string; body: () => ReactNode };

const GUIDES = {
  "out-the-door-quote": {
    title: "How to read an out-the-door quote",
    summary: "What makes up the total a dealer quotes, and how the parts should add up.",
    channel: "guide-otd-quote",
    body: OutTheDoorQuote,
  },
  "dealer-fees": {
    title: "Which dealer fees to ask about",
    summary: "How to tell dealer charges from government charges, and questions to put to the dealer.",
    channel: "guide-dealer-fees",
    body: DealerFees,
  },
} satisfies Record<string, Guide>;

export type GuideSlug = keyof typeof GUIDES;

function isGuide(value: string | null): value is GuideSlug {
  return value !== null && Object.hasOwn(GUIDES, value);
}

/** The guide named by the address bar, if any. An unknown name shows the usual start screen. */
export function requestedGuide(): GuideSlug | undefined {
  const value = new URL(window.location.href).searchParams.get(PARAMETER);
  return isGuide(value) ? value : undefined;
}

export const guideLink = (slug: GuideSlug) => `?${PARAMETER}=${slug}`;
/** The intake reads `channel` once and removes it from the address bar, exactly as for any link. */
const startLink = (channel: string) => `?channel=${channel}#intake-heading`;

/** Every guide, for pages that link to them. */
export const GUIDE_LIST = (Object.keys(GUIDES) as GuideSlug[]).map(slug => ({ slug, title: GUIDES[slug].title }));

/** Shown on the start screen so the guides can be found without a shared link. */
export function GuideLinks() {
  return <section className="panel" aria-labelledby="guide-links-heading">
    <div className="section-heading">
      <div>
        <p className="step">Holding a dealer quote?</p>
        <h2 id="guide-links-heading">Guides to reading your quote</h2>
      </div>
    </div>
    <ul className="guide-links">
      {(Object.keys(GUIDES) as GuideSlug[]).map(slug => <li key={slug}>
        <a href={guideLink(slug)}>{GUIDES[slug].title}</a>
        <span className="muted">{GUIDES[slug].summary}</span>
      </li>)}
    </ul>
  </section>;
}

export function ExplainerGuide({ slug }: { slug: GuideSlug }) {
  const guide = GUIDES[slug];
  // Read once like the intake does: removed from the address bar and held only in memory.
  const [referral] = useState(arrivalChannel);
  const Body = guide.body;
  const other = (Object.keys(GUIDES) as GuideSlug[]).filter(each => each !== slug);
  return <article className="panel guide" aria-labelledby="guide-heading">
    <p className="step">Guide</p>
    <h1 id="guide-heading">{guide.title}</h1>
    <p className="lede">{guide.summary}</p>
    <Body />
    <p className="muted">
      This guide is general information. Rules on what a dealer may charge differ by place, and this
      guide does not describe them or offer a legal opinion.
    </p>
    <section className="guide-start" aria-labelledby="guide-start-heading">
      <h2 id="guide-start-heading">Check your own quote</h2>
      <p>
        Start a Buying Case and upload the dealer's quote. You confirm each amount against the page it
        came from before any analysis runs.
      </p>
      <p>
        Deal X-Ray is a tool on your side: it helps you find cars, check quotes, draft your emails to
        dealers, and check desk paperwork. You send every email from your own email and make every
        decision. We don't work with dealers. We help people.
      </p>
      <div className="actions">
        <a className="button-link" href={startLink(referral ?? guide.channel)}>Start a case with your quote</a>
      </div>
    </section>
    <nav className="actions" aria-label="More guides">
      {other.map(each => <a key={each} href={guideLink(each)}>{GUIDES[each].title}</a>)}
      <a href="./">Back to the start</a>
    </nav>
  </article>;
}

function OutTheDoorQuote() {
  return <>
    <section aria-labelledby="otd-parts-heading">
      <h2 id="otd-parts-heading">What the total is made of</h2>
      <p>
        The out-the-door total is what you would pay to drive the vehicle away. A complete quote
        breaks it into parts, each with its own line:
      </p>
      <ul>
        <li><strong>Selling price</strong>, the price of the vehicle itself, after any discount the dealer shows.</li>
        <li><strong>Rebates and credits</strong>, which reduce what you pay.</li>
        <li><strong>Dealer fees</strong>, charges the dealer sets for its own work, such as paperwork or preparation.</li>
        <li><strong>Add-ons</strong>, products or services added to the sale, such as protection packages or accessories.</li>
        <li><strong>Taxes</strong> on the sale.</li>
        <li><strong>Government charges</strong>, such as title and registration, which the dealer collects and passes on.</li>
        <li><strong>Trade-in</strong>, if you have one: its allowance and any payoff still owed on it.</li>
      </ul>
    </section>
    <figure className="guide-example">
      <figcaption>
        Example only: invented amounts that show the layout. They are not typical, expected, or
        recommended values.
      </figcaption>
      <dl className="money-list">
        <div><dt>Selling price (example)</dt><dd>$24,000.00</dd></div>
        <div><dt>Dealer fees (example)</dt><dd>$600.00</dd></div>
        <div><dt>Add-ons (example)</dt><dd>$1,200.00</dd></div>
        <div><dt>Taxes (example)</dt><dd>$1,500.00</dd></div>
        <div><dt>Government charges (example)</dt><dd>$300.00</dd></div>
        <div><dt>Out-the-door total (example)</dt><dd>$27,600.00</dd></div>
      </dl>
    </figure>
    <section aria-labelledby="otd-check-heading">
      <h2 id="otd-check-heading">What to look for</h2>
      <ul>
        <li>Every part has its own line and amount, and the lines add up to the total shown.</li>
        <li>Each line says what it is for. A charge with only a code or an abbreviation is worth a question.</li>
        <li>Add-ons are listed separately from the selling price, so you can see which ones are optional.</li>
        <li>Dealer fees are not mixed in with government charges.</li>
        <li>The quote names the vehicle, ideally by its VIN, so you know which vehicle it prices.</li>
      </ul>
    </section>
    <section aria-labelledby="otd-product-heading">
      <h2 id="otd-product-heading">What Deal X-Ray checks</h2>
      <p>
        When you upload a quote, Deal X-Ray reads each line, sorts it into the parts above, and asks you
        to confirm every amount against the page it came from. It then checks that the parts add up to
        the out-the-door total, compares the selling price with a market range where enough evidence
        exists, and turns its findings into questions you can edit and copy for the dealer. If the dealer sends a revised quote, the
        report shows each change between the two versions as a quote change.
      </p>
    </section>
  </>;
}

function DealerFees() {
  return <>
    <section aria-labelledby="fees-kinds-heading">
      <h2 id="fees-kinds-heading">Dealer charges and government charges</h2>
      <p>
        A quote usually contains two different kinds of charge beyond the selling price. Government
        charges, such as title and registration, are collected by the dealer and passed on. Dealer fees
        and add-ons are set by the dealer. Knowing which is which tells you which lines the dealer can
        explain or change.
      </p>
    </section>
    <section aria-labelledby="fees-common-heading">
      <h2 id="fees-common-heading">Charges worth asking about</h2>
      <ul>
        <li><strong>Documentation or processing fees</strong>, for preparing the sale paperwork.</li>
        <li><strong>Preparation or delivery fees</strong>, for getting the vehicle ready.</li>
        <li><strong>Pre-installed add-ons</strong>, such as protection coatings, etching, or accessories already on the vehicle.</li>
        <li><strong>Optional products</strong>, such as service contracts or gap coverage, added to the sale.</li>
        <li><strong>Market adjustments</strong>, an amount added on top of the vehicle's price.</li>
        <li><strong>Any line you do not recognise</strong>, including ones shown only as a code.</li>
      </ul>
    </section>
    <section aria-labelledby="fees-questions-heading">
      <h2 id="fees-questions-heading">Questions to put to the dealer</h2>
      <ol>
        <li>What is this charge for?</li>
        <li>Is it set by the dealer or by a government office?</li>
        <li>Is it required to buy this vehicle, or optional?</li>
        <li>Can it be removed, and what is the out-the-door total without it?</li>
        <li>Was it included in the price you advertised?</li>
      </ol>
      <p>Asking for the answer in writing, on a revised quote, keeps every change on the record.</p>
    </section>
    <section aria-labelledby="fees-product-heading">
      <h2 id="fees-product-heading">What Deal X-Ray checks</h2>
      <p>
        When you upload a quote, Deal X-Ray sorts each line into dealer fees, add-ons, taxes, government
        charges and the other parts of the total, and asks you to confirm each amount against its page.
        The report shows dealer fees and add-ons as their own parts of the total, and turns its
        findings into questions you can edit and copy for the dealer. If the dealer sends a revised quote, the report shows what changed between
        the two versions as a quote change.
      </p>
    </section>
  </>;
}
