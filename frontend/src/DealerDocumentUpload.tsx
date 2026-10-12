import { ChangeEvent, useEffect, useRef, useState } from "react";

/** The intake limits the service publishes, so a pre-check never uses a looser threshold. */
export type DocumentLimits = {
  acceptedMediaTypes: string[];
  maxFileBytes: number;
  maxPages: number;
  maxPixels: number;
  minWidth: number;
  minHeight: number;
};

type PageQuality = {
  pageNumber: number;
  likelyMissing: boolean;
  likelyDuplicate: boolean;
  likelyRotated: boolean;
  likelyCropped: boolean;
};

type OperationView = {
  id: string;
  documentId: string;
  stage: string;
  failedStage?: string | null;
  errorCode?: string | null;
  error?: string | null;
  retryCount: number;
  updatedAt: string;
};

type Upload = {
  /** Identifies this row while it is on screen; the upload's idempotency key is derived per file. */
  key: string;
  filename: string;
  state: "SENDING" | "TRACKING" | "REFUSED";
  operationId?: string;
  documentId?: string;
  stage?: string;
  /** The failed operation's `updatedAt`, which keeps a retry key stable across repeated taps. */
  failedAt?: string;
  problem?: string;
  pages?: PageQuality[];
  retrying?: boolean;
};

const EXTENSIONS: Record<string, string[]> = {
  "application/pdf": [".pdf"],
  "image/jpeg": [".jpg", ".jpeg"],
  "image/png": [".png"],
  "image/heic": [".heic", ".heif"],
};

const STAGE_ORDER = [
  "QUEUED", "NORMALIZING", "READY_FOR_EXTRACTION", "EXTRACTING", "EXTRACTION_COMPLETE",
];

/** Stage names the Buyer can act on: each says what is happening to their paperwork. */
const STAGE_LABELS: Record<string, string> = {
  SENDING: "Sending to the service",
  QUEUED: "Waiting to be processed",
  NORMALIZING: "Preparing the pages",
  READY_FOR_EXTRACTION: "Pages prepared",
  EXTRACTING: "Reading the deal terms",
  EXTRACTION_COMPLETE: "Terms read — ready to verify",
  FAILED: "Processing failed",
};

