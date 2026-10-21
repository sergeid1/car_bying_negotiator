import { FormEvent, useEffect, useId, useRef, useState } from "react";
import { GUIDE_LIST, guideLink } from "./ExplainerGuides";
import "./TotoChat.css";
import * as format from "./i18n/format";

/**
 * Toto, an AI guide that answers process questions from otdog's reviewed guides (tickets 118–121).
 * The chat lives only in this component's memory: nothing is written to storage, and leaving or
 * reloading the page clears it. Each question carries the last few turns so the server stores nothing.
 * When `GET /api/v1/toto` fails or says Toto is unavailable, nothing renders at all.
 */
export const QUESTION_LIMIT = 1000;
export const TURN_LIMIT = 6;
export const TURN_TEXT_LIMIT = 1500;

export type TotoCitation = { articleId: string; title: string; link: string | null };
export type TotoAnswer = {
  kind: "ANSWER" | "DECLINE" | "FALLBACK";
  paragraphs: { text: string; citations: TotoCitation[] }[];
  message: string | null;
  suggestions: TotoCitation[];
};
type Turn = { role: "BUYER" | "TOTO"; text: string };
type Exchange = { id: number; question: string; answer?: TotoAnswer; failure?: string };

/** Static, code-owned starter questions shown while the chat is empty. */
export const STARTER_QUESTIONS = [
  "What is a doc fee?",
  "What does the four-square do?",
  "What should I bring to the dealership?",
  "How does an outside finance offer work?",
];

const RESTING = "Toto is resting for now. The guides are still open to read.";
const FAILED = "Toto couldn't answer just now. Your chat is still here, so you can try again.";
const UNAVAILABLE = "Toto isn't available right now. The guides are still open to read.";

/** Only links into the guides this client knows are followed; anything else shows as the title alone. */
const KNOWN_LINKS = new Set(GUIDE_LIST.map(guide => guideLink(guide.slug)));

/** Opens a guide without reloading, as the header's tabs do, so the chat stays in memory. */
function openGuide(href: string) {
  window.history.pushState(null, "", href);
  window.dispatchEvent(new PopStateEvent("popstate"));
  window.scrollTo?.({ top: 0 });
}

function answerText(answer: TotoAnswer) {
  return answer.kind === "ANSWER" ? answer.paragraphs.map(paragraph => paragraph.text).join("\n\n") : answer.message ?? "";
}

/** The last six turns, oldest first. Toto's own earlier text is shortened to the server's per-turn limit. */
export function recentTurns(exchanges: Exchange[]): Turn[] {
  const turns = exchanges.filter(exchange => exchange.answer).flatMap(exchange => [
    { role: "BUYER" as const, text: exchange.question },
    { role: "TOTO" as const, text: answerText(exchange.answer!).slice(0, TURN_TEXT_LIMIT) },
  ]);
  return turns.slice(-TURN_LIMIT);
}

function isNarrow() {
  return typeof window.matchMedia === "function" && window.matchMedia("(max-width: 720px)").matches;
}

