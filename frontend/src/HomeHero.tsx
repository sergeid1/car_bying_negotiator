import { XrayIllustration } from "./XrayIllustration";
import "./HomeHero.css";

export function HomeHero() {
  return <section className="home-hero" aria-labelledby="home-heading">
    <div className="hero-copy">
      <h1 id="home-heading">See the whole deal before you decide.</h1>
      <p className="lede">Check your dealer's quote line by line. See the full out-the-door total, spot the fees and
        add-ons worth questioning, and get the questions to ask before you sign. You send every email and make
        every decision.</p>
      <ul className="home-points"><li>You make every decision</li><li>No account needed</li><li>We never work for dealers</li></ul>
    </div>
    <XrayIllustration />
  </section>;
}