function fileSizeLimit(bytes: number) {
  return bytes >= 1024 * 1024
    ? `${Math.round(bytes / 104857.6) / 10} MB`
    : `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

function pixelLimit(pixels: number) {
  return pixels >= 1_000_000
    ? `${Math.round(pixels / 100_000) / 10} megapixels`
    : `${pixels.toLocaleString("en-US")} pixels`;
}

/**
 * One actionable sentence per rejection class the document intake can raise, naming the file and
 * what to do next. Every code the service can return has an entry; the server's own detail is only
 * a fallback for a refusal this client has not been taught about.
 */
export const refusalGuidance: Record<string, (file: string, limits?: DocumentLimits) => string> = {
  EMPTY_FILE: file =>
    `“${file}” has no content. Photograph the page again, or re-export the PDF, and add it.`,
  FILE_TOO_LARGE: (file, limits) =>
    `“${file}” is over the ${limits ? fileSizeLimit(limits.maxFileBytes) : "size"} limit for one file.`
    + " Photograph the pages in smaller batches, or export a smaller PDF, and add them.",
  EXECUTABLE_FILE: file =>
    `“${file}” is a program, not paperwork. Add the Dealer's document as a PDF, JPEG, PNG, or HEIC file.`,
  UNSUPPORTED_FILE: file =>
    `“${file}” is not a PDF, JPEG, PNG, or HEIC document. Photograph the page with your camera, or ask`
    + " the Dealer to email a PDF, and add that instead.",
  ENCRYPTED_FILE: file =>
    `“${file}” is password-protected. Ask the Dealer for an unlocked copy, or photograph the printed`
    + " pages, and add that instead.",
  MALFORMED_FILE: file =>
    `“${file}” could not be read safely. Photograph the page again, or ask the Dealer to export the`
    + " PDF again, and add it.",
  PAGE_LIMIT_EXCEEDED: (file, limits) =>
    `“${file}” has more than ${limits ? limits.maxPages : "the maximum number of"} pages. Split it and`
    + " add each part as its own document.",
  RESOLUTION_TOO_LOW: (file, limits) =>
    `“${file}” is too small to read${limits ? ` (at least ${limits.minWidth}×${limits.minHeight} pixels)` : ""}.`
    + " Hold the camera closer so the page fills the frame, and add the photo again.",
  PIXEL_LIMIT_EXCEEDED: (file, limits) =>
    `“${file}” is over the ${limits ? pixelLimit(limits.maxPixels) : "pixel"} limit. Lower your camera`
    + " resolution, or resize the photo, and add it again.",
  DECOMPRESSION_RISK: file =>
    `“${file}” expands far beyond its file size and cannot be processed safely. Re-export or resize it`
    + " and add it again.",
  MALWARE_DETECTED: file =>
    `“${file}” failed the malware scan and was not stored. Ask the Dealer for a clean copy.`,
  MALWARE_SCAN_UNAVAILABLE: file =>
    `“${file}” could not be scanned, so nothing was stored. Add it again in a few minutes.`,
  PARSER_BUSY: file =>
    `Document processing is busy, so “${file}” was not stored. Add it again in a moment.`,
  PARSER_UNAVAILABLE: file =>
    `Document processing is unavailable, so “${file}” was not stored. Add it again in a few minutes.`,
  PARSER_TIMEOUT: file =>
    `“${file}” took too long to process. Add one page at a time, or a simpler PDF export.`,
  SERVICE_PROCESSING_CONSENT_REQUIRED: file =>
    `“${file}” was not uploaded because permission to process your case is not currently granted.`
    + " Grant it in your case permissions, then add the file again.",
  UPLOAD_READ_FAILED: file =>
    `“${file}” could not be read from this device. Choose the file again and add it.`,
  DOCUMENT_EXPIRED: file =>
    `“${file}” reached its retention deadline before processing finished. Add the file again.`,
  NORMALIZATION_FAILED: file =>
    `Preparing the pages of “${file}” failed. Retry processing — the file is stored and does not need`
    + " to be added again.",
  EXTRACTION_FAILED: file =>
    `Reading the terms from “${file}” failed. Retry processing — the file is stored and does not need`
    + " to be added again.",
  PROVIDER_UNAVAILABLE: file =>
    `The service that reads “${file}” is unavailable. Retry processing in a few minutes; the file is`
    + " stored.",
  PROVIDER_FAILURE: file =>
    `The service that reads “${file}” returned an error. Retry processing; the file is stored.`,
  CONTRACT_FAILURE: file =>
    `The terms read from “${file}” did not pass the service's own checks, so none were kept. Retry`
    + " processing, or add a clearer copy of the document.",
  BUYER_INPUT: file =>
    `“${file}” does not look like the Dealer paperwork this case needs. Check you added the buyer's`
    + " order or quote, and add the right document.",
  INTERNAL: file =>
    `Processing “${file}” failed inside the service. Retry processing; the file is stored.`,
};

function statusGuidance(status: number, file: string, detail?: string) {
  if (status === 401 || status === 403) {
    return `“${file}” was not uploaded because this case's access token was not accepted. Reopen the`
      + " case with the token you saved, then add the file again.";
  }
  if (status === 404) {
    return `“${file}” was not uploaded because this Buying Case is no longer available. It may have`
      + " been deleted or expired.";
  }
  if (status === 409) {
    return `A different file was already uploaded for this request, so “${file}” was not stored.`
      + " Choose the file again to add it.";
  }
  if (status === 429) {
    return `Too many requests were made, so “${file}” was not uploaded. Wait a moment and add it again.`;
  }
  return detail
    ? `“${file}” was not accepted: ${detail}`
    : `“${file}” could not be uploaded. Check your connection and add it again.`;
}

/** A known code comes first, then the service's own detail. */
export function refusalMessage(
  file: string, status: number, code?: string, detail?: string, limits?: DocumentLimits,
) {
  const guidance = code ? refusalGuidance[code] : undefined;
  return guidance ? guidance(file, limits) : statusGuidance(status, file, detail);
}

function failureMessage(operation: OperationView, file: string, limits?: DocumentLimits) {
  const guidance = operation.errorCode ? refusalGuidance[operation.errorCode] : undefined;
  if (guidance) {
    return guidance(file, limits);
  }
  return operation.error
    ? `Processing “${file}” failed: ${operation.error}`
    : `Processing “${file}” failed. Retry processing, or add a clearer copy of the document.`;
}

function supportedMediaType(file: File, accepted: string[]) {
  const reported = file.type.toLowerCase() === "image/heif" ? "image/heic" : file.type.toLowerCase();
  if (accepted.includes(reported)) {
    return reported;
  }
  const name = file.name.toLowerCase();
  return accepted.find(type => (EXTENSIONS[type] ?? []).some(extension => name.endsWith(extension)));
}

