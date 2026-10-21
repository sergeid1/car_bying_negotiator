import { XrayIllustration } from "./XrayIllustration";
import "./HomeHero.css";
import { useMessages } from "./i18n/LanguageProvider";

export function HomeHero() {
  const { m } = useMessages();
  return <section className="home-hero" aria-labelledby="home-heading">
    <div className="hero-copy">
      <h1 id="home-heading">{m.home.heading}</h1>
      <p className="lede">{m.home.lede}</p>
      <ul className="home-points"><li>{m.home.pointDecisions}</li><li>{m.home.pointNoAccount}</li><li>{m.home.pointNoDealers}</li></ul>
    </div>
    <XrayIllustration />
  </section>;
}
