import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { readFileSync, readdirSync } from "node:fs";
import { afterEach, expect, test, vi } from "vitest";
import { DealerDocumentUpload, refusalGuidance } from "./DealerDocumentUpload";

const LIMITS = {
  acceptedMediaTypes: ["application/pdf", "image/heic", "image/jpeg", "image/png"],
  maxFileBytes: 20971520,
  maxPages: 25,
  maxPixels: 40000000,
  minWidth: 2,
  minHeight: 2,
};

const DOCUMENT_SOURCES = "../backend/src/main/java/com/dealanalyzer/document";

type Reply = { status?: number; body?: unknown };
type Call = { url: string; method: string; headers: Record<string, string>; form?: FormData };

/**
 * Routes by URL and method so each test states only the replies it cares about. The last reply in
 * a queue repeats, because polling continues until a stage is terminal.
 */
function stubApi(replies: {
  limits?: Reply;
  uploads?: Reply[];
  operations?: Reply[];
  retries?: Reply[];
  documents?: Reply[];
}) {
  const queues = {
    uploads: [...(replies.uploads ?? [])],
    operations: [...(replies.operations ?? [])],
    retries: [...(replies.retries ?? [])],
    documents: [...(replies.documents ?? [])],
  };
  const next = (queue: Reply[]) => (queue.length > 1 ? queue.shift()! : queue[0]);
  const calls: Call[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const url = String(input);
    const method = init?.method ?? "GET";
    calls.push({
      url,
      method,
      headers: (init?.headers ?? {}) as Record<string, string>,
      form: init?.body instanceof FormData ? init.body : undefined,
    });
    let reply: Reply | undefined;
    if (url.endsWith("/document-limits")) reply = replies.limits ?? { body: LIMITS };
    else if (url.endsWith("/retries")) reply = next(queues.retries);
    else if (url.includes("/operations/")) reply = next(queues.operations);
    else if (url.endsWith("/documents") && method === "POST") reply = next(queues.uploads);
    else if (url.includes("/documents/")) reply = next(queues.documents);
    if (!reply) throw new Error(`Unexpected ${method} to ${url}`);
    return new Response(reply.body === undefined ? null : JSON.stringify(reply.body), {
      status: reply.status ?? 200,
      headers: { "Content-Type": "application/json" },
    });
  });
  return calls;
}

function page(pageNumber: number, flags: Partial<Record<string, boolean>> = {}) {
  return {
    pageNumber,
    likelyMissing: false,
    likelyDuplicate: false,
    likelyRotated: false,
    likelyCropped: false,
    ...flags,
  };
}

function started(documentId: string, operationId: string, pages = [page(1)]) {
  return {
    document: { id: documentId, originalFilename: "order.pdf", pages },
    operation: { id: operationId, documentId, stage: "QUEUED", retryCount: 0 },
  };
}

function operation(stage: string, extra: Record<string, unknown> = {}) {
  return {
    id: "op-1",
    documentId: "doc-1",
    stage,
    retryCount: 0,
    updatedAt: "2026-09-29T12:00:00Z",
    ...extra,
  };
}

function pdf(name = "order.pdf", size = 2048) {
  return new File(["%PDF-1.4".padEnd(size, " ")], name, { type: "application/pdf" });
}

function photo(name = "page-one.jpg", size = 4096) {
  return new File(["x".repeat(size)], name, { type: "image/jpeg" });
}

function upload(caseId = "case-1", token = "case-secret") {
  return render(<DealerDocumentUpload caseId={caseId} token={token} pollDelayMs={0} />);
}