/**
 * Fast feedback for what can be judged without reading the document: the browser never parses a
 * PDF or interprets a page, and the service still inspects every accepted file for itself.
 */
async function precheck(file: File, limits?: DocumentLimits) {
  if (file.size === 0) {
    return refusalGuidance.EMPTY_FILE(file.name, limits);
  }
  if (!limits) {
    return undefined;
  }
  const mediaType = supportedMediaType(file, limits.acceptedMediaTypes);
  // With neither a reported type nor a known extension there is nothing to judge; the service decides.
  if (!mediaType && (file.type !== "" || /\.[a-z0-9]+$/i.test(file.name))) {
    return refusalGuidance.UNSUPPORTED_FILE(file.name, limits);
  }
  if (file.size > limits.maxFileBytes) {
    return refusalGuidance.FILE_TOO_LARGE(file.name, limits);
  }
  return mediaType && mediaType !== "application/pdf" ? pixelPrecheck(file, limits) : undefined;
}

/** Uses the browser's own image decoder for dimensions only; an image it cannot read is the service's call. */
async function pixelPrecheck(file: File, limits: DocumentLimits) {
  if (typeof createImageBitmap !== "function") {
    return undefined;
  }
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(file);
  } catch {
    return undefined;
  }
  try {
    if (bitmap.width < limits.minWidth || bitmap.height < limits.minHeight) {
      return refusalGuidance.RESOLUTION_TOO_LOW(file.name, limits);
    }
    if (bitmap.width * bitmap.height > limits.maxPixels) {
      return refusalGuidance.PIXEL_LIMIT_EXCEEDED(file.name, limits);
    }
    return undefined;
  } finally {
    bitmap.close?.();
  }
}

/**
 * Derived from the file rather than the attempt, so adding the same paperwork again after a dropped
 * connection replays the first upload instead of storing a second copy of it. Header values have to
 * stay short printable ASCII, and a same-key-different-content collision is answered with a 409.
 */
function uploadKey(file: File) {
  return `upload-${file.size}-${file.lastModified}-${file.name.replace(/[^\x20-\x7e]/g, "").slice(-80)}`;
}

function pageWarnings(pages: PageQuality[] = []) {
  return pages.flatMap(page =>
    [
      page.likelyMissing && `Page ${page.pageNumber} suggests a page of this document is missing.`,
      page.likelyDuplicate && `Page ${page.pageNumber} repeats another page of this document.`,
      page.likelyRotated && `Page ${page.pageNumber} looks rotated.`,
      page.likelyCropped
        && `Page ${page.pageNumber} looks cropped, so an amount near an edge may be cut off.`,
    ].filter((warning): warning is string => typeof warning === "string"));
}

function terminal(stage?: string) {
  return stage === "EXTRACTION_COMPLETE" || stage === "FAILED";
}

async function problem(response: Response) {
  try {
    const body = await response.json();
    return {
      code: typeof body?.code === "string" ? body.code : undefined,
      detail: typeof body?.detail === "string" ? body.detail : undefined,
    };
  } catch {
    return {};
  }
}

