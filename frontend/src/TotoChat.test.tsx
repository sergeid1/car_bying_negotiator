import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";
import App from "./App";
import { TotoChat, type TotoAnswer } from "./TotoChat";

afterEach(() => { cleanup(); vi.restoreAllMocks(); window.history.replaceState(null, "", "/"); });

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const ANSWER: TotoAnswer = {
  kind: "ANSWER",
  paragraphs: [
    { text: "A doc fee is what the dealer charges to prepare the sale's paperwork.",
      citations: [{ articleId: "dealer-fees", title: "Which dealer fees to ask about", link: "?guide=dealer-fees" }] },
    { text: "You can ask the dealer to show it as its own line.",
      citations: [{ articleId: "doc-fee", title: "Doc fees", link: null },
        { articleId: "unknown", title: "A guide this page does not have", link: "https://example.com/elsewhere" }] },
  ],
  message: null,
  suggestions: [],
};
const DECLINE: TotoAnswer = { kind: "DECLINE", paragraphs: [], message: "I can't help with tax questions. A tax professional can.", suggestions: [] };
const FALLBACK: TotoAnswer = { kind: "FALLBACK", paragraphs: [], message: "I can't answer that one well. These guides may help.",
  suggestions: [{ articleId: "otd", title: "How to read an out-the-door quote", link: "?guide=out-the-door-quote" }] };

/** A mock of the agreed Toto endpoints; `replies` answers each POST in turn. */
function toto(replies: (Response | Error)[], available: unknown = { available: true }) {
  const posts: { question: string; turns: { role: string; text: string }[] }[] = [];
  const request = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input) === "/api/v1/toto") return json(available);
    if (String(input) === "/api/v1/toto/answers") {
      posts.push(JSON.parse(String(init?.body)));
      const reply = replies.shift();
      if (reply instanceof Error) throw reply;
      return reply ?? json(ANSWER);
    }
    return json({}, 404);
  });
  return { request: request as unknown as typeof fetch, posts, mock: request };
}

/** The chat history, apart from the live region that repeats the latest answer. */
const history = (panel: HTMLElement) => within(within(panel).getByRole("list", { name: "Your questions and Toto's answers" }));

async function openChat() {
  await userEvent.click(await screen.findByRole("button", { name: "Ask Toto" }));
  return screen.getByRole("dialog", { name: "Ask Toto" });
}

test.each([
  ["reports it unavailable", { available: false }],
  ["answers with an error", undefined],
])("nothing shows when Toto %s", async (_, available) => {
  const api = toto([], available);
  if (!available) api.mock.mockImplementation(async () => json({ code: "NOT_FOUND" }, 404));
  const { container } = render(<TotoChat request={api.request} />);
  await waitFor(() => expect(api.mock).toHaveBeenCalledWith("/api/v1/toto"));
  expect(container).toBeEmptyDOMElement();
  expect(screen.queryByRole("button", { name: "Ask Toto" })).not.toBeInTheDocument();
});

test("nothing shows when the availability request fails outright", async () => {
  const request = vi.fn().mockRejectedValue(new TypeError("offline"));
  const { container } = render(<TotoChat request={request} />);
  await waitFor(() => expect(request).toHaveBeenCalled());
  expect(container).toBeEmptyDOMElement();
});

