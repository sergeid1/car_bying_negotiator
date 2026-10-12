import { howItWorksLink } from "./HowItWorks";
import "./HomeHero.css";

export function HomeHero() {
  return <section className="home-hero" aria-labelledby="home-heading">
    <h1 id="home-heading">See the whole deal before you decide.</h1>
    <p className="lede">Check your dealer's quote, see the out-the-door total, and find the questions to ask.</p>
    <div className="home-actions">
      <button type="button" onClick={() => {
        const field = document.querySelector<HTMLInputElement>("#buying-case-intake input[name=buyerPostalCode]");
        field?.scrollIntoView?.({ block: "center" });
        field?.focus({ preventScroll: true });
      }}>Check my deal</button>
      <a href="#sample-report" onClick={() => window.dispatchEvent(new Event("open-sample-report"))}>See an example report</a>
      <a href={howItWorksLink}>How it works</a>
    </div>
    <ul className="home-points"><li>You make every decision</li><li>No account needed</li><li>We never work for dealers</li></ul>
  </section>;
}