export function DealerDocumentUpload({
  caseId, token, pollDelayMs = 700, heading, onChosen, onExtracted, onFailed, initialFiles,
}: {
  caseId: string; token: string; pollDelayMs?: number;
  /** Lets another panel, such as the Desk Check, present this intake under its own heading. */
  heading?: { id: string; step: string; title: string };
  /** The chosen files stay in memory only, so a fresh case can be given the same paperwork. */
  onChosen?: (files: File[]) => void;
  onExtracted?: (documentId: string) => void;
  /** A file was refused or its processing failed, so its terms will not be read. */
  onFailed?: () => void;
  /** Paperwork already chosen elsewhere, sent once through the same pre-checks. */
  initialFiles?: File[];
}) {
  const [limits, setLimits] = useState<DocumentLimits>();
  const [limitsSettled, setLimitsSettled] = useState(false);
  const initialSent = useRef(false);
  const [uploads, setUploads] = useState<Upload[]>([]);
  const [replacing, setReplacing] = useState<string>();
  const [tick, setTick] = useState(0);
  const chooser = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let cancelled = false;
    fetch("/api/v1/document-limits")
      .then(response => (response.ok ? response.json() : undefined))
      .then(value => {
        if (!cancelled && value) setLimits(value as DocumentLimits);
      })
      .catch(() => undefined)
      .finally(() => {
        if (!cancelled) setLimitsSettled(true);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!limitsSettled || !initialFiles?.length || initialSent.current) return;
    initialSent.current = true;
    void sendAll(initialFiles);
    // Sent once, after the published limits are known.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [limitsSettled]);

  function update(key: string, change: Partial<Upload>) {
    setUploads(current => current.map(item => (item.key === key ? { ...item, ...change } : item)));
  }

  async function choose(event: ChangeEvent<HTMLInputElement>) {
    const chosen = Array.from(event.target.files ?? []);
    // Clearing the control lets the same file be chosen again after a correction.
    event.target.value = "";
    setReplacing(undefined);
    if (chosen.length > 0) onChosen?.(chosen);
    await sendAll(chosen);
  }

  async function sendAll(chosen: File[]) {
    for (const file of chosen) {
      const key = crypto.randomUUID();
      setUploads(current => [...current, { key, filename: file.name, state: "SENDING" }]);
      const refusal = await precheck(file, limits);
      if (refusal) {
        update(key, { state: "REFUSED", problem: refusal });
        onFailed?.();
      } else {
        await send(file, key);
      }
    }
  }

  async function send(file: File, key: string, idempotencyKey = uploadKey(file)) {
    const body = new FormData();
    body.append("file", file, file.name);
    try {
      const response = await fetch(
        `/api/v1/cases/${encodeURIComponent(caseId)}/documents`,
        {
          method: "POST",
          headers: { "Case-Access-Token": token, "Idempotency-Key": idempotencyKey },
          body,
        },
      );
      if (response.status === 409 && idempotencyKey !== key) {
        // Different content under the same derived key: a fresh key cannot duplicate this file.
        return send(file, key, key);
      }
      if (!response.ok) {
        const { code, detail } = await problem(response);
        update(key, {
          state: "REFUSED",
          problem: refusalMessage(file.name, response.status, code, detail, limits),
        });
        onFailed?.();
        return;
      }
      const started = await response.json();
      update(key, {
        state: "TRACKING",
        operationId: started.operation.id,
        documentId: started.document.id,
        stage: started.operation.stage,
        pages: started.document.pages ?? [],
      });
    } catch {
      update(key, {
        state: "REFUSED",
        problem: refusalMessage(file.name, 0, undefined, undefined, limits),
      });
      onFailed?.();
    }
  }

  async function follow(item: Upload) {
    try {
      const response = await fetch(
        `/api/v1/operations/${encodeURIComponent(item.operationId!)}`,
        { headers: { "Case-Access-Token": token } },
      );
      if (!response.ok) {
        // A refusal will not resolve itself; a server-side fault may, so keep following that.
        if (response.status < 500) {
          const { code, detail } = await problem(response);
          update(item.key, {
            state: "REFUSED",
            problem: refusalMessage(item.filename, response.status, code, detail, limits),
          });
        }
        return;
      }
      const operation: OperationView = await response.json();
      const change: Partial<Upload> = { stage: operation.stage, problem: undefined };
      if (operation.stage === "FAILED") {
        change.failedAt = operation.updatedAt;
        change.problem = failureMessage(operation, item.filename, limits);
      }
      if (operation.stage === "EXTRACTION_COMPLETE") {
        change.pages = (await pagesOf(item.documentId)) ?? item.pages;
      }
      update(item.key, change);
      if (operation.stage === "EXTRACTION_COMPLETE" && item.documentId) onExtracted?.(item.documentId);
      if (operation.stage === "FAILED") onFailed?.();
    } catch {
      // A dropped request on a dealership connection is retried by the next poll.
    }
  }

  async function pagesOf(documentId?: string) {
    if (!documentId) return undefined;
    try {
      const response = await fetch(
        `/api/v1/cases/${encodeURIComponent(caseId)}/documents/${encodeURIComponent(documentId)}`,
        { headers: { "Case-Access-Token": token } },
      );
      return response.ok ? ((await response.json()).pages as PageQuality[]) : undefined;
    } catch {
      return undefined;
    }
  }

  async function retryProcessing(item: Upload) {
    update(item.key, { retrying: true });
    try {
      const response = await fetch(
        `/api/v1/operations/${encodeURIComponent(item.operationId!)}/retries`,
        {
          method: "POST",
          headers: {
            "Case-Access-Token": token,
            // Derived from the failure being retried, so repeated taps replay instead of duplicating.
            "Idempotency-Key": `retry-${item.operationId}-${item.failedAt}`,
          },
        },
      );
      if (!response.ok) {
        const { code, detail } = await problem(response);
        update(item.key, {
          retrying: false,
          problem: refusalMessage(item.filename, response.status, code, detail, limits),
        });
        return;
      }
      const operation: OperationView = await response.json();
      update(item.key, {
        retrying: false,
        stage: operation.stage,
        problem: operation.stage === "FAILED" ? item.problem : undefined,
      });
    } catch {
      update(item.key, { retrying: false });
    }
  }

  const following = uploads.filter(item => item.state === "TRACKING" && !terminal(item.stage));
  const signature = following.map(item => `${item.operationId}:${item.stage}`).join("|");

  useEffect(() => {
    if (signature === "") {
      return undefined;
    }
    let cancelled = false;
    const timer = window.setTimeout(async () => {
      for (const item of following) {
        if (!cancelled) await follow(item);
      }
      if (!cancelled) setTick(value => value + 1);
    }, pollDelayMs);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
    // `tick` chains the next poll; the signature restarts it as soon as a stage or file changes.
  }, [signature, tick, pollDelayMs]);

  function addOrReplacePages(item: Upload) {
    setReplacing(item.filename);
    chooser.current?.focus();
    chooser.current?.click();
  }

  const accept = (limits?.acceptedMediaTypes ?? Object.keys(EXTENSIONS))
    .flatMap(type => [type, ...(EXTENSIONS[type] ?? [])])
    .join(",");

  return (
    <section className="panel" aria-labelledby={heading?.id ?? "documents-heading"}>
      <div className="section-heading">
        <div>
          <p className="step">{heading?.step ?? "Dealer paperwork"}</p>
          <h2 id={heading?.id ?? "documents-heading"}>{heading?.title ?? "Add the Dealer's paperwork"}</h2>
        </div>
      </div>

      <p className="muted">
        Photograph every page of the buyer's order, quote, or worksheet, or attach a PDF the Dealer
        sent you. The service reads the pages; nothing is read on this device.
      </p>
      <p className="muted">
        {limits
          ? `PDF, JPEG, PNG, and HEIC · up to ${fileSizeLimit(limits.maxFileBytes)} per file `
            + `· up to ${limits.maxPages} pages per document · up to ${pixelLimit(limits.maxPixels)} per photo.`
          : "PDF, JPEG, PNG, and HEIC are accepted."}
      </p>

      {replacing && (
        <p className="notice">
          Choose the missing or corrected pages of “{replacing}”. They are added as another document
          and processed the same way.
        </p>
      )}

      <label>
        Dealer paperwork (PDF or photos)
        <input
          ref={chooser}
          type="file"
          name="documents"
          multiple
          accept={accept}
          onChange={choose}
        />
      </label>

      <label>
        Photograph a page with your camera
        <input
          type="file"
          name="photograph"
          accept="image/jpeg,image/png,image/heic,image/heif,.jpg,.jpeg,.png,.heic,.heif"
          capture="environment"
          multiple
          onChange={choose}
        />
      </label>

      {uploads.length > 0 && (
        <ul className="upload-list" aria-label="Uploaded paperwork">
          {uploads.map(item => {
            const warnings = pageWarnings(item.pages);
            const stage = item.state === "SENDING" ? "SENDING" : item.stage;
            return (
              <li key={item.key} className="upload-item">
                <p className="upload-name">{item.filename}</p>

                {item.state !== "REFUSED" && (
                  <>
                    <p className="operation" role="status">
                      {!terminal(stage) && <span className="spinner" aria-hidden="true" />}
                      {STAGE_LABELS[stage ?? ""] ?? stage}
                    </p>
                    {stage !== "FAILED" && (
                      <ol className="stage-track" aria-hidden="true">
                        {STAGE_ORDER.map((name, index) => (
                          <li key={name} data-done={index <= STAGE_ORDER.indexOf(stage ?? "")} />
                        ))}
                      </ol>
                    )}
                  </>
                )}

                {item.problem && (
                  <p className="error" role="alert">
                    {item.problem}
                  </p>
                )}

                {item.stage === "FAILED" && item.state === "TRACKING" && (
                  <div className="actions">
                    <button
                      type="button"
                      onClick={() => retryProcessing(item)}
                      disabled={item.retrying}
                    >
                      {item.retrying ? "Retrying…" : "Retry processing"}
                    </button>
                  </div>
                )}

                {warnings.length > 0 && (
                  <div className="notice">
                    <div>
                      <strong>Check these pages before the terms are verified</strong>
                      <ul>
                        {warnings.map(warning => (
                          <li key={warning}>{warning}</li>
                        ))}
                      </ul>
                      <button
                        type="button"
                        className="btn-secondary"
                        onClick={() => addOrReplacePages(item)}
                      >
                        Add or replace pages
                      </button>
                    </div>
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