async function waitForLimits() {
  await waitFor(() => expect(screen.getByText(/up to 20 MB per file/i)).toBeInTheDocument());
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

test("paperwork posts as multipart content and is followed to the terms-read state", async () => {
  const calls = stubApi({
    uploads: [{ status: 202, body: started("doc-1", "op-1") }],
    operations: [
      { body: operation("NORMALIZING") },
      { body: operation("EXTRACTING") },
      { body: operation("EXTRACTION_COMPLETE") },
    ],
    documents: [{ body: { pages: [page(1)] } }],
  });
  upload();
  await waitForLimits();

  await userEvent.upload(screen.getByLabelText(/dealer paperwork/i), pdf());

  await waitFor(() => expect(screen.getByText(/terms read/i)).toBeInTheDocument());
  expect(screen.getByText("order.pdf")).toBeInTheDocument();

  const posted = calls.find(call => call.method === "POST" && call.url.endsWith("/documents"))!;
  expect(posted.url).toBe("/api/v1/cases/case-1/documents");
  expect(posted.form?.get("file")).toBeInstanceOf(File);
  expect(posted.headers["Case-Access-Token"]).toBe("case-secret");
  expect(posted.headers["Idempotency-Key"]).toMatch(/\S/);
  // The browser has to set the multipart boundary itself.
  expect(posted.headers["Content-Type"]).toBeUndefined();
  expect(calls.some(call => call.url.includes("/operations/op-1"))).toBe(true);
});

test("the file control accepts every supported type, several files, and the phone camera", async () => {
  stubApi({ uploads: [{ status: 202, body: started("doc-1", "op-1") }], operations: [{ body: operation("EXTRACTION_COMPLETE") }], documents: [{ body: { pages: [page(1)] } }] });
  upload();
  await waitForLimits();

  const chooser = screen.getByLabelText(/dealer paperwork/i);
  expect(chooser).toHaveAttribute("multiple");
  for (const accepted of ["application/pdf", "image/jpeg", "image/png", "image/heic", ".pdf", ".heic"]) {
    expect(chooser.getAttribute("accept")).toContain(accepted);
  }

  const camera = screen.getByLabelText(/photograph a page/i);
  expect(camera).toHaveAttribute("capture", "environment");
  expect(camera.getAttribute("accept")).toContain("image/jpeg");
  expect(camera.getAttribute("accept")).not.toContain("application/pdf");
});

test("several chosen files each upload under their own request key", async () => {
  const calls = stubApi({
    uploads: [
      { status: 202, body: started("doc-1", "op-1") },
      { status: 202, body: started("doc-2", "op-2") },
    ],
    operations: [{ body: operation("EXTRACTION_COMPLETE") }],
    documents: [{ body: { pages: [page(1)] } }],
  });
  upload();
  await waitForLimits();

  await userEvent.upload(screen.getByLabelText(/dealer paperwork/i), [
    pdf("page-one.pdf"),
    pdf("page-two.pdf"),
  ]);

  await waitFor(() => expect(screen.getByText("page-two.pdf")).toBeInTheDocument());
  const posts = calls.filter(call => call.method === "POST" && call.url.endsWith("/documents"));
  expect(posts).toHaveLength(2);
  expect(posts[0].headers["Idempotency-Key"]).not.toBe(posts[1].headers["Idempotency-Key"]);
  expect(screen.getByText("page-one.pdf")).toBeInTheDocument();
});

test("adding the same file again replays the first upload instead of storing a second copy", async () => {
  const calls = stubApi({
    uploads: [{ status: 202, body: started("doc-1", "op-1") }],
    operations: [{ body: operation("EXTRACTION_COMPLETE") }],
    documents: [{ body: { pages: [page(1)] } }],
  });
  const file = pdf("order.pdf");
  upload();
  await waitForLimits();

  await userEvent.upload(screen.getByLabelText(/dealer paperwork/i), file);
  await waitFor(() => expect(screen.getByText(/terms read/i)).toBeInTheDocument());
  await userEvent.upload(screen.getByLabelText(/dealer paperwork/i), file);

  await waitFor(() =>
    expect(calls.filter(call => call.method === "POST" && call.url.endsWith("/documents")))
      .toHaveLength(2));
  const posts = calls.filter(call => call.method === "POST" && call.url.endsWith("/documents"));
  expect(posts[0].headers["Idempotency-Key"]).toBe(posts[1].headers["Idempotency-Key"]);
  expect(posts[0].headers["Idempotency-Key"]).toContain("order.pdf");
});

test("a request key that already holds different content is resolved with a fresh key", async () => {
  const calls = stubApi({
    uploads: [
      { status: 409, body: { detail: "Idempotency key was already used for different content" } },
      { status: 202, body: started("doc-1", "op-1") },
    ],
    operations: [{ body: operation("EXTRACTION_COMPLETE") }],
    documents: [{ body: { pages: [page(1)] } }],
  });
  upload();
  await waitForLimits();

  await userEvent.upload(screen.getByLabelText(/dealer paperwork/i), pdf("order.pdf"));

  await waitFor(() => expect(screen.getByText(/terms read/i)).toBeInTheDocument());
  const posts = calls.filter(call => call.method === "POST" && call.url.endsWith("/documents"));
  expect(posts).toHaveLength(2);
  expect(posts[0].headers["Idempotency-Key"]).not.toBe(posts[1].headers["Idempotency-Key"]);
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});

test("client pre-checks refuse what the configured limits refuse, before any bytes are sent", async () => {
  const calls = stubApi({});
  upload();
  await waitForLimits();
  const chooser = screen.getByLabelText(/dealer paperwork/i);

  await userEvent.upload(chooser, new File([], "empty.pdf", { type: "application/pdf" }));
  expect(await screen.findByText(/“empty.pdf” has no content/i)).toBeInTheDocument();

  // `accept` is only a hint a file manager may let the Buyer override, so the check runs anyway.
  await userEvent.upload(
    chooser, new File(["not paperwork"], "notes.txt", { type: "text/plain" }),
    { applyAccept: false });
  expect(await screen.findByText(/“notes.txt” is not a PDF, JPEG, PNG, or HEIC/i)).toBeInTheDocument();

  await userEvent.upload(chooser, pdf("huge.pdf", LIMITS.maxFileBytes + 1));
  expect(await screen.findByText(/“huge.pdf” is over the 20 MB limit/i)).toBeInTheDocument();

  expect(calls.every(call => call.method === "GET")).toBe(true);
});

test("photo pre-checks mirror the configured pixel and resolution limits", async () => {
  const calls = stubApi({});
  const decoded = { width: 1, height: 1, close: vi.fn() };
  vi.stubGlobal("createImageBitmap", vi.fn(async () => decoded));
  upload();
  await waitForLimits();
  const chooser = screen.getByLabelText(/dealer paperwork/i);

  await userEvent.upload(chooser, photo("tiny.jpg"));
  expect(await screen.findByText(/“tiny.jpg” is too small to read \(at least 2×2 pixels\)/i))
    .toBeInTheDocument();

  decoded.width = 8000;
  decoded.height = 6000;
  await userEvent.upload(chooser, photo("enormous.jpg"));
  expect(await screen.findByText(/“enormous.jpg” is over the 40 megapixels limit/i))
    .toBeInTheDocument();

  expect(calls.every(call => call.method === "GET")).toBe(true);
  expect(decoded.close).toHaveBeenCalledTimes(2);
});

test("an image the browser cannot decode is left for the service to judge", async () => {
  const calls = stubApi({
    uploads: [{ status: 202, body: started("doc-1", "op-1") }],
    operations: [{ body: operation("EXTRACTION_COMPLETE") }],
    documents: [{ body: { pages: [page(1)] } }],
  });
  vi.stubGlobal("createImageBitmap", vi.fn(async () => {
    throw new Error("unsupported on this browser");
  }));
  upload();
  await waitForLimits();

  await userEvent.upload(screen.getByLabelText(/photograph a page/i),
    new File(["heic bytes"], "IMG_0001.heic", { type: "image/heic" }));

  await waitFor(() =>
    expect(calls.some(call => call.method === "POST" && call.url.endsWith("/documents"))).toBe(true));
});

test("every rejection class the service can raise has guidance naming the file and a next step", () => {
  const codes = [...intakeRejectionCodes(), ...operationFailureCodes()];
  expect(codes.length).toBeGreaterThan(20);

  for (const code of codes) {
    const guidance = refusalGuidance[code];
    expect(guidance, `no Buyer-facing guidance for ${code}`).toBeDefined();
    const message = guidance("dealer-order.pdf", LIMITS);
    expect(message, code).toContain("dealer-order.pdf");
    // Actionable means it says what to do, not only what went wrong.
    expect(message, code).toMatch(/\b(add|choose|ask|retry|split|lower|hold|grant|wait|photograph|check|re-export|resize)\b/i);
  }
});

test("a rejected upload names the file and what to do next, for each class in turn", async () => {
  const classes = [
    ["ENCRYPTED_FILE", /“locked.pdf” is password-protected/i],
    ["MALWARE_DETECTED", /“locked.pdf” failed the malware scan/i],
    ["PAGE_LIMIT_EXCEEDED", /“locked.pdf” has more than 25 pages/i],
    ["SERVICE_PROCESSING_CONSENT_REQUIRED", /permission to process your case is not currently granted/i],
  ] as const;

  for (const [code, expected] of classes) {
    stubApi({ uploads: [{ status: 422, body: { code, detail: "unused", retryable: true } }] });
    upload();
    await waitForLimits();

    await userEvent.upload(screen.getByLabelText(/dealer paperwork/i), pdf("locked.pdf"));

    expect(await screen.findByRole("alert")).toHaveTextContent(expected);
    cleanup();
    vi.restoreAllMocks();
  }
});

test("an unrecognized refusal falls back to the service's own explanation", async () => {
  stubApi({ uploads: [{ status: 422, body: { code: "SOMETHING_NEW", detail: "Reason the service gave." } }] });
  upload();
  await waitForLimits();

  await userEvent.upload(screen.getByLabelText(/dealer paperwork/i), pdf("order.pdf"));

  expect(await screen.findByRole("alert"))
    .toHaveTextContent(/“order.pdf” was not accepted: Reason the service gave./i);
});

test("a revoked case token stops the upload and says how to recover it", async () => {
  stubApi({ uploads: [{ status: 401, body: { detail: "Unauthorized" } }] });
  upload();
  await waitForLimits();

  await userEvent.upload(screen.getByLabelText(/dealer paperwork/i), pdf("order.pdf"));

  expect(await screen.findByRole("alert"))
    .toHaveTextContent(/access token was not accepted. Reopen the case/i);
});

test("a failed stage retries under one key however often it is tapped, and recovers", async () => {
  const calls = stubApi({
    uploads: [{ status: 202, body: started("doc-1", "op-1") }],
    operations: [
      { body: operation("FAILED", { failedStage: "NORMALIZING", errorCode: "NORMALIZATION_FAILED" }) },
      { body: operation("EXTRACTION_COMPLETE") },
    ],
    // The first tap is answered by an outage, so the same failure is still on screen to tap again.
    retries: [
      { status: 503, body: { detail: "Document processing is temporarily unavailable." } },
      { body: operation("QUEUED", { retryCount: 1, updatedAt: "2026-09-29T12:00:05Z" }) },
    ],
    documents: [{ body: { pages: [page(1)] } }],
  });
  upload();
  await waitForLimits();
  await userEvent.upload(screen.getByLabelText(/dealer paperwork/i), pdf("retry.pdf"));

  expect(await screen.findByText(/processing failed/i)).toBeInTheDocument();
  expect(screen.getByRole("alert")).toHaveTextContent(/Preparing the pages of “retry.pdf” failed/i);

  await userEvent.click(screen.getByRole("button", { name: /retry processing/i }));
  await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent(/temporarily unavailable/i));
  await userEvent.click(screen.getByRole("button", { name: /retry processing/i }));

  await waitFor(() => expect(screen.getByText(/terms read/i)).toBeInTheDocument());
  const retries = calls.filter(call => call.url.endsWith("/retries"));
  expect(retries).toHaveLength(2);
  expect(retries[0].url).toBe("/api/v1/operations/op-1/retries");
  expect(retries[0].headers["Idempotency-Key"]).toBe(retries[1].headers["Idempotency-Key"]);
  expect(retries[0].headers["Idempotency-Key"]).toContain("op-1");
});

