import { useEffect, useState } from "react";
import * as format from "./i18n/format";

/**
 * Counts a money amount up to its value once, then shows the exact formatted text. Assistive
 * technology always reads the final amount; without motion support or with reduced motion
 * requested, the final amount is all that renders.
 */
export function AnimatedAmount({ minorUnits, currency, text }: { minorUnits: number; currency: string; text: string }) {
  const [shown, setShown] = useState<number | null>(null);
  useEffect(() => {
    const canAnimate = typeof window.matchMedia === "function" && typeof window.requestAnimationFrame === "function"
      && !window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (!canAnimate || minorUnits <= 0) return;
    let frame = 0;
    const begin = performance.now();
    const step = (now: number) => {
      const progress = Math.min(1, (now - begin) / 1200);
      const eased = 1 - Math.pow(1 - progress, 4);
      setShown(progress < 1 ? Math.round(minorUnits * eased) : null);
      if (progress < 1) frame = window.requestAnimationFrame(step);
    };
    setShown(0);
    frame = window.requestAnimationFrame(step);
    return () => { window.cancelAnimationFrame(frame); setShown(null); };
  }, [minorUnits]);
  if (shown === null) return <>{text}</>;
  const interim = format.money(shown, { currency, maximumFractionDigits: 0 });
  return <><span className="visually-hidden">{text}</span><span aria-hidden="true">{interim}</span></>;
}
