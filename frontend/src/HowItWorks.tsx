import { useEffect, useRef, useState, type ReactNode } from "react";
import { exampleReportLink } from "./SampleReport";
import * as format from "./i18n/format";

/**
 * The how-it-works walkthrough, reachable without a case at `?page=how-it-works`. It is an ordered list
 * of steps first; with motion allowed on a wide screen it pins one scene beside the captions and swaps it
 * as each caption reaches the middle of the screen. Scenes are still placeholders until the operator's
 * clips arrive (ticket 109); a step's `media` then holds its compressed clip and poster frame.
 */
const PARAMETER = "page";
const SLUG = "how-it-works";
const TITLE = "How it works · otdog";

export const howItWorksLink = `?${PARAMETER}=${SLUG}`;

/** Whether the address bar names the walkthrough. An unknown page shows the usual start screen. */
export function requestedHowItWorks(): boolean {
  return new URL(window.location.href).searchParams.get(PARAMETER) === SLUG;
}

/** Amounts in minor units from the sample report, the same synthetic case the start screen's example shows. */
export type SampleFigures = {
  total: number | null; sellingPrice: number | null; dealerFees: number | null; addOns: number | null;
  taxes: number | null; governmentCharges: number | null; currency: string; reasons: string[]; nextAction: string | null;
};

type Media = { clip: string; poster: string };
export type WalkthroughStep = {
  id: string; title: string; caption: string; scene: SceneKind; sceneAlt: string;
  /** The side of the scene left calm for the product screen, from ticket 109's table. */
  calm: "left" | "right";
  screen: (figures: SampleFigures) => ReactNode;
  media?: Media;
};
type SceneKind = "kitchen" | "typing" | "paperwork" | "tracing" | "couch" | "phone" | "desk";

const AMOUNT_KEYS = ["sellingPrice", "dealerFees", "addOns", "taxes", "governmentCharges", "outTheDoorTotal"] as const;

/** Reads both report shapes the sample endpoint has served: synthetic economics, or core material amounts. */
export function sampleFigures(body: unknown): SampleFigures | undefined {
  const analysis = (body as { report?: { analysis?: Record<string, unknown> } } | undefined)?.report?.analysis;
  if (!analysis) return undefined;
  const economics = analysis.economics as Record<string, { minorUnits?: number | null; currency?: string } | undefined> | undefined;
  const material = (analysis.materialAmounts ?? []) as { label?: string | null; pointer?: string; minorUnits: number | null; currency: string }[];
  const amount = (key: typeof AMOUNT_KEYS[number]) => economics?.[key]?.minorUnits
    ?? material.find(each => each.pointer?.endsWith(`/${key}`) || (key === "outTheDoorTotal" && each.label === "Out-the-door total"))?.minorUnits
    ?? null;
  const currency = economics?.outTheDoorTotal?.currency ?? material[0]?.currency ?? "USD";
  const reasons = Array.isArray(analysis.topReasons) ? (analysis.topReasons as unknown[]).filter((each): each is string => typeof each === "string") : [];
  return {
    total: amount("outTheDoorTotal"), sellingPrice: amount("sellingPrice"), dealerFees: amount("dealerFees"),
    addOns: amount("addOns"), taxes: amount("taxes"), governmentCharges: amount("governmentCharges"), currency,
    reasons: reasons.slice(0, 3), nextAction: typeof analysis.nextAction === "string" ? analysis.nextAction : null,
  };
}

function money(minor: number | null, currency: string) {
  return minor == null ? "Not in this example" : format.money(minor, { currency });
}

