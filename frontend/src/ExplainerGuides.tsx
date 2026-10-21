import { useEffect, useState, type ReactNode } from "react";
import { arrivalChannel } from "./AcquisitionChannel";
import { useMessages, type Messages } from "./i18n/LanguageProvider";

/**
 * First-party explainer guides. Each is reachable without a case at a stable `?guide=<slug>` link
 * and ends with a plain link that starts a case through the existing `channel` link parameter, so
 * the only acquisition datum stays the channel code. A guide reached through a referral partner's
 * link carries that partner's code instead of its own, so the referral is not lost on the way to
 * the intake. The guides are static: they fetch nothing,
 * load nothing from another site, and write nothing to cookies or browser storage.
 */
const PARAMETER = "guide";

type GuideText = Messages["content"]["explainerGuides"];
type Guide = { channel: string; body: (props: { t: GuideText }) => ReactNode };

const GUIDES = {
  "out-the-door-quote": { channel: "guide-otd-quote", body: OutTheDoorQuote },
  "dealer-fees": { channel: "guide-dealer-fees", body: DealerFees },
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

/** Every guide, for pages that link to them; each guide's title is `content.explainerGuides.guides[slug].title`. */
export const GUIDE_SLUGS = Object.keys(GUIDES) as GuideSlug[];

/** The guides page, reachable from the header's Guides tab at `?page=guides`. */
export const guidesLink = "?page=guides";

export function requestedGuideIndex(): boolean {
  return new URL(window.location.href).searchParams.get("page") === "guides";
}

/** Lists every guide on its own page, so the guides stay out of the start form's way. */
export function GuideLinks() {
  const { m: { content: { explainerGuides: t } } } = useMessages();
  useEffect(() => {
    const previous = document.title;
    document.title = t.indexTitle;
    return () => { document.title = previous; };
  }, [t]);
  return <article className="guides-page" aria-labelledby="guide-links-heading">
    <p className="step">{t.indexStep}</p>
    <h1 id="guide-links-heading">{t.indexHeading}</h1>
    <p className="lede">{t.indexLede}</p>
    <ul className="guide-cards">
      {GUIDE_SLUGS.map(slug => <li key={slug}>
        <a className="guide-card" href={guideLink(slug)} aria-describedby={`guide-card-${slug}`}>
          <span className="guide-card-title">{t.guides[slug].title}</span>
          <span className="guide-card-summary" id={`guide-card-${slug}`}>{t.guides[slug].summary}</span>
          <span className="guide-card-cta" aria-hidden="true">{t.readTheGuide} <span className="guide-card-arrow">→</span></span>
        </a>
      </li>)}
    </ul>
  </article>;
}

export function ExplainerGuide({ slug }: { slug: GuideSlug }) {
  const { m: { content: { explainerGuides: t } } } = useMessages();
  const guide = GUIDES[slug];
  // Read once like the intake does: removed from the address bar and held only in memory.
  const [referral] = useState(arrivalChannel);
  const Body = guide.body;
  const other = GUIDE_SLUGS.filter(each => each !== slug);
  return <article className="panel guide" aria-labelledby="guide-heading">
    <p className="step">{t.step}</p>
    <h1 id="guide-heading">{t.guides[slug].title}</h1>
    <p className="lede">{t.guides[slug].summary}</p>
    <Body t={t} />
    <p className="muted">{t.generalInformation}</p>
    <section className="guide-start" aria-labelledby="guide-start-heading">
      <h2 id="guide-start-heading">{t.startHeading}</h2>
      <p>{t.startText}</p>
      <p>{t.onYourSide}</p>
      <div className="actions">
        <a className="button-link" href={startLink(referral ?? guide.channel)}>{t.startLink}</a>
      </div>
    </section>
    <nav className="actions" aria-label={t.moreGuides}>
      {other.map(each => <a key={each} href={guideLink(each)}>{t.guides[each].title}</a>)}
      <a href={guidesLink}>{t.allGuides}</a>
      <a href="./">{t.backToStart}</a>
    </nav>
  </article>;
}

function OutTheDoorQuote({ t: { guides: { "out-the-door-quote": g } } }: { t: GuideText }) {
  const { fillNodes } = useMessages();
  return <>
    <section aria-labelledby="otd-parts-heading">
      <h2 id="otd-parts-heading">{g.partsHeading}</h2>
      <p>{g.partsIntro}</p>
      <ul>{g.parts.map(item => <li key={item.term}>{fillNodes(item.text, { term: <strong>{item.term}</strong> })}</li>)}</ul>
    </section>
    <figure className="guide-example">
      <figcaption>{g.exampleCaption}</figcaption>
      <dl className="money-list">
        <div><dt>{g.example.sellingPrice}</dt><dd>$24,000.00</dd></div>
        <div><dt>{g.example.dealerFees}</dt><dd>$600.00</dd></div>
        <div><dt>{g.example.addOns}</dt><dd>$1,200.00</dd></div>
        <div><dt>{g.example.taxes}</dt><dd>$1,500.00</dd></div>
        <div><dt>{g.example.governmentCharges}</dt><dd>$300.00</dd></div>
        <div><dt>{g.example.total}</dt><dd>$27,600.00</dd></div>
      </dl>
    </figure>
    <section aria-labelledby="otd-check-heading">
      <h2 id="otd-check-heading">{g.checkHeading}</h2>
      <ul>{g.checks.map(check => <li key={check}>{check}</li>)}</ul>
    </section>
    <section aria-labelledby="otd-product-heading">
      <h2 id="otd-product-heading">{g.productHeading}</h2>
      <p>{g.product}</p>
    </section>
  </>;
}

function DealerFees({ t: { guides: { "dealer-fees": g } } }: { t: GuideText }) {
  const { fillNodes } = useMessages();
  return <>
    <section aria-labelledby="fees-kinds-heading">
      <h2 id="fees-kinds-heading">{g.kindsHeading}</h2>
      <p>{g.kinds}</p>
    </section>
    <section aria-labelledby="fees-common-heading">
      <h2 id="fees-common-heading">{g.commonHeading}</h2>
      <ul>{g.common.map(item => <li key={item.term}>{fillNodes(item.text, { term: <strong>{item.term}</strong> })}</li>)}</ul>
    </section>
    <section aria-labelledby="fees-questions-heading">
      <h2 id="fees-questions-heading">{g.questionsHeading}</h2>
      <ol>{g.questions.map(question => <li key={question}>{question}</li>)}</ol>
      <p>{g.inWriting}</p>
    </section>
    <section aria-labelledby="fees-product-heading">
      <h2 id="fees-product-heading">{g.productHeading}</h2>
      <p>{g.product}</p>
    </section>
  </>;
}
