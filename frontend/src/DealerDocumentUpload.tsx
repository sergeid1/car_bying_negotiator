import { ChangeEvent, useEffect, useRef, useState } from "react";
import * as format from "./i18n/format";
import { en } from "./i18n/en";
import { fillIn, type Message, type Placeholders, type Values } from "./i18n/messages";
import { useMessages, type Messages } from "./i18n/LanguageProvider";

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

type Copy = Messages["paperwork"]["dealerDocumentUpload"];

/** English, for a caller outside a component; components pass the current language's copy. */
const ENGLISH: Copy = en.paperwork.dealerDocumentUpload;

function fillCopy<M extends Message>(message: M, values: Values<M>) {
  return fillIn(format.locale(), message, values);
}

function fileSizeLimit(bytes: number, t: Copy) {
  return bytes >= 1024 * 1024
    ? fillCopy(t.megabytes, { size: String(Math.round(bytes / 104857.6) / 10) })
    : fillCopy(t.kilobytes, { size: String(Math.max(1, Math.round(bytes / 1024))) });
}

function pixelLimit(pixels: number, t: Copy) {
  return pixels >= 1_000_000
    ? fillCopy(t.megapixels, { size: String(Math.round(pixels / 100_000) / 10) })
    : fillCopy(t.pixels, { size: format.number(pixels) });
}

type Guidance = (file: string, limits?: DocumentLimits, t?: Copy) => string;

/** The refusals whose only changing part is the file's name. */
type NamedRefusal = { [K in keyof Copy["refusals"]]: Placeholders<Copy["refusals"][K]> extends "file" ? K : never }[keyof Copy["refusals"]];

const named = (key: NamedRefusal): Guidance => (file, _limits, t = ENGLISH) => fillCopy(t.refusals[key], { file });

/**
 * One actionable sentence per rejection class the document intake can raise, naming the file and
 * what to do next. Every code the service can return has an entry; the server's own detail is only
 * a fallback for a refusal this client has not been taught about.
 */
export const refusalGuidance: Record<string, Guidance> = {
  EMPTY_FILE: named("emptyFile"),
  FILE_TOO_LARGE: (file, limits, t = ENGLISH) => limits
    ? fillCopy(t.refusals.fileTooLarge, { file, limit: fileSizeLimit(limits.maxFileBytes, t) })
    : fillCopy(t.refusals.fileTooLargeNoLimit, { file }),
  EXECUTABLE_FILE: named("executable"),
  UNSUPPORTED_FILE: named("unsupported"),
  ENCRYPTED_FILE: named("encrypted"),
  MALFORMED_FILE: named("malformed"),
  PAGE_LIMIT_EXCEEDED: (file, limits, t = ENGLISH) => limits
    ? fillCopy(t.refusals.pageLimit, { file, pages: String(limits.maxPages) })
    : fillCopy(t.refusals.pageLimitNoLimit, { file }),
  RESOLUTION_TOO_LOW: (file, limits, t = ENGLISH) => limits
    ? fillCopy(t.refusals.resolution, { file, width: String(limits.minWidth), height: String(limits.minHeight) })
    : fillCopy(t.refusals.resolutionNoLimit, { file }),
  PIXEL_LIMIT_EXCEEDED: (file, limits, t = ENGLISH) => limits
    ? fillCopy(t.refusals.pixelLimit, { file, limit: pixelLimit(limits.maxPixels, t) })
    : fillCopy(t.refusals.pixelLimitNoLimit, { file }),
  DECOMPRESSION_RISK: named("decompression"),
  MALWARE_DETECTED: named("malware"),
  MALWARE_SCAN_UNAVAILABLE: named("scanUnavailable"),
  PARSER_BUSY: named("parserBusy"),
  PARSER_UNAVAILABLE: named("parserUnavailable"),
  PARSER_TIMEOUT: named("parserTimeout"),
  SERVICE_PROCESSING_CONSENT_REQUIRED: named("consentRequired"),
  UPLOAD_READ_FAILED: named("readFailed"),
  DOCUMENT_EXPIRED: named("expired"),
  NORMALIZATION_FAILED: named("normalizationFailed"),
  EXTRACTION_FAILED: named("extractionFailed"),
  PROVIDER_UNAVAILABLE: named("providerUnavailable"),
  PROVIDER_FAILURE: named("providerFailure"),
  CONTRACT_FAILURE: named("contractFailure"),
  BUYER_INPUT: named("buyerInput"),
  INTERNAL: named("internal"),
};

function statusGuidance(status: number, file: string, detail: string | undefined, t: Copy) {
  if (status === 401 || status === 403) return fillCopy(t.refusals.tokenRejected, { file });
  if (status === 404) return fillCopy(t.refusals.caseUnavailable, { file });
  if (status === 409) return fillCopy(t.refusals.conflict, { file });
  if (status === 429) return fillCopy(t.refusals.tooManyRequests, { file });
  return detail
    ? fillCopy(t.refusals.notAccepted, { file, detail })
    : fillCopy(t.refusals.uploadFailed, { file });
}