test("a second, distinct failure retries under its own key", async () => {
  const calls = stubApi({
    uploads: [{ status: 202, body: started("doc-1", "op-1") }],
    operations: [
      { body: operation("FAILED", { failedStage: "EXTRACTING", errorCode: "EXTRACTION_FAILED" }) },
      { body: operation("FAILED", {
        failedStage: "EXTRACTING", errorCode: "PROVIDER_UNAVAILABLE",
        updatedAt: "2026-09-29T12:07:00Z",
      }) },
    ],
    retries: [
      { body: operation("READY_FOR_EXTRACTION", { retryCount: 1 }) },
      { body: operation("READY_FOR_EXTRACTION", { retryCount: 2 }) },
    ],
  });
  upload();
  await waitForLimits();
  await userEvent.upload(screen.getByLabelText(/dealer paperwork/i), pdf("retry.pdf"));

  expect(await screen.findByText(/processing failed/i)).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: /retry processing/i }));
  await waitFor(() =>
    expect(screen.getByRole("alert")).toHaveTextContent(/service that reads “retry.pdf” is unavailable/i));
  await userEvent.click(screen.getByRole("button", { name: /retry processing/i }));

  await waitFor(() => expect(calls.filter(call => call.url.endsWith("/retries"))).toHaveLength(2));
  const retries = calls.filter(call => call.url.endsWith("/retries"));
  expect(retries[0].headers["Idempotency-Key"]).not.toBe(retries[1].headers["Idempotency-Key"]);
});

