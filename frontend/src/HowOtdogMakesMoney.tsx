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
  return <article className="panel guide how-otdog-makes-money" aria-labelledby="money-heading">
    <p className="step">About otdog</p>
    <h1 id="money-heading">How otdog makes money</h1>
    <p className="lede">
      otdog is free for you. Here is how we plan to pay for it, and the rules we hold ourselves to.
    </p>

    <section aria-labelledby="money-now-heading">
      <h2 id="money-now-heading">Right now</h2>
      <p>
        Today otdog earns no money from anyone. We pay to run it ourselves.
      </p>
    </section>

    <section aria-labelledby="money-quotes-heading">
      <h2 id="money-quotes-heading">Car insurance and loan quotes</h2>
      <p>
        We're adding a way to compare car insurance quotes and loan pre-approvals from many companies with
        one application, through a licensed comparison partner. The insurance and lending companies you
        see there, or our partner, may pay otdog. That is how otdog stays free. You never pay otdog
        anything, whether or not you take an offer.
      </p>
    </section>

    <section aria-labelledby="money-never-heading">
      <h2 id="money-never-heading">What pay never changes</h2>
      <ul>
        <li>
          Insurance quotes are listed by price for the same coverage, and loans by total cost. A company
          that pays us more does not move up the list.
        </li>
        <li>No company can pay to change a finding, a rating, or anything otdog says about your deal.</li>
        <li>If the dealer's financing costs less than a pre-approval you got through otdog, otdog tells you.</li>
      </ul>
    </section>

    <section aria-labelledby="money-dealers-heading">
      <h2 id="money-dealers-heading">Dealers never pay us</h2>
      <p>
        No dealer, dealer group, or dealer finance office pays otdog, not for leads, placement,
        appointments, or sales. We don't work with dealers. We help people.
      </p>
    </section>

    <section aria-labelledby="money-data-heading">
      <h2 id="money-data-heading">Your information</h2>
      <p>
        We never sell your information. An insurance or loan application goes only to our licensed partner
        and the companies quoting through it, and only when you agree. Nothing about you goes to a dealer
        unless you send it yourself.
      </p>
    </section>

    <section aria-labelledby="money-free-heading">
      <h2 id="money-free-heading">Is otdog really free?</h2>
      <p>Yes. Every part of otdog works the same whether or not you ever get a quote.</p>
    </section>
  </article>;
}
