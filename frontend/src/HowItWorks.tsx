import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { exampleReportLink } from "./SampleReport";
import * as format from "./i18n/format";
import { content } from "./i18n/en/content";
import { useMessages, type Messages } from "./i18n/LanguageProvider";

/**
 * The how-it-works walkthrough, reachable without a case at `?page=how-it-works`. It is an ordered list
 * of steps first; with motion allowed on a wide screen it pins one scene beside the captions and swaps it
 * as each caption reaches the middle of the screen. Scenes are still placeholders until the operator's
 * clips arrive (ticket 109); a step's `media` then holds its compressed clip and poster frame.
 */
const PARAMETER = "page";
const SLUG = "how-it-works";

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
    ?? material.find(each => each.pointer?.endsWith(`/${key}`) || (key === "outTheDoorTotal" && each.pointer === "/inputs/outTheDoorTotalMinor"))?.minorUnits
    ?? null;
  const currency = economics?.outTheDoorTotal?.currency ?? material[0]?.currency ?? "USD";
  const reasons = Array.isArray(analysis.topReasons) ? (analysis.topReasons as unknown[]).filter((each): each is string => typeof each === "string") : [];
  return {
    total: amount("outTheDoorTotal"), sellingPrice: amount("sellingPrice"), dealerFees: amount("dealerFees"),
    addOns: amount("addOns"), taxes: amount("taxes"), governmentCharges: amount("governmentCharges"), currency,
    reasons: reasons.slice(0, 3), nextAction: typeof analysis.nextAction === "string" ? analysis.nextAction : null,
  };
}

type WalkthroughText = Messages["content"]["howItWorks"];

