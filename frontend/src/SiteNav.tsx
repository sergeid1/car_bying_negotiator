import { useEffect, useRef, useState, type MouseEvent as ReactMouseEvent, type PointerEvent as ReactPointerEvent } from "react";
import { guidesLink } from "./ExplainerGuides";
import { howItWorksLink } from "./HowItWorks";
import { exampleReportLink } from "./SampleReport";
import "./SiteNav.css";

export type SitePage = "home" | "example-report" | "how-it-works" | "guides" | "other";

/** `short` is what a phone shows; the accessible name stays the full one and contains it. */
const TABS: { page: SitePage; label: string; short: string; href: string; name: string }[] = [
  { page: "home", label: "Check my deal", short: "My deal", href: "./#start", name: "Check my deal" },
  { page: "example-report", label: "Example report", short: "Example", href: exampleReportLink, name: "See an example report" },
  { page: "how-it-works", label: "How it works", short: "How it works", href: howItWorksLink, name: "How it works" },
  { page: "guides", label: "Guides", short: "Guides", href: guidesLink, name: "Guides" },
];

function focusStartForm() {
  const field = document.querySelector<HTMLInputElement>("#buying-case-intake input[name=buyerPostalCode]");
  field?.scrollIntoView?.({ block: "center" });
  field?.focus({ preventScroll: true });
}

/** Moves to another tab's page without reloading, so the pill can glide there; the App re-reads the address on popstate. */
function go(href: string) {
  window.history.pushState(null, "", href);
  window.dispatchEvent(new PopStateEvent("popstate"));
  if (!href.endsWith("#start")) window.scrollTo?.({ top: 0 });
}

/**
 * The site's four tabs, kept in the header on every page without an open case, drawn like an iOS segmented
 * control: equal segments and a raised pill that glides to the tapped tab. The pill can also be pressed and
 * dragged; it follows the pointer and settles on the nearest tab when let go.
 */
export function SiteNav({ page }: { page: SitePage }) {
  const track = useRef<HTMLElement>(null);
  const drag = useRef<{ pointer: number; startX: number; moved: boolean } | undefined>(undefined);
  const index = TABS.findIndex(tab => tab.page === page);
  const [pressed, setPressed] = useState(false);
  const [offset, setOffset] = useState<number>();

  // Another page's "Check my deal" arrives at #start and lands in the start form.
  useEffect(() => {
    if (page === "home" && window.location.hash === "#start") focusStartForm();
  }, [page]);

  function segmentWidth() {
    const element = track.current;
    if (!element) return 0;
    const style = getComputedStyle(element);
    return (element.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight)) / TABS.length;
  }

  function choose(target: number) {
    const tab = TABS[target];
    if (tab.page === "home" && page === "home") { focusStartForm(); return; }
    go(tab.href);
  }

  function onClick(event: ReactMouseEvent, target: number) {
    if (event.defaultPrevented || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey || event.button !== 0) return;
    event.preventDefault();
    if (target !== index) choose(target);
  }

  function onPointerDown(event: ReactPointerEvent) {
    if (index < 0 || (event.pointerType === "mouse" && event.button !== 0)) return;
    const onPill = (event.target as HTMLElement).closest(".is-shown");
    if (!onPill) return;
    drag.current = { pointer: event.pointerId, startX: event.clientX, moved: false };
    setPressed(true);
  }

  function onPointerMove(event: ReactPointerEvent) {
    const state = drag.current;
    if (!state || state.pointer !== event.pointerId) return;
    const distance = event.clientX - state.startX;
    if (!state.moved && Math.abs(distance) < 4) return;
    if (!state.moved) { state.moved = true; track.current?.setPointerCapture?.(event.pointerId); }
    const width = segmentWidth();
    setOffset(Math.min(Math.max(index * width + distance, 0), (TABS.length - 1) * width));
  }

  function onPointerUp(event: ReactPointerEvent) {
    const state = drag.current;
    drag.current = undefined;
    setPressed(false);
    if (!state?.moved) return;
    track.current?.releasePointerCapture?.(event.pointerId);
    const width = segmentWidth();
    const target = width > 0 ? Math.round((offset ?? 0) / width) : index;
    setOffset(undefined);
    // The click that follows a drag lands on the track, not a tab, so the move is made here.
    if (target !== index) choose(target);
  }

  function cancel() { drag.current = undefined; setPressed(false); setOffset(undefined); }

  // While the pill is dragged, the label it sits over is the one drawn as chosen.
  const width = offset === undefined ? 0 : segmentWidth();
  const shown = offset === undefined || width === 0 ? index : Math.round(offset / width);

  return <nav className="site-switch" aria-label="Main" ref={track}
    data-dragging={offset !== undefined ? "" : undefined} data-pressed={pressed ? "" : undefined}
    onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerUp} onPointerCancel={cancel}>
    {index >= 0 && <span className="site-switch-thumb" aria-hidden="true"
      style={{ transform: offset === undefined ? `translateX(${index * 100}%)` : `translateX(${offset}px)` }} />}
    {TABS.map((tab, position) => {
      const current = position === index;
      const props = { className: position === shown ? "site-switch-item is-shown" : "site-switch-item", draggable: false,
        "aria-label": tab.name };
      const label = <><span className="site-switch-full">{tab.label}</span><span className="site-switch-short">{tab.short}</span></>;
      // On the home page "Check my deal" stays a button: it moves to the start form rather than to another page.
      return tab.page === "home" && page === "home"
        ? <button key={tab.page} type="button" {...props} aria-current="page" onClick={() => choose(position)}>{label}</button>
        : <a key={tab.page} href={tab.href} {...props} aria-current={current ? "page" : undefined}
          onClick={event => onClick(event, position)}>{label}</a>;
    })}
  </nav>;
}
