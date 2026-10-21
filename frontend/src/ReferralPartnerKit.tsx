import { FormEvent, useState } from "react";
import { isChannelCode, ReferralDisclosure } from "./AcquisitionChannel";
import { GUIDE_SLUGS, guideLink } from "./ExplainerGuides";
import { useMessages, type Messages } from "./i18n/LanguageProvider";

/**
 * The referral partner kit, reachable without a case at a stable `?page=referral-partners` link.
 * Its link builder works entirely in the page: it fetches nothing and writes nothing to cookies or
 * browser storage. Copying a link uses the clipboard only when the partner presses Copy.
 */
const PARAMETER = "page";
const SLUG = "referral-partners";

export const partnerKitLink = `?${PARAMETER}=${SLUG}`;
/** The link parameter the kit names; the same in every language. */
const CHANNEL_PARAMETER = "channel=";

/** Whether the address bar names the kit. An unknown page shows the usual start screen. */
export function requestedPartnerKit(): boolean {
  return new URL(window.location.href).searchParams.get(PARAMETER) === SLUG;
}

type PartnerLink = { label: string; address: string };
type Text = Messages["content"];

function partnerLinks(code: string, { referralPartnerKit: t, explainerGuides: { guides } }: Text,
  fill: ReturnType<typeof useMessages>["fill"]): PartnerLink[] {
  const base = `${window.location.origin}${window.location.pathname}`;
  return [
    { label: t.startScreen, address: `${base}?channel=${code}` },
    ...GUIDE_SLUGS.map(slug => ({ label: fill(t.guideLink, { title: guides[slug].title }), address: `${base}${guideLink(slug)}&channel=${code}` })),
  ];
}

export function ReferralPartnerKit() {
  const { m: { content: { referralPartnerKit: t, explainerGuides: { guides } } }, fillNodes } = useMessages();
  return <article className="panel guide partner-kit" aria-labelledby="partner-kit-heading">
    <p className="step">{t.step}</p>
    <h1 id="partner-kit-heading">{t.heading}</h1>
    <p className="lede">{t.lede}</p>

    <section aria-labelledby="partner-who-heading">
      <h2 id="partner-who-heading">{t.whoHeading}</h2>
      <ul>
        {t.who.map(item => <li key={item.term}>{fillNodes(item.text, { term: <strong>{item.term}</strong> })}</li>)}
      </ul>
      <p>{t.whoExcluded}</p>
    </section>

    <section aria-labelledby="partner-product-heading">
      <h2 id="partner-product-heading">{t.productHeading}</h2>
      {t.product.map(paragraph => <p key={paragraph}>{paragraph}</p>)}
    </section>

    <section aria-labelledby="partner-link-heading">
      <h2 id="partner-link-heading">{t.linkHeading}</h2>
      <p>{fillNodes(t.linkCode, { code: <code>{CHANNEL_PARAMETER}</code> })}</p>
      <p>{t.linkPrivacy}</p>
    </section>

    <section aria-labelledby="partner-buyer-heading">
      <h2 id="partner-buyer-heading">{t.buyerHeading}</h2>
      <p>{t.buyer}</p>
      <figure className="guide-example">
        <figcaption>{t.exampleCaption}</figcaption>
        <ReferralDisclosure text={t.exampleDisclosure} />
      </figure>
    </section>

    <section aria-labelledby="partner-terms-heading">
      <h2 id="partner-terms-heading">{t.termsHeading}</h2>
      <ul>{t.terms.map(term => <li key={term}>{term}</li>)}</ul>
    </section>

    <LinkBuilder />

    <nav className="actions" aria-label={t.moreLabel}>
      {GUIDE_SLUGS.map(slug => <a key={slug} href={guideLink(slug)}>{guides[slug].title}</a>)}
      <a href="./">{t.backToStart}</a>
    </nav>
  </article>;
}

function LinkBuilder() {
  const { m: { content }, fill } = useMessages();
  const t = content.referralPartnerKit.builder;
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
    <h2 id="partner-builder-heading">{t.heading}</h2>
    <p>{t.intro}</p>
    <form className="partner-builder" onSubmit={build} noValidate>
      <label>
        {t.codeLabel}
        <input value={code} onChange={event => setCode(event.target.value)} autoComplete="off"
          spellCheck={false} aria-invalid={invalid} aria-describedby="partner-code-format" />
      </label>
      <p id="partner-code-format" className="muted">{t.codeFormat}</p>
      <div className="actions"><button type="submit">{t.build}</button></div>
    </form>
    {invalid && <p role="alert">{t.invalid}</p>}
    {built && <ul className="partner-links" aria-label={fill(t.linksLabel, { code: built })}>
      {partnerLinks(built, content, fill).map(link => <CopyableLink key={link.address} {...link} />)}
    </ul>}
  </section>;
}

function CopyableLink({ label, address }: PartnerLink) {
  const { m: { content: { referralPartnerKit: { builder: t } } }, fill } = useMessages();
  const [status, setStatus] = useState("");
  return <li>
    <label>
      {label}
      <input readOnly value={address} onFocus={event => event.currentTarget.select()} />
    </label>
    <button type="button" className="btn-secondary" aria-label={fill(t.copyLabel, { label })} onClick={async () => {
      try { await navigator.clipboard.writeText(address); setStatus(t.copied); }
      catch { setStatus(t.copyUnavailable); }
    }}>{t.copy}</button>
    <p role="status">{status}</p>
  </li>;
}