test("the button opens a panel with the notice and starter questions, moving focus in and back out", async () => {
  const api = toto([]);
  render(<TotoChat request={api.request} />);
  const opener = await screen.findByRole("button", { name: "Ask Toto" });
  const panel = await openChat();
  expect(panel).toHaveAccessibleDescription(/Toto is an AI guide that answers from otdog's guides/);
  expect(panel).toHaveTextContent("can't see your case or documents");
  expect(panel).toHaveTextContent("Please don't type personal details");
  expect(panel).toHaveTextContent("are not saved by otdog");
  expect(panel).toHaveTextContent("This chat clears when you leave or reload the page");
  expect(within(panel).getByRole("button", { name: "What is a doc fee?" })).toBeVisible();
  expect(within(panel).getByRole("button", { name: "What does the four-square do?" })).toBeVisible();
  expect(within(panel).getByRole("textbox", { name: "Your question" })).toHaveFocus();

  await userEvent.keyboard("{Escape}");
  expect(screen.queryByRole("dialog", { name: "Ask Toto" })).not.toBeInTheDocument();
  expect(opener).toHaveFocus();

  await userEvent.keyboard("{Enter}");
  await userEvent.click(within(screen.getByRole("dialog", { name: "Ask Toto" })).getByRole("button", { name: "Close Ask Toto" }));
  expect(opener).toHaveFocus();
});

test("asking shows the answer with cited guides and announces it politely", async () => {
  let release!: (response: Response) => void;
  const api = toto([]);
  api.mock.mockImplementationOnce(async () => json({ available: true }))
    .mockImplementationOnce(async (_, init) => {
      api.posts.push(JSON.parse(String(init?.body)));
      return new Promise<Response>(resolve => { release = resolve; });
    });
  render(<TotoChat request={api.request} />);
  const panel = await openChat();
  await userEvent.click(within(panel).getByRole("button", { name: "What is a doc fee?" }));

  expect(within(panel).getByText("Toto is thinking…")).toBeVisible();
  expect(within(panel).getByRole("button", { name: "Asking…" })).toBeDisabled();
  expect(api.posts).toEqual([{ question: "What is a doc fee?", turns: [] }]);

  release(json(ANSWER));
  const log = await within(panel).findByRole("list", { name: "Your questions and Toto's answers" });
  expect(log).toHaveTextContent("What is a doc fee?");
  expect(log).toHaveTextContent("A doc fee is what the dealer charges");
  expect(within(log).getByRole("link", { name: "Which dealer fees to ask about" })).toHaveAttribute("href", "?guide=dealer-fees");
  expect(within(log).getByText("Doc fees").tagName).toBe("CITE");
  // A link outside the guides this page knows is shown as its title only.
  expect(within(log).queryByRole("link", { name: "A guide this page does not have" })).not.toBeInTheDocument();
  expect(within(log).getByText("A guide this page does not have")).toBeVisible();
  const status = within(panel).getByRole("status");
  expect(status).toHaveAttribute("aria-live", "polite");
  expect(status).toHaveTextContent("Toto answered: A doc fee is what the dealer charges");
  expect(within(panel).queryByRole("button", { name: "What is a doc fee?" })).not.toBeInTheDocument();
});

test("a cited guide opens without reloading, so the chat stays", async () => {
  const api = toto([json(ANSWER)]);
  const moved = vi.fn();
  window.addEventListener("popstate", moved);
  render(<TotoChat request={api.request} />);
  const panel = await openChat();
  await userEvent.type(within(panel).getByRole("textbox", { name: "Your question" }), "What is a doc fee?{Enter}");
  await userEvent.click(await within(panel).findByRole("link", { name: "Which dealer fees to ask about" }));
  expect(window.location.search).toBe("?guide=dealer-fees");
  expect(moved).toHaveBeenCalled();
  expect(screen.getByText("What is a doc fee?")).toBeInTheDocument();
  window.removeEventListener("popstate", moved);
});

test("decline and fallback answers show the server's fixed wording and suggested guides", async () => {
  const api = toto([json(DECLINE), json(FALLBACK)]);
  render(<TotoChat request={api.request} />);
  const panel = await openChat();
  const field = within(panel).getByRole("textbox", { name: "Your question" });
  await userEvent.type(field, "Can I deduct the sales tax?{Enter}");
  await waitFor(() => expect(history(panel).getByText(DECLINE.message!)).toBeVisible());
  await userEvent.type(field, "What is a spot delivery?{Enter}");
  await waitFor(() => expect(history(panel).getByText(FALLBACK.message!)).toBeVisible());
  expect(within(panel).getByText(/Guides that may help/)).toBeVisible();
  expect(within(panel).getByRole("link", { name: "How to read an out-the-door quote" })).toHaveAttribute("href", "?guide=out-the-door-quote");
  expect(within(panel).getByRole("status")).toHaveTextContent(`Toto answered: ${FALLBACK.message}`);
});

test("follow-up questions carry only the last six turns, oldest first", async () => {
  const api = toto([]);
  render(<TotoChat request={api.request} />);
  const panel = await openChat();
  const field = within(panel).getByRole("textbox", { name: "Your question" });
  for (const question of ["One?", "Two?", "Three?", "Four?"]) {
    await userEvent.type(field, `${question}{Enter}`);
    await waitFor(() => expect(history(panel).getAllByText(/A doc fee is what/)).toHaveLength(api.posts.length));
  }
  const last = api.posts[3];
  expect(last.question).toBe("Four?");
  expect(last.turns).toHaveLength(6);
  expect(last.turns.map(turn => turn.role)).toEqual(["BUYER", "TOTO", "BUYER", "TOTO", "BUYER", "TOTO"]);
  expect(last.turns[0].text).toBe("One?");
  expect(last.turns[1].text).toBe(`${ANSWER.paragraphs[0].text}\n\n${ANSWER.paragraphs[1].text}`);
});

test("an over-length question is explained and not sent; an empty one asks for text", async () => {
  const api = toto([]);
  render(<TotoChat request={api.request} />);
  const panel = await openChat();
  const field = within(panel).getByRole("textbox", { name: "Your question" });
  await userEvent.click(within(panel).getByRole("button", { name: "Ask" }));
  expect(within(panel).getByRole("alert")).toHaveTextContent("Type a question first.");

  await userEvent.click(field);
  await userEvent.paste("a".repeat(1001));
  expect(within(panel).getByText(/1,001 of 1,000 characters/)).toBeVisible();
  expect(field).toHaveAttribute("aria-invalid", "true");
  await userEvent.click(within(panel).getByRole("button", { name: "Ask" }));
  expect(within(panel).getByRole("alert")).toHaveTextContent(
    "Questions can be up to 1,000 characters, and this one is 1,001. Please shorten it and ask again.");
  expect(field).toHaveValue("a".repeat(1001));
  expect(api.posts).toEqual([]);
});

test("a cap shows the server's resting wording and the Buyer can still ask later", async () => {
  const resting = "Toto is resting until tomorrow. The guides are still here.";
  const api = toto([json({ code: "TOTO_RESTING", detail: resting }, 429), json(ANSWER)]);
  render(<TotoChat request={api.request} />);
  const panel = await openChat();
  await userEvent.click(within(panel).getByRole("button", { name: "What is a doc fee?" }));
  await waitFor(() => expect(history(panel).getByText(resting)).toBeVisible());
  expect(within(panel).getByRole("status")).toHaveTextContent(resting);
  expect(within(panel).getByRole("button", { name: "Ask" })).toBeEnabled();
  // The capped exchange has no answer, so it is not sent back as context.
  await userEvent.type(within(panel).getByRole("textbox", { name: "Your question" }), "And a trade-in?{Enter}");
  await waitFor(() => expect(history(panel).getByText(/A doc fee is what/)).toBeVisible());
  expect(api.posts[1].turns).toEqual([]);
});

test("a resting refusal without wording falls back to the client's own", async () => {
  const api = toto([json({ code: "TOTO_RESTING" }, 429)]);
  render(<TotoChat request={api.request} />);
  const panel = await openChat();
  await userEvent.click(within(panel).getByRole("button", { name: "What is a doc fee?" }));
  await waitFor(() => expect(history(panel).getByText("Toto is resting for now. The guides are still open to read.")).toBeVisible());
});

test("a server or network error keeps the chat and puts the question back to try again", async () => {
  const api = toto([json(ANSWER), json({}, 500), new TypeError("offline")]);
  render(<TotoChat request={api.request} />);
  const panel = await openChat();
  const field = within(panel).getByRole("textbox", { name: "Your question" });
  await userEvent.type(field, "First?{Enter}");
  await waitFor(() => expect(history(panel).getByText(/A doc fee is what/)).toBeVisible());
  await userEvent.type(field, "Second?{Enter}");
  await waitFor(() => expect(history(panel).getByText("Toto couldn't answer just now. Your chat is still here, so you can try again.")).toBeVisible());
  expect(field).toHaveValue("Second?");
  expect(history(panel).getByText(/A doc fee is what/)).toBeVisible();
  await userEvent.click(within(panel).getByRole("button", { name: "Ask" }));
  await waitFor(() => expect(history(panel).getAllByText(/Toto couldn't answer just now/)).toHaveLength(2));
  expect(field).toHaveValue("Second?");
});

test("Toto switched off since the page loaded stops further questions", async () => {
  const api = toto([json({ code: "TOTO_UNAVAILABLE", detail: "Disabled." }, 503)]);
  render(<TotoChat request={api.request} />);
  const panel = await openChat();
  await userEvent.click(within(panel).getByRole("button", { name: "What is a doc fee?" }));
  await waitFor(() => expect(history(panel).getByText("Toto isn't available right now. The guides are still open to read.")).toBeVisible());
  expect(within(panel).getByRole("textbox", { name: "Your question" })).toBeDisabled();
  expect(within(panel).getByRole("button", { name: "Ask" })).toBeDisabled();
});

test("a question the server refuses as invalid shows its plain reason", async () => {
  const api = toto([json({ code: "TOTO_QUESTION_INVALID", detail: "Questions can be up to 1,000 characters." }, 400)]);
  render(<TotoChat request={api.request} />);
  const panel = await openChat();
  await userEvent.type(within(panel).getByRole("textbox", { name: "Your question" }), "Hello?{Enter}");
  await waitFor(() => expect(history(panel).getByText("Questions can be up to 1,000 characters.")).toBeVisible());
});

test("the whole panel works by keyboard", async () => {
  const api = toto([]);
  render(<TotoChat request={api.request} />);
  const opener = await screen.findByRole("button", { name: "Ask Toto" });
  opener.focus();
  await userEvent.keyboard("{Enter}");
  const panel = screen.getByRole("dialog", { name: "Ask Toto" });
  await userEvent.keyboard("What does the four-square do?");
  await userEvent.keyboard("{Enter}");
  await waitFor(() => expect(history(panel).getByText(/A doc fee is what/)).toBeVisible());
  expect(api.posts[0].question).toBe("What does the four-square do?");
  await userEvent.keyboard("{Shift>}{Tab}{/Shift}");
  expect(document.activeElement?.closest("#toto-panel")).toBe(panel);
});

test("on a narrow screen the panel is a modal dialog that keeps focus inside", async () => {
  vi.stubGlobal("matchMedia", (query: string) => ({ matches: true, media: query, addEventListener() {}, removeEventListener() {} }));
  try {
    const api = toto([]);
    render(<TotoChat request={api.request} />);
    const panel = await openChat();
    expect(panel).toHaveAttribute("aria-modal", "true");
    expect(document.documentElement).toHaveClass("toto-dialog-open");
    const close = within(panel).getByRole("button", { name: "Close Ask Toto" });
    close.focus();
    await userEvent.keyboard("{Shift>}{Tab}{/Shift}");
    expect(within(panel).getByRole("button", { name: "Ask" })).toHaveFocus();
    await userEvent.keyboard("{Tab}");
    expect(close).toHaveFocus();
    await userEvent.keyboard("{Escape}");
    expect(document.documentElement).not.toHaveClass("toto-dialog-open");
  } finally {
    vi.unstubAllGlobals();
  }
});

test("on a wide screen the panel sits beside the page without trapping it", async () => {
  const api = toto([]);
  render(<TotoChat request={api.request} />);
  const panel = await openChat();
  expect(panel).not.toHaveAttribute("aria-modal");
  expect(document.documentElement).not.toHaveClass("toto-dialog-open");
});

test("the app shows Ask Toto on every page only when Toto is available", async () => {
  for (const [address, available] of [["/", true], ["/?page=guides", true], ["/?guide=dealer-fees", true], ["/", false]] as const) {
    window.history.replaceState(null, "", address);
    vi.spyOn(globalThis, "fetch").mockImplementation(async input => {
      const path = String(input);
      if (path === "/api/v1/toto") return json({ available });
      if (path === "/api/v1/account") return json({}, 401);
      if (path.endsWith("/intake-options")) return json({ supported: ["US-NC"], consentPolicies: {} });
      return json({}, 404);
    });
    render(<App />);
    if (available) expect(await screen.findByRole("button", { name: "Ask Toto" })).toBeVisible();
    else {
      await waitFor(() => expect(globalThis.fetch).toHaveBeenCalledWith("/api/v1/toto"));
      expect(screen.queryByRole("button", { name: "Ask Toto" })).not.toBeInTheDocument();
    }
    cleanup();
    vi.restoreAllMocks();
  }
});