/** A known code comes first, then the service's own detail. */
export function refusalMessage(
  file: string, status: number, code?: string, detail?: string, limits?: DocumentLimits, t: Copy = ENGLISH,
) {
  const guidance = code ? refusalGuidance[code] : undefined;
  return guidance ? guidance(file, limits, t) : statusGuidance(status, file, detail, t);
}

function failureMessage(operation: OperationView, file: string, limits: DocumentLimits | undefined, t: Copy) {
  const guidance = operation.errorCode ? refusalGuidance[operation.errorCode] : undefined;
  if (guidance) {
    return guidance(file, limits, t);
  }
  return operation.error
    ? fillCopy(t.refusals.processingFailedDetail, { file, error: operation.error })
    : fillCopy(t.refusals.processingFailed, { file });
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
async function precheck(file: File, limits: DocumentLimits | undefined, t: Copy) {
  if (file.size === 0) {
    return refusalGuidance.EMPTY_FILE(file.name, limits, t);
  }
  if (!limits) {
    return undefined;
  }
  const mediaType = supportedMediaType(file, limits.acceptedMediaTypes);
  // With neither a reported type nor a known extension there is nothing to judge; the service decides.
  if (!mediaType && (file.type !== "" || /\.[a-z0-9]+$/i.test(file.name))) {
    return refusalGuidance.UNSUPPORTED_FILE(file.name, limits, t);
  }
  if (file.size > limits.maxFileBytes) {
    return refusalGuidance.FILE_TOO_LARGE(file.name, limits, t);
  }
  return mediaType && mediaType !== "application/pdf" ? pixelPrecheck(file, limits, t) : undefined;
}

/** Uses the browser's own image decoder for dimensions only; an image it cannot read is the service's call. */
async function pixelPrecheck(file: File, limits: DocumentLimits, t: Copy) {
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
      return refusalGuidance.RESOLUTION_TOO_LOW(file.name, limits, t);
    }
    if (bitmap.width * bitmap.height > limits.maxPixels) {
      return refusalGuidance.PIXEL_LIMIT_EXCEEDED(file.name, limits, t);
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

function pageWarnings(pages: PageQuality[] = [], t: Copy) {
  return pages.flatMap(page =>
    [
      page.likelyMissing && fillCopy(t.pageMissing, { page: page.pageNumber }),
      page.likelyDuplicate && fillCopy(t.pageDuplicate, { page: page.pageNumber }),
      page.likelyRotated && fillCopy(t.pageRotated, { page: page.pageNumber }),
      page.likelyCropped && fillCopy(t.pageCropped, { page: page.pageNumber }),
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
  const { m: { paperwork: { dealerDocumentUpload: t } }, fill } = useMessages();
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
      const refusal = await precheck(file, limits, t);
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
          problem: refusalMessage(file.name, response.status, code, detail, limits, t),
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
        problem: refusalMessage(file.name, 0, undefined, undefined, limits, t),
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
            problem: refusalMessage(item.filename, response.status, code, detail, limits, t),
          });
        }
        return;
      }
      const operation: OperationView = await response.json();
      const change: Partial<Upload> = { stage: operation.stage, problem: undefined };
      if (operation.stage === "FAILED") {
        change.failedAt = operation.updatedAt;
        change.problem = failureMessage(operation, item.filename, limits, t);
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
          problem: refusalMessage(item.filename, response.status, code, detail, limits, t),
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
          <p className="step">{heading?.step ?? t.step}</p>
          <h2 id={heading?.id ?? "documents-heading"}>{heading?.title ?? t.title}</h2>
        </div>
      </div>

      <p className="muted">{t.intro}</p>
      <p className="muted">
        {limits
          ? fill(t.limits, { size: fileSizeLimit(limits.maxFileBytes, t), pages: String(limits.maxPages), pixels: pixelLimit(limits.maxPixels, t) })
          : t.accepted}
      </p>

      {replacing && (
        <p className="notice">{fill(t.replacing, { file: replacing })}</p>
      )}

      <label>
        {t.chooser}
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
        {t.camera}
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
        <ul className="upload-list" aria-label={t.uploads}>
          {uploads.map(item => {
            const warnings = pageWarnings(item.pages, t);
            const stage = item.state === "SENDING" ? "SENDING" : item.stage;
            return (
              <li key={item.key} className="upload-item">
                <p className="upload-name">{item.filename}</p>

                {item.state !== "REFUSED" && (
                  <>
                    <p className="operation" role="status">
                      {!terminal(stage) && <span className="spinner" aria-hidden="true" />}
                      {(t.stages as Record<string, string>)[stage ?? ""] ?? stage}
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
                      {item.retrying ? t.retrying : t.retry}
                    </button>
                  </div>
                )}

                {warnings.length > 0 && (
                  <div className="notice">
                    <div>
                      <strong>{t.checkPages}</strong>
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
                        {t.addOrReplace}
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