function Rows({ rows }: { rows: [string, ReactNode][] }) {
  return <dl className="walkthrough-rows">{rows.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl>;
}

export const STEPS: WalkthroughStep[] = [
  {
    id: "start", title: "Start with the car or the quote", scene: "kitchen", calm: "right",
    caption: "Describe the car you want, or start from a quote you already have. You do not need an account.",
    sceneAlt: "A Buyer at a kitchen table in the evening, a laptop open beside a mug.",
    screen: () => <>
      <p className="walkthrough-screen-title">Start your Buying Case</p>
      <Rows rows={[["Which vehicle?", "By VIN, or by year, make and model"], ["Already have a quote?", "Upload it and start from there"]]} />
    </>,
  },
  {
    id: "request", title: "Ask dealers for an itemized quote", scene: "typing", calm: "left",
    caption: "Ask dealers for an itemized out-the-door quote. otdog drafts the request; you send it from your own email.",
    sceneAlt: "Hands typing on a laptop whose screen is a soft, blank glow.",
    screen: () => <>
      <p className="walkthrough-screen-title">Request an itemized quote</p>
      <p className="walkthrough-draft">Please send an itemized out-the-door quote for this car: the selling price, each dealer fee,
        any add-ons, taxes, and government charges.</p>
      <p className="walkthrough-screen-note">You copy this request and send it from your own email.</p>
    </>,
  },
  {
    id: "upload", title: "Add the dealer's paperwork", scene: "paperwork", calm: "right",
    caption: "Upload the dealer's quote, or photograph the paperwork with your phone.",
    sceneAlt: "Seen from above, a phone held over printed paperwork whose text is blurred.",
    screen: () => <>
      <p className="walkthrough-screen-title">Add the Dealer's paperwork</p>
      <Rows rows={[["dealer-quote.pdf", "2 pages · read"], ["Photo of the worksheet", "1 page · read"]]} />
    </>,
  },
  {
    id: "confirm", title: "Confirm each number", scene: "tracing", calm: "left",
    caption: "otdog reads each amount and outlines where it found it on the page. You confirm or correct every one.",
    sceneAlt: "Close up, a finger tracing one line of a printed worksheet beside a phone.",
    screen: figures => <>
      <p className="walkthrough-screen-title">Check the terms against the paperwork</p>
      <div className="walkthrough-outline" aria-hidden="true"><span /><span className="walkthrough-outline-mark" /><span /></div>
      <Rows rows={[
        ["Selling price", <>{money(figures.sellingPrice, figures.currency)} <span className="walkthrough-badge">Confirmed by you</span></>],
        ["Dealer fees", <>{money(figures.dealerFees, figures.currency)} <span className="walkthrough-badge walkthrough-badge-open">Waiting for you</span></>],
      ]} />
    </>,
  },
  {
    id: "report", title: "Read the report", scene: "couch", calm: "right",
    caption: "See the out-the-door total, what stands out, and the questions to ask the dealer.",
    sceneAlt: "A Buyer on a couch reading a phone, seen from the side and behind.",
    screen: figures => <>
      <p className="walkthrough-screen-title">Deal Analysis Report</p>
      <p className="walkthrough-total">{money(figures.total, figures.currency)}</p>
      <p className="walkthrough-screen-note">Out-the-door total</p>
      {figures.reasons.length > 0 && <ol className="walkthrough-reasons">{figures.reasons.map(reason => <li key={reason}>{reason}</li>)}</ol>}
      {figures.nextAction && <p><strong>Next action:</strong> {figures.nextAction}</p>}
    </>,
  },
  {
    id: "reply", title: "Reply with a draft you edit", scene: "phone", calm: "left",
    caption: "When the dealer answers, otdog drafts a reply from the report's figures. You edit it and send it yourself.",
    sceneAlt: "Hands holding a phone, a thumb resting near its bottom edge.",
    screen: figures => <>
      <p className="walkthrough-screen-title">Reply to a Dealer response</p>
      <Rows rows={[["Dealer's response", "This is the best we can do."]]} />
      <p className="walkthrough-draft">Thank you. The quote lists dealer fees of {money(figures.dealerFees, figures.currency)} and
        add-ons of {money(figures.addOns, figures.currency)}. Could you explain each one and send a revised itemized quote?</p>
      <p className="walkthrough-screen-note">You edit the reply, copy it, and send it from your own email.</p>
    </>,
  },
  {
    id: "desk", title: "Check the four-square at the desk", scene: "desk", calm: "right",
    caption: "At the dealership, photograph the four-square. otdog checks it against the price you agreed, including your trade-in.",
    sceneAlt: "A plain showroom desk with a four-square sheet on it and a phone raised over it.",
    screen: figures => <>
      <p className="walkthrough-screen-title">Desk Check</p>
      <Rows rows={[
        ["Agreed selling price", money(figures.sellingPrice, figures.currency)],
        ["Agreed out-the-door total", money(figures.total, figures.currency)],
        ["Trade-in", "Checked against your trade allowance and payoff"],
      ]} />
    </>,
  },
];

function useSampleFigures() {
  const [figures, setFigures] = useState<SampleFigures>();
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    fetch("/api/v1/sample-report", { signal: controller.signal }).then(async response => {
      if (!response.ok) throw new Error();
      const parsed = sampleFigures(await response.json());
      if (!parsed) throw new Error();
      setFigures(parsed);
    }).catch(() => { if (!controller.signal.aborted) setFailed(true); });
    return () => controller.abort();
  }, []);
  return { figures, failed };
}

function matches(query: string) {
  return typeof window.matchMedia === "function" && window.matchMedia(query).matches;
}