export function TotoChat({ request = fetch }: { request?: typeof fetch }) {
  const [available, setAvailable] = useState(false);
  const [open, setOpen] = useState(false);
  const [modal, setModal] = useState(isNarrow);
  const [exchanges, setExchanges] = useState<Exchange[]>([]);
  const [question, setQuestion] = useState("");
  const [problem, setProblem] = useState<string>();
  const [pending, setPending] = useState(false);
  const [stopped, setStopped] = useState(false);
  const [announcement, setAnnouncement] = useState("");
  const panel = useRef<HTMLDivElement>(null);
  const field = useRef<HTMLTextAreaElement>(null);
  const opener = useRef<HTMLButtonElement>(null);
  const log = useRef<HTMLOListElement>(null);
  const nextId = useRef(0);
  const ids = useId();

  useEffect(() => {
    let current = true;
    request("/api/v1/toto")
      .then(response => response.ok ? response.json() : undefined)
      .then(body => { if (current) setAvailable(body?.available === true); })
      .catch(() => {});
    return () => { current = false; };
  }, [request]);

  useEffect(() => {
    if (typeof window.matchMedia !== "function") return;
    const query = window.matchMedia("(max-width: 720px)");
    const changed = () => setModal(query.matches);
    query.addEventListener?.("change", changed);
    return () => query.removeEventListener?.("change", changed);
  }, []);

  // The opener is hidden while the panel is open, so focus returns to it once it is shown again.
  const returnFocus = useRef(false);
  useEffect(() => {
    if (!open) {
      if (returnFocus.current) opener.current?.focus();
      returnFocus.current = false;
      return;
    }
    (field.current && !field.current.disabled ? field.current : panel.current)?.focus();
    if (!modal) return;
    document.documentElement.classList.add("toto-dialog-open");
    return () => document.documentElement.classList.remove("toto-dialog-open");
  }, [open, modal]);

  useEffect(() => {
    log.current?.lastElementChild?.scrollIntoView?.({ block: "nearest" });
  }, [exchanges]);

  if (!available) return null;

  function close() {
    returnFocus.current = true;
    setOpen(false);
  }

  /** A guide opens behind the chat; on a phone the chat steps aside so the guide can be read. */
  function guideOpened() {
    if (modal) close();
  }

  async function ask(text: string) {
    if (pending || stopped) return;
    const trimmed = text.trim();
    if (!trimmed) { setProblem("Type a question first."); return; }
    if (trimmed.length > QUESTION_LIMIT) {
      setProblem(`Questions can be up to ${format.number(QUESTION_LIMIT)} characters, and this one is `
        + `${format.number(trimmed.length)}. Please shorten it and ask again.`);
      return;
    }
    setProblem(undefined);
    const id = nextId.current++;
    const turns = recentTurns(exchanges);
    setExchanges(list => [...list, { id, question: trimmed }]);
    setQuestion("");
    setPending(true);
    setAnnouncement("Toto is working on an answer.");
    const settle = (change: Partial<Exchange>, spoken: string) => {
      setExchanges(list => list.map(exchange => exchange.id === id ? { ...exchange, ...change } : exchange));
      setAnnouncement(spoken);
    };
    try {
      const response = await request("/api/v1/toto/answers", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ question: trimmed, turns }),
      });
      if (response.ok) {
        const answer = await response.json() as TotoAnswer;
        settle({ answer }, `Toto answered: ${answerText(answer)}`);
        return;
      }
      const refusal = await response.json().catch(() => ({})) as { code?: string; detail?: string };
      if (refusal.code === "TOTO_RESTING") {
        settle({ failure: refusal.detail || RESTING }, refusal.detail || RESTING);
      } else if (refusal.code === "TOTO_UNAVAILABLE") {
        setStopped(true);
        settle({ failure: UNAVAILABLE }, UNAVAILABLE);
      } else if (refusal.code === "TOTO_QUESTION_INVALID" && refusal.detail) {
        settle({ failure: refusal.detail }, refusal.detail);
        setQuestion(trimmed);
      } else {
        settle({ failure: FAILED }, FAILED);
        setQuestion(trimmed);
      }
    } catch {
      settle({ failure: FAILED }, FAILED);
      setQuestion(trimmed);
    } finally {
      setPending(false);
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    void ask(question);
  }

  function onKeyDown(event: React.KeyboardEvent) {
    if (event.key === "Escape") { event.stopPropagation(); close(); return; }
    if (!modal || event.key !== "Tab" || !panel.current) return;
    const focusable = Array.from(panel.current.querySelectorAll<HTMLElement>(
      "a[href], button:not([disabled]), textarea:not([disabled])",
    ));
    if (focusable.length === 0) return;
    const first = focusable[0], last = focusable[focusable.length - 1];
    if (event.shiftKey && (document.activeElement === first || document.activeElement === panel.current)) {
      event.preventDefault(); last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault(); first.focus();
    }
  }

  const titleId = `${ids}-title`, noticeId = `${ids}-notice`, hintId = `${ids}-hint`, problemId = `${ids}-problem`;
  const over = question.trim().length > QUESTION_LIMIT;

  return <>
    <button type="button" className="toto-open" ref={opener} aria-haspopup="dialog" aria-expanded={open}
      aria-controls="toto-panel" hidden={open} onClick={() => setOpen(true)}>
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
        strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z" />
      </svg>
      Ask Toto
    </button>
    {modal && <div className="toto-backdrop" hidden={!open} onClick={close} aria-hidden="true" />}
    <div id="toto-panel" className="toto-panel" role="dialog" aria-modal={modal ? true : undefined}
      aria-labelledby={titleId} aria-describedby={noticeId} hidden={!open} tabIndex={-1} ref={panel} onKeyDown={onKeyDown}>
      <div className="toto-header">
        <h2 id={titleId} className="toto-title">Ask Toto</h2>
        <button type="button" className="btn-ghost toto-close" aria-label="Close Ask Toto" onClick={close}>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
            <path d="M6 6l12 12M18 6L6 18" />
          </svg>
        </button>
      </div>
      <p className="toto-notice" id={noticeId}>
        Toto is an AI guide that answers from otdog's guides. It can't see your case or documents, and it can't
        answer legal or tax questions. Please don't type personal details. Questions are sent to an AI provider
        and are not saved by otdog. This chat clears when you leave or reload the page.
      </p>
      <div className="toto-body">
        {exchanges.length === 0 && <div className="toto-starters">
          <p id={`${ids}-starters`}>Try one of these:</p>
          <ul aria-labelledby={`${ids}-starters`}>
            {STARTER_QUESTIONS.map(starter => <li key={starter}>
              <button type="button" className="btn-secondary" disabled={pending || stopped} onClick={() => void ask(starter)}>{starter}</button>
            </li>)}
          </ul>
        </div>}
        {exchanges.length > 0 && <ol className="toto-log" ref={log} aria-label="Your questions and Toto's answers">
          {exchanges.map(exchange => <li key={exchange.id}>
            <p className="toto-question"><span className="visually-hidden">You asked: </span>{exchange.question}</p>
            <div className="toto-reply">
              <span className="visually-hidden">Toto: </span>
              {exchange.answer ? <Reply answer={exchange.answer} onGuide={guideOpened} />
                : exchange.failure ? <p className="toto-failure">{exchange.failure}</p>
                  : <p className="toto-thinking"><span className="spinner" aria-hidden="true" />Toto is thinking…</p>}
            </div>
          </li>)}
        </ol>}
      </div>
      <form className="toto-form" onSubmit={submit} aria-busy={pending}>
        <label htmlFor={`${ids}-question`}>Your question</label>
        <textarea id={`${ids}-question`} ref={field} rows={2} value={question} disabled={stopped}
          aria-invalid={problem || over ? true : undefined} aria-describedby={problem ? `${problemId} ${hintId}` : hintId}
          onChange={event => { setQuestion(event.target.value); setProblem(undefined); }}
          onKeyDown={event => {
            if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); void ask(question); }
          }} />
        <p className="toto-hint" id={hintId} data-over={over || undefined}>
          {format.number(question.trim().length)} of {format.number(QUESTION_LIMIT)} characters.
          Enter sends; Shift+Enter adds a line.
        </p>
        {problem && <p className="error" id={problemId} role="alert">{problem}</p>}
        <button type="submit" disabled={pending || stopped}>{pending ? "Asking…" : "Ask"}</button>
      </form>
      <p className="visually-hidden" role="status" aria-live="polite">{announcement}</p>
    </div>
  </>;
}

function Reply({ answer, onGuide }: { answer: TotoAnswer; onGuide: () => void }) {
  if (answer.kind === "ANSWER") {
    return <>{answer.paragraphs.map((paragraph, index) => <div className="toto-paragraph" key={index}>
      <p>{paragraph.text}</p>
      {paragraph.citations.length > 0 && <Sources label="From the guide" citations={paragraph.citations} onGuide={onGuide} />}
    </div>)}</>;
  }
  return <>
    <p>{answer.message}</p>
    {answer.suggestions.length > 0 && <Sources label="Guides that may help" citations={answer.suggestions} onGuide={onGuide} />}
  </>;
}

function Sources({ label, citations, onGuide }: { label: string; citations: TotoCitation[]; onGuide: () => void }) {
  return <p className="toto-sources">
    <span>{label}: </span>
    {citations.map((citation, index) => <span key={`${citation.articleId}-${index}`}>
      {index > 0 && ", "}
      {citation.link && KNOWN_LINKS.has(citation.link)
        ? <a href={citation.link} onClick={event => {
          if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
          event.preventDefault(); openGuide(citation.link!); onGuide();
        }}>{citation.title}</a>
        : <cite>{citation.title}</cite>}
    </span>)}
  </p>;
}
