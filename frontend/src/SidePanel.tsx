import { ReactNode, useEffect, useRef } from "react";

export type SidePanelView = "account" | "case";

const OPEN_EVENT = "otdog:open-side-panel";

/** Opens a side panel from anywhere on the page, such as a link inside the case workflow. */
export function openSidePanel(view: SidePanelView) {
  window.dispatchEvent(new CustomEvent<SidePanelView>(OPEN_EVENT, { detail: view }));
}

export function useSidePanelRequests(onOpen: (view: SidePanelView) => void) {
  const handler = useRef(onOpen);
  handler.current = onOpen;
  useEffect(() => {
    function receive(event: Event) { handler.current((event as CustomEvent<SidePanelView>).detail); }
    window.addEventListener(OPEN_EVENT, receive);
    return () => window.removeEventListener(OPEN_EVENT, receive);
  }, []);
}

/**
 * A modal panel that slides over the page from the right. Its content stays mounted while it is closed,
 * so sign-in state and case access keep working in the background.
 */
export function SidePanel({ id, title, open, onClose, children }: {
  id: string; title: string; open: boolean; onClose: () => void; children: ReactNode;
}) {
  const panel = useRef<HTMLDivElement>(null);
  const opener = useRef<Element | null>(null);
  useEffect(() => {
    if (!open) return;
    opener.current = document.activeElement;
    panel.current?.focus();
    document.documentElement.classList.add("side-panel-open");
    return () => {
      document.documentElement.classList.remove("side-panel-open");
      if (opener.current instanceof HTMLElement && opener.current.isConnected) opener.current.focus();
    };
  }, [open]);

  function keepFocusInside(event: React.KeyboardEvent) {
    if (event.key === "Escape") { event.stopPropagation(); onClose(); return; }
    if (event.key !== "Tab" || !panel.current) return;
    const focusable = Array.from(panel.current.querySelectorAll<HTMLElement>(
      "a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex='-1'])",
    )).filter(element => element.offsetParent !== null || element === document.activeElement);
    if (focusable.length === 0) return;
    const first = focusable[0], last = focusable[focusable.length - 1];
    if (event.shiftKey && (document.activeElement === first || document.activeElement === panel.current)) {
      event.preventDefault(); last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault(); first.focus();
    }
  }

  return <>
    <div className="side-panel-backdrop" hidden={!open} onClick={onClose} aria-hidden="true" />
    <div id={id} className="side-panel" role="dialog" aria-modal="true" aria-labelledby={`${id}-title`}
      hidden={!open} tabIndex={-1} ref={panel} onKeyDown={keepFocusInside}>
      <div className="side-panel-header">
        <h2 id={`${id}-title`} className="side-panel-title">{title}</h2>
        <button type="button" className="btn-ghost side-panel-close" aria-label={`Close ${title}`} onClick={onClose}>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
            <path d="M6 6l12 12M18 6L6 18" />
          </svg>
        </button>
      </div>
      <div className="side-panel-body">{children}</div>
    </div>
  </>;
}