/** Pinning and clip scrubbing need motion, room for two columns, and IntersectionObserver. */
const PINNED_QUERY = "(prefers-reduced-motion: no-preference) and (min-width: 760px)";

function usePinned() {
  const [pinned, setPinned] = useState(() => "IntersectionObserver" in window && matches(PINNED_QUERY));
  useEffect(() => {
    if (!("IntersectionObserver" in window) || typeof window.matchMedia !== "function") return;
    const query = window.matchMedia(PINNED_QUERY);
    const change = () => setPinned(query.matches);
    query.addEventListener?.("change", change);
    return () => query.removeEventListener?.("change", change);
  }, []);
  return pinned;
}

/** Clips are never downloaded under reduced motion or on a Save-Data connection. */
function clipsAllowed() {
  const connection = (navigator as Navigator & { connection?: { saveData?: boolean } }).connection;
  return !connection?.saveData && !matches("(prefers-reduced-motion: reduce)");
}

export function HowItWorks({ steps = STEPS }: { steps?: WalkthroughStep[] }) {
  const { figures, failed } = useSampleFigures();
  const pinned = usePinned();
  const [active, setActive] = useState(0);
  const [near, setNear] = useState<Set<number>>(() => new Set([0]));
  const items = useRef<(HTMLLIElement | null)[]>([]);
  const videos = useRef<(HTMLVideoElement | null)[]>([]);
  const playClips = pinned && clipsAllowed();

  useEffect(() => {
    const previous = document.title;
    document.title = TITLE;
    return () => { document.title = previous; };
  }, []);

  // The step whose caption crosses the middle of the screen is the one on stage.
  useEffect(() => {
    if (!pinned) return;
    const observer = new IntersectionObserver(entries => {
      for (const entry of entries) if (entry.isIntersecting) setActive(items.current.indexOf(entry.target as HTMLLIElement));
    }, { rootMargin: "-50% 0px -50% 0px" });
    items.current.forEach(item => item && observer.observe(item));
    return () => observer.disconnect();
  }, [pinned, steps]);

  // Later clips load only as their step approaches.
  useEffect(() => {
    if (!playClips) return;
    const observer = new IntersectionObserver(entries => {
      const arriving = entries.filter(entry => entry.isIntersecting).map(entry => items.current.indexOf(entry.target as HTMLLIElement));
      if (arriving.length) setNear(current => new Set([...current, ...arriving]));
    }, { rootMargin: "100% 0px" });
    items.current.forEach(item => item && observer.observe(item));
    return () => observer.disconnect();
  }, [playClips, steps]);

  // The active clip follows scroll position through its caption instead of playing on its own.
  useEffect(() => {
    if (!playClips) return;
    let frame = 0;
    const scrub = () => {
      frame = 0;
      const item = items.current[active], video = videos.current[active];
      if (!item || !video || !Number.isFinite(video.duration)) return;
      const box = item.getBoundingClientRect();
      const progress = Math.min(1, Math.max(0, (window.innerHeight / 2 - box.top) / Math.max(1, box.height)));
      video.currentTime = progress * video.duration;
    };
    const onScroll = () => { if (!frame) frame = window.requestAnimationFrame(scrub); };
    window.addEventListener("scroll", onScroll, { passive: true });
    scrub();
    return () => { window.removeEventListener("scroll", onScroll); window.cancelAnimationFrame(frame); };
  }, [playClips, active]);

  return <article className="panel guide how-it-works" aria-labelledby="how-it-works-heading">
    <p className="step">How it works</p>
    <h1 id="how-it-works-heading">Buying a car with otdog, step by step</h1>
    <p className="lede">
      You do the asking, the checking, and the sending; otdog reads the paperwork and checks the numbers. Every
      screen below is an example built from a synthetic case, not a real Buyer's deal.
    </p>
    <nav aria-label="Steps">
      <ol className="walkthrough-index">
        {steps.map(step => <li key={step.id}><a href={`#how-step-${step.id}`}>{step.title}</a></li>)}
      </ol>
    </nav>
    {failed && <p className="notice" role="note">The example figures are unavailable right now. The steps still describe what you do.</p>}

    <ol className={`walkthrough${pinned ? " walkthrough-pinned" : ""}`}>
      {steps.map((step, index) => <li key={step.id} id={`how-step-${step.id}`} tabIndex={-1}
        ref={element => { items.current[index] = element; }} onFocus={() => setActive(index)}
        className={index === active ? "walkthrough-step is-active" : "walkthrough-step"}>
        <div className="walkthrough-caption">
          <h2>{step.title}</h2>
          <p>{step.caption}</p>
        </div>
        <div className="walkthrough-stage">
          <div className={`walkthrough-frame walkthrough-calm-${step.calm}`}>
            <Scene step={step} index={index} clip={playClips && near.has(index)}
              videoRef={element => { videos.current[index] = element; }} />
            <figure className="walkthrough-screen">
              <figcaption>Example screen from a synthetic case</figcaption>
              {figures ? step.screen(figures) : <p className="walkthrough-screen-note">{failed ? "Example figures unavailable." : "Loading the example…"}</p>}
            </figure>
          </div>
        </div>
      </li>)}
    </ol>

    <nav className="actions walkthrough-next" aria-label="Get started">
      <a className="walkthrough-primary" href="./">Start with your quote</a>
      <a href={exampleReportLink}>See a sample report</a>
    </nav>
  </article>;
}