function Rows({ rows }: { rows: [string, ReactNode][] }) {
  return <dl className="walkthrough-rows">{rows.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl>;
}

/** The walkthrough's steps in the given language; each step's words come from `content.howItWorks.steps`. */
export function walkthroughSteps(t: WalkthroughText): WalkthroughStep[] {
  const { steps } = t;
  const money = (minor: number | null, currency: string) => minor == null ? t.notInExample : format.money(minor, { currency });
  return [
    {
      id: "start", title: steps.start.title, scene: "kitchen", calm: "right",
      caption: steps.start.caption,
      sceneAlt: steps.start.sceneAlt,
      screen: () => <>
        <p className="walkthrough-screen-title">{steps.start.screenTitle}</p>
        <Rows rows={[[steps.start.whichVehicle, steps.start.whichVehicleValue], [steps.start.haveQuote, steps.start.haveQuoteValue]]} />
      </>,
    },
    {
      id: "request", title: steps.request.title, scene: "typing", calm: "left",
      caption: steps.request.caption,
      sceneAlt: steps.request.sceneAlt,
      screen: () => <>
        <p className="walkthrough-screen-title">{steps.request.screenTitle}</p>
        <p className="walkthrough-draft">{steps.request.draft}</p>
        <p className="walkthrough-screen-note">{steps.request.note}</p>
      </>,
    },
    {
      id: "upload", title: steps.upload.title, scene: "paperwork", calm: "right",
      caption: steps.upload.caption,
      sceneAlt: steps.upload.sceneAlt,
      screen: () => <>
        <p className="walkthrough-screen-title">{steps.upload.screenTitle}</p>
        <Rows rows={[[steps.upload.quoteFile, steps.upload.quoteFileValue], [steps.upload.photo, steps.upload.photoValue]]} />
      </>,
    },
    {
      id: "confirm", title: steps.confirm.title, scene: "tracing", calm: "left",
      caption: steps.confirm.caption,
      sceneAlt: steps.confirm.sceneAlt,
      screen: figures => <>
        <p className="walkthrough-screen-title">{steps.confirm.screenTitle}</p>
        <div className="walkthrough-outline" aria-hidden="true"><span /><span className="walkthrough-outline-mark" /><span /></div>
        <Rows rows={[
          [steps.confirm.sellingPrice, <>{money(figures.sellingPrice, figures.currency)} <span className="walkthrough-badge">{steps.confirm.confirmed}</span></>],
          [steps.confirm.dealerFees, <>{money(figures.dealerFees, figures.currency)} <span className="walkthrough-badge walkthrough-badge-open">{steps.confirm.waiting}</span></>],
        ]} />
      </>,
    },
    {
      id: "report", title: steps.report.title, scene: "couch", calm: "right",
      caption: steps.report.caption,
      sceneAlt: steps.report.sceneAlt,
      screen: figures => <>
        <p className="walkthrough-screen-title">{steps.report.screenTitle}</p>
        <p className="walkthrough-total">{money(figures.total, figures.currency)}</p>
        <p className="walkthrough-screen-note">{steps.report.total}</p>
        {figures.reasons.length > 0 && <ol className="walkthrough-reasons">{figures.reasons.map(reason => <li key={reason}>{reason}</li>)}</ol>}
        {figures.nextAction && <p><NextAction text={steps.report} action={figures.nextAction} /></p>}
      </>,
    },
    {
      id: "reply", title: steps.reply.title, scene: "phone", calm: "left",
      caption: steps.reply.caption,
      sceneAlt: steps.reply.sceneAlt,
      screen: figures => <>
        <p className="walkthrough-screen-title">{steps.reply.screenTitle}</p>
        <Rows rows={[[steps.reply.response, steps.reply.responseValue]]} />
        <p className="walkthrough-draft"><Filled message={steps.reply.draft}
          values={{ dealerFees: money(figures.dealerFees, figures.currency), addOns: money(figures.addOns, figures.currency) }} /></p>
        <p className="walkthrough-screen-note">{steps.reply.note}</p>
      </>,
    },
    {
      id: "desk", title: steps.desk.title, scene: "desk", calm: "right",
      caption: steps.desk.caption,
      sceneAlt: steps.desk.sceneAlt,
      screen: figures => <>
        <p className="walkthrough-screen-title">{steps.desk.screenTitle}</p>
        <Rows rows={[
          [steps.desk.agreedPrice, money(figures.sellingPrice, figures.currency)],
          [steps.desk.agreedTotal, money(figures.total, figures.currency)],
          [steps.desk.tradeIn, steps.desk.tradeInValue],
        ]} />
      </>,
    },
  ];
}

function NextAction({ text, action }: { text: WalkthroughText["steps"]["report"]; action: string }) {
  const { fillNodes } = useMessages();
  return <>{fillNodes(text.nextAction, { label: <strong>{text.nextActionLabel}</strong>, action })}</>;
}

function Filled({ message, values }: { message: WalkthroughText["steps"]["reply"]["draft"]; values: { dealerFees: string; addOns: string } }) {
  const { fill } = useMessages();
  return <>{fill(message, values)}</>;
}

/** The English steps, for tests and callers that pass their own steps. */
export const STEPS: WalkthroughStep[] = walkthroughSteps(content.howItWorks);

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

export function HowItWorks({ steps: given }: { steps?: WalkthroughStep[] }) {
  const { m: { content: { howItWorks: t } } } = useMessages();
  const translated = useMemo(() => walkthroughSteps(t), [t]);
  const steps = given ?? translated;
  const { figures, failed } = useSampleFigures();
  const pinned = usePinned();
  const [active, setActive] = useState(0);
  const [near, setNear] = useState<Set<number>>(() => new Set([0]));
  const items = useRef<(HTMLLIElement | null)[]>([]);
  const videos = useRef<(HTMLVideoElement | null)[]>([]);
  const playClips = pinned && clipsAllowed();

  useEffect(() => {
    const previous = document.title;
    document.title = t.title;
    return () => { document.title = previous; };
  }, [t]);

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
    <p className="step">{t.step}</p>
    <h1 id="how-it-works-heading">{t.heading}</h1>
    <p className="lede">{t.lede}</p>
    <nav aria-label={t.stepsLabel}>
      <ol className="walkthrough-index">
        {steps.map(step => <li key={step.id}><a href={`#how-step-${step.id}`}>{step.title}</a></li>)}
      </ol>
    </nav>
    {failed && <p className="notice" role="note">{t.unavailable}</p>}

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
              <figcaption>{t.screenCaption}</figcaption>
              {figures ? step.screen(figures) : <p className="walkthrough-screen-note">{failed ? t.figuresUnavailable : t.loading}</p>}
            </figure>
          </div>
        </div>
      </li>)}
    </ol>

    <nav className="actions walkthrough-next" aria-label={t.nextLabel}>
      <a className="walkthrough-primary" href="./">{t.startWithQuote}</a>
      <a href={exampleReportLink}>{t.seeSample}</a>
    </nav>
  </article>;
}

function Scene({ step, index, clip, videoRef }: { step: WalkthroughStep; index: number; clip: boolean; videoRef: (element: HTMLVideoElement | null) => void }) {
  const { m: { content: { howItWorks: t } }, fill } = useMessages();
  return <div className="walkthrough-scene" role="img" aria-label={step.media ? step.sceneAlt : fill(t.placeholderScene, { scene: step.sceneAlt })}>
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
  const { m: { content: { howItWorks: { card: t } } } } = useMessages();
  const [hidden, setHidden] = useState(wasDismissed);
  if (hidden) return null;
  return <section className="panel how-it-works-card" aria-labelledby="how-it-works-card-heading">
    <div>
      <h2 id="how-it-works-card-heading">{t.heading}</h2>
      <p>{t.text}</p>
    </div>
    <div className="actions">
      <a href={howItWorksLink}>{t.link}</a>
      <button type="button" className="btn-ghost" aria-label={t.dismissLabel} onClick={() => {
        try { window.localStorage.setItem(DISMISSED, "1"); } catch { /* Without storage the card returns on the next visit. */ }
        setHidden(true);
      }}>{t.dismiss}</button>
    </div>
  </section>;
}
