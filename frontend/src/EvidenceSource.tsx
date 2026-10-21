import { useEffect, useState } from "react";
import * as format from "./i18n/format";
import { en } from "./i18n/en";
import { fillIn } from "./i18n/messages";
import { useMessages, type Messages } from "./i18n/LanguageProvider";

export type SourceRegion = { x: number; y: number; width: number; height: number };
export type DocumentSource = {
  documentId: string | null; extractionId: string | null; offerVersion: number | null;
  wording: string | null; pageNumber: number; region: SourceRegion;
};
export type Operand = { operator: string | null; label: string; minorUnits: number | null; value: string | null };
export type Calculation = { operands: Operand[]; notes: string[] };

/** `unknown` is the current language's word for a missing amount; callers outside a component get the English one. */
export const formatMoney = (minor: number | null | undefined, currency = "USD", unknown: string = en.paperwork.common.unknown) =>
  minor == null ? unknown : format.money(minor, { currency });

const percent = (value: number) => `${Math.round(Math.min(1, Math.max(0, value)) * 100)}%`;

export function describeRegion(region: SourceRegion, t: Messages["paperwork"]["evidenceSource"] = en.paperwork.evidenceSource) {
  return fillIn(format.locale(), t.region,
    { left: percent(region.x), top: percent(region.y), width: percent(region.width), height: percent(region.height) });
}

/** A dealer document line: its wording, page and the outlined region it was read from. */
export function DocumentSourceView({ source, caseId, token, what }: {
  source: DocumentSource; caseId?: string; token?: string; what: string;
}) {
  const { m: { paperwork: { evidenceSource: t, common } }, fill } = useMessages();
  const [image, setImage] = useState<string>();
  const [status, setStatus] = useState("");
  useEffect(() => () => { if (image) URL.revokeObjectURL(image); }, [image]);
  const place = (value: number) => `${Math.round(Math.min(1, Math.max(0, value)) * 10000) / 100}%`;
  const canShow = caseId && token && source.documentId;
  async function show() {
    setStatus(t.loadingPage);
    try {
      const response = await fetch(`/api/v1/cases/${encodeURIComponent(caseId!)}/documents/${encodeURIComponent(source.documentId!)}/pages/${source.pageNumber}/image`,
        { headers: { "Case-Access-Token": token! } });
      if (!response.ok) throw new Error();
      setImage(URL.createObjectURL(await response.blob())); setStatus("");
    } catch { setStatus(t.pageUnavailable); }
  }
  return <div className="evidence-source">
    <p>
      {source.offerVersion != null
        ? source.wording
          ? fill(t.readFromVersionWording, { page: source.pageNumber, version: source.offerVersion, wording: source.wording })
          : fill(t.readFromVersion, { page: source.pageNumber, version: source.offerVersion })
        : source.wording
          ? fill(t.readFromWording, { page: source.pageNumber, wording: source.wording })
          : fill(t.readFrom, { page: source.pageNumber })}
      {" "}{fill(t.regionSentence, { region: describeRegion(source.region, t) })}
    </p>
    <figure className={`source-page${image ? " source-page-shown" : ""}`}>
      <div className="source-page-frame">
        {image
          ? <img src={image} alt={fill(t.pageImage, { page: source.pageNumber, what })} />
          : <div className="source-page-placeholder" role="img" aria-label={fill(t.pageOutline, { page: source.pageNumber, what })} />}
        <span className="source-region" aria-hidden="true"
          style={{ left: place(source.region.x), top: place(source.region.y), width: place(source.region.width), height: place(source.region.height) }} />
      </div>
      <figcaption>{fill(common.pageOutlined, { page: source.pageNumber })}</figcaption>
    </figure>
    {canShow && !image && <button type="button" className="btn-secondary" onClick={show}>{fill(t.showPage, { page: source.pageNumber })}</button>}
    {status && <p role="status">{status}</p>}
  </div>;
}

/** Inputs and operation written out so the result can be checked by hand. */
export function Arithmetic({ calculation, result, currency = "USD", label }: {
  calculation: Calculation; result: number | null; currency?: string; label: string;
}) {
  const { m: { paperwork: { evidenceSource: t, common } }, fill } = useMessages();
  const shown = (operand: Operand) => operand.minorUnits != null ? formatMoney(operand.minorUnits, currency, common.unknown) : operand.value ?? common.unknown;
  return <div className="arithmetic">
    {calculation.operands.length > 0 && <ol aria-label={fill(t.calculationOf, { label })}>
      {calculation.operands.map((operand, index) => <li key={index}>
        <span className="arithmetic-operator">{operand.operator ?? ""}</span>
        <span className="arithmetic-value">{shown(operand)}</span>
        <span className="arithmetic-label">{operand.label}</span>
      </li>)}
      <li className="arithmetic-result">
        <span className="arithmetic-operator">=</span>
        <span className="arithmetic-value">{formatMoney(result, currency, common.unknown)}</span>
        <span className="arithmetic-label">{label}</span>
      </li>
    </ol>}
    {calculation.notes.map(note => <p key={note}>{note}</p>)}
  </div>;
}