test("page-quality flags are shown with a path to add or replace pages", async () => {
  stubApi({
    uploads: [{
      status: 202,
      body: started("doc-1", "op-1", [
        page(1, { likelyRotated: true }),
        page(2, { likelyCropped: true, likelyDuplicate: true }),
      ]),
    }],
    operations: [{ body: operation("EXTRACTION_COMPLETE") }],
    documents: [{
      body: {
        pages: [
          page(1, { likelyRotated: true }),
          page(2, { likelyCropped: true, likelyDuplicate: true }),
        ],
      },
    }],
  });
  upload();
  await waitForLimits();

  await userEvent.upload(screen.getByLabelText(/dealer paperwork/i), pdf("worksheet.pdf"));

  expect(await screen.findByText(/page 1 looks rotated/i)).toBeInTheDocument();
  expect(screen.getByText(/page 2 repeats another page/i)).toBeInTheDocument();
  expect(screen.getByText(/page 2 looks cropped/i)).toBeInTheDocument();

  await userEvent.click(screen.getByRole("button", { name: /add or replace pages/i }));
  expect(screen.getByText(/choose the missing or corrected pages of “worksheet.pdf”/i))
    .toBeInTheDocument();
  expect(screen.getByLabelText(/dealer paperwork/i)).toHaveFocus();
});

