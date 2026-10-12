import { useEffect, useLayoutEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import { guidesLink } from "./ExplainerGuides";
import { howItWorksLink } from "./HowItWorks";
import "./SiteNav.css";

export type SitePage = "home" | "how-it-works" | "guides" | "other";

function focusStartForm() {
  const field = document.querySelector<HTMLInputElement>("#buying-case-intake input[name=buyerPostalCode]");
  field?.scrollIntoView?.({ block: "center" });
  field?.focus({ preventScroll: true });
}

/**
 * The site's four tabs, kept in the header on every page without an open case so the Buyer can always switch.
 * The pill slides between them on hover, focus, or a drag along the track, and rests on the current page.
 */
export function SiteNav({ page }: { page: SitePage }) {
  const track = useRef<HTMLElement>(null);
  const items = useRef<(HTMLElement | null)[]>([]);
  const drag = useRef<{ startX: number; moved: boolean; offset: number } | undefined>(undefined);
  const [active, setActive] = useState<number | undefined>({ home: 0, "how-it-works": 2, guides: 3, other: undefined }[page]);
  const [hovered, setHovered] = useState<number>();
  const [dragging, setDragging] = useState(false);
  const [thumb, setThumb] = useState<{ left: number; width: number }>();
  const shown = hovered ?? active;

  // Another page's "Check my deal" arrives at ./#start and lands in the start form.
  useEffect(() => {
    if (page === "home" && window.location.hash === "#start") focusStartForm();
  }, [page]);

  useLayoutEffect(() => {
    const place = () => {
      if (drag.current?.moved) return;
      const item = shown === undefined ? undefined : items.current[shown];
      if (!item) { setThumb(undefined); return; }
      setThumb({ left: item.offsetLeft, width: item.offsetWidth });
    };
    place();
    window.addEventListener("resize", place);
    return () => window.removeEventListener("resize", place);
  }, [shown]);

  function nearest(x: number) {
    let best = 0;
    items.current.forEach((item, index) => {
      const current = items.current[best]!;
      if (item && Math.abs(item.offsetLeft + item.offsetWidth / 2 - x) < Math.abs(current.offsetLeft + current.offsetWidth / 2 - x)) best = index;
    });
    return best;
  }

  function onPointerDown(event: ReactPointerEvent) {
    if (event.pointerType === "mouse" && event.button !== 0) return;
    const start = shown === undefined ? undefined : items.current[shown];
    drag.current = { startX: event.clientX, moved: false, offset: start ? start.offsetLeft : 0 };
  }

  function onPointerMove(event: ReactPointerEvent) {
    const state = drag.current;
    if (!state || !track.current) return;
    const distance = event.clientX - state.startX;
    if (!state.moved && Math.abs(distance) < 6) return;
    if (!state.moved) { state.moved = true; setDragging(true); track.current.setPointerCapture?.(event.pointerId); }
    const width = thumb?.width ?? items.current[0]?.offsetWidth ?? 0;
    const left = Math.min(Math.max(state.offset + distance, 0), track.current.clientWidth - width);
    setThumb({ left, width });
    setHovered(nearest(left + width / 2));
  }

  function onPointerUp(event: ReactPointerEvent) {
    const state = drag.current;
    drag.current = undefined;
    if (!state?.moved) return;
    setDragging(false);
    track.current?.releasePointerCapture?.(event.pointerId);
    const left = thumb?.left ?? 0;
    const target = nearest(left + (thumb?.width ?? 0) / 2);
    setHovered(undefined);
    setActive(target);
    items.current[target]?.click();
  }

  const itemProps = (index: number) => ({
    ref: (element: HTMLElement | null) => { items.current[index] = element; },
    className: index === shown ? "site-switch-item is-shown" : "site-switch-item",
    onPointerEnter: (event: ReactPointerEvent) => { if (event.pointerType === "mouse" && !drag.current) setHovered(index); },
    onFocus: () => setHovered(index),
    onBlur: () => setHovered(undefined),
    draggable: false,
  });

  return <nav className="site-switch" aria-label="Main" ref={track}
    data-dragging={dragging ? "" : undefined}
    onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerUp}
    onPointerCancel={() => { drag.current = undefined; setDragging(false); setHovered(undefined); }}
    onPointerLeave={event => { if (event.pointerType === "mouse" && !drag.current) setHovered(undefined); }}>
    {thumb && <span className="site-switch-thumb" aria-hidden="true"
      style={{ transform: `translateX(${thumb.left}px)`, width: thumb.width }} />}
    <button type="button" {...itemProps(0)} onClick={() => {
      setActive(0);
      if (page === "home") focusStartForm(); else window.location.assign("./#start");
    }}>Check my deal</button>
    <a href={page === "home" ? "#sample-report" : "./#sample-report"} aria-label="See an example report" {...itemProps(1)} onClick={() => {
      setActive(1);
      if (page === "home") window.dispatchEvent(new Event("open-sample-report"));
    }}>Example report</a>
    <a href={howItWorksLink} aria-current={page === "how-it-works" ? "page" : undefined} {...itemProps(2)} onClick={() => setActive(2)}>How it works</a>
    <a href={guidesLink} aria-current={page === "guides" ? "page" : undefined} {...itemProps(3)} onClick={() => setActive(3)}>Guides</a>
  </nav>;
}
