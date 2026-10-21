import { useMessages } from "./i18n/LanguageProvider";

/**
 * How otdog makes money, reachable without a case at a stable `?page=how-otdog-makes-money` link and from the
 * footer. It states the revenue model in spec 2.3 (section 1.3): dealers never pay, insurance and lending
 * companies may, and pay never changes what otdog shows. It names no company and no amount.
 */
const PARAMETER = "page";
const SLUG = "how-otdog-makes-money";

export const howOtdogMakesMoneyLink = `?${PARAMETER}=${SLUG}`;

/** Whether the address bar names this page. An unknown page shows the usual start screen. */
export function requestedHowOtdogMakesMoney(): boolean {
  return new URL(window.location.href).searchParams.get(PARAMETER) === SLUG;
}

export function HowOtdogMakesMoney() {
  const { m: { content: { howOtdogMakesMoney: t } } } = useMessages();
  return <article className="panel guide how-otdog-makes-money" aria-labelledby="money-heading">
    <p className="step">{t.step}</p>
    <h1 id="money-heading">{t.heading}</h1>
    <p className="lede">{t.lede}</p>

    <section aria-labelledby="money-now-heading">
      <h2 id="money-now-heading">{t.nowHeading}</h2>
      <p>{t.now}</p>
    </section>

    <section aria-labelledby="money-quotes-heading">
      <h2 id="money-quotes-heading">{t.quotesHeading}</h2>
      <p>{t.quotes}</p>
    </section>

    <section aria-labelledby="money-never-heading">
      <h2 id="money-never-heading">{t.neverHeading}</h2>
      <ul>{t.never.map(item => <li key={item}>{item}</li>)}</ul>
    </section>

    <section aria-labelledby="money-dealers-heading">
      <h2 id="money-dealers-heading">{t.dealersHeading}</h2>
      <p>{t.dealers}</p>
    </section>

    <section aria-labelledby="money-data-heading">
      <h2 id="money-data-heading">{t.dataHeading}</h2>
      <p>{t.data}</p>
    </section>

    <section aria-labelledby="money-free-heading">
      <h2 id="money-free-heading">{t.freeHeading}</h2>
      <p>{t.free}</p>
    </section>
  </article>;
}