test("document bytes never reach browser storage, a query string, or the console", async () => {
  const calls = stubApi({
    uploads: [{ status: 202, body: started("doc-1", "op-1") }],
    operations: [{ body: operation("EXTRACTION_COMPLETE") }],
    documents: [{ body: { pages: [page(1)] } }],
  });
  const stored = vi.spyOn(Storage.prototype, "setItem");
  const logs = (["log", "info", "warn", "error", "debug"] as const)
    .map(level => vi.spyOn(console, level).mockImplementation(() => undefined));
  upload();
  await waitForLimits();

  await userEvent.upload(screen.getByLabelText(/dealer paperwork/i), pdf("order.pdf"));
  await waitFor(() => expect(screen.getByText(/terms read/i)).toBeInTheDocument());

  expect(stored).not.toHaveBeenCalled();
  logs.forEach(spy => expect(spy).not.toHaveBeenCalled());
  for (const call of calls) {
    expect(call.url).not.toContain("?");
    expect(call.url).not.toContain("%PDF");
  }
  // jsdom implements no IndexedDB and no cookie jar worth spying on, so this is read from source.
  const source = readFileSync("src/DealerDocumentUpload.tsx", "utf8");
  for (const api of ["localStorage", "sessionStorage", "indexedDB", "document.cookie", "console."]) {
    expect(source, `${api} has no place in document intake`).not.toContain(api);
  }
});

/** The rejection classes the document intake exception handler can answer with. */
function intakeRejectionCodes() {
  const codes = new Set<string>();
  for (const name of readdirSync(DOCUMENT_SOURCES).filter(file => file.endsWith(".java"))) {
    const source = readFileSync(`${DOCUMENT_SOURCES}/${name}`, "utf8");
    for (const match of source.matchAll(/(?:new InvalidDocumentException|\binvalid)\(\s*"([A-Z_]+)"/g)) {
      codes.add(match[1]);
    }
    // The parser process narrows an untrusted worker code to this allowlist before reporting it.
    for (const match of source.matchAll(/String safeCode =[\s\S]*?Set\.of\(([\s\S]*?)\)\s*\.contains/g)) {
      for (const quoted of match[1].matchAll(/"([A-Z_]+)"/g)) codes.add(quoted[1]);
    }
  }
  const handler = readFileSync(
    "../backend/src/main/java/com/dealanalyzer/api/DocumentIntakeExceptionHandler.java", "utf8");
  for (const match of handler.matchAll(/setProperty\("code",\s*"([A-Z_]+)"\)/g)) codes.add(match[1]);
  return [...codes];
}

/** The error codes a failed document operation can carry back through polling. */
function operationFailureCodes() {
  const codes = new Set<string>();
  const service = readFileSync(`${DOCUMENT_SOURCES}/DocumentIntakeService.java`, "utf8");
  for (const match of service.matchAll(/"(EXTRACTION_FAILED|NORMALIZATION_FAILED)"/g)) {
    codes.add(match[1]);
  }
  const persistence = readFileSync(
    `${DOCUMENT_SOURCES}/persistence/DocumentIntakePersistence.java`, "utf8");
  for (const match of persistence.matchAll(/errorCode = "([A-Z_]+)"/g)) codes.add(match[1]);
  const meter = readFileSync(
    "../backend/src/main/java/com/dealanalyzer/operations/ProviderMeter.java", "utf8");
  for (const status of meter.match(/enum Status \{([^}]*)\}/)![1].split(",")) {
    const name = status.trim();
    if (name && name !== "SUCCESS") codes.add(name);
  }
  return [...codes];
}