function Scene({ step, index, clip, videoRef }: { step: WalkthroughStep; index: number; clip: boolean; videoRef: (element: HTMLVideoElement | null) => void }) {
  return <div className="walkthrough-scene" role="img" aria-label={step.media ? step.sceneAlt : `Placeholder scene: ${step.sceneAlt}`}>
    {step.media
      ? clip
        ? <video ref={videoRef} src={step.media.clip} poster={step.media.poster} muted playsInline preload="auto" aria-hidden="true" />
        : <img src={step.media.poster} alt="" loading={index === 0 ? "eager" : "lazy"} />
      : <Placeholder kind={step.scene} />}
  </div>;
}

/** A still, warm, wordless stand-in for each clip, drawn so the page weighs almost nothing until footage arrives. */
function Placeholder({ kind }: { kind: SceneKind }) {
  return <svg viewBox="0 0 160 90" aria-hidden="true" focusable="false" preserveAspectRatio="xMidYMid slice">
    <rect width="160" height="90" className="scene-wall" />
    <rect y="58" width="160" height="32" className="scene-table" />
    {kind === "kitchen" && <><path d="M30 58 l8 -24 h34 l-8 24 z" className="scene-device" /><rect x="76" y="48" width="8" height="10" rx="2" className="scene-object" /></>}
    {kind === "typing" && <><path d="M88 58 l8 -24 h34 l-8 24 z" className="scene-glow" /><ellipse cx="104" cy="64" rx="14" ry="4" className="scene-hand" /></>}
    {kind === "paperwork" && <><rect x="28" y="40" width="38" height="46" className="scene-paper" /><rect x="38" y="20" width="18" height="30" rx="3" className="scene-device" /></>}
    {kind === "tracing" && <><rect x="96" y="44" width="40" height="44" className="scene-paper" /><rect x="100" y="60" width="32" height="3" className="scene-line" /><ellipse cx="124" cy="62" rx="5" ry="3" className="scene-hand" /></>}
    {kind === "couch" && <><rect x="14" y="44" width="70" height="22" rx="6" className="scene-object" /><circle cx="46" cy="36" r="8" className="scene-hand" /><rect x="54" y="38" width="6" height="10" rx="1" className="scene-device" /></>}
    {kind === "phone" && <><rect x="104" y="26" width="22" height="40" rx="4" className="scene-device" /><ellipse cx="115" cy="66" rx="12" ry="5" className="scene-hand" /></>}
    {kind === "desk" && <><rect x="22" y="52" width="40" height="30" className="scene-paper" /><path d="M42 52 v30 M22 67 h40" className="scene-stroke" /><rect x="34" y="24" width="16" height="26" rx="3" className="scene-device" /><circle cx="128" cy="34" r="12" className="scene-blur" /></>}
  </svg>;
}

const DISMISSED = "otdog.how-it-works-card.dismissed";

function wasDismissed() {
  try { return window.localStorage.getItem(DISMISSED) === "1"; } catch { return false; }
}

/** The start screen's invitation for a first-time visitor. It sits in the column and never covers the start form. */
export function HowItWorksCard() {
  const [hidden, setHidden] = useState(wasDismissed);
  if (hidden) return null;
  return <section className="panel how-it-works-card" aria-labelledby="how-it-works-card-heading">
    <div>
      <h2 id="how-it-works-card-heading">New here? See how it works</h2>
      <p>Seven short steps, from asking for a quote to checking the paperwork at the desk.</p>
    </div>
    <div className="actions">
      <a href={howItWorksLink}>See how it works</a>
      <button type="button" className="btn-ghost" aria-label="Dismiss the how it works card" onClick={() => {
        try { window.localStorage.setItem(DISMISSED, "1"); } catch { /* Without storage the card returns on the next visit. */ }
        setHidden(true);
      }}>Dismiss</button>
    </div>
  </section>;
}
