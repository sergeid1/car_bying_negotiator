import { useEffect, useState } from "react";

export type SourceRegion = { x: number; y: number; width: number; height: number };
export type DocumentSource = {
  documentId: string | null; extractionId: string | null; offerVersion: number | null;
  wording: string | null; pageNumber: number; region: SourceRegion;
};
export type Operand = { operator: string | null; label: string; minorUnits: number | null; value: string | null };
export type Calculation = { operands: Operand[]; notes: string[] };

export const formatMoney = (minor: number | null | undefined, currency = "USD") => minor == null ? "Unknown"
  : new Intl.NumberFormat("en-US", { style: "currency", currency }).format(minor / 100);

const percent = (value: number) => `${Math.round(Math.min(1, Math.max(0, value)) * 100)}%`;

export function describeRegion(region: SourceRegion) {
  return `${percent(region.x)} from the left and ${percent(region.y)} from the top, ${percent(region.width)} wide by ${percent(region.height)} tall`;
}

/** A dealer document line: its wording, page and the outlined region it was read from. */
export function DocumentSourceView({ source, caseId, token, what }: {
  source: DocumentSource; caseId?: string; token?: string; what: string;
}) {
  const [image, setImage] = useState<string>();
  const [status, setStatus] = useState("");
  useEffect(() => () => { if (image) URL.revokeObjectURL(image); }, [image]);
  const place = (value: number) => `${Math.round(Math.min(1, Math.max(0, value)) * 10000) / 100}%`;
  const canShow = caseId && token && source.documentId;
  async function show() {
    setStatus("Loading page…");
    try {
      const response = await fetch(`/api/v1/cases/${encodeURIComponent(caseId!)}/documents/${encodeURIComponent(source.documentId!)}/pages/${source.pageNumber}/image`,
        { headers: { "Case-Access-Token": token! } });
      if (!response.ok) throw new Error();
      setImage(URL.createObjectURL(await response.blob())); setStatus("");
    } catch { setStatus("The page image is unavailable. The page number and region above still identify the source."); }
  }
  return <div className="evidence-source">
    <p>
      Read from page {source.pageNumber} of the dealer document{source.offerVersion != null && <> (Dealer Offer version {source.offerVersion})</>}
      {source.wording && <>, where it says “{source.wording}”</>}.
      {" "}Region: {describeRegion(source.region)}.
    </p>
    <figure className={`source-page${image ? " source-page-shown" : ""}`}>
      <div className="source-page-frame">
        {image
          ? <img src={image} alt={`Page ${source.pageNumber} of the dealer document, where ${what} was read`} />
          : <div className="source-page-placeholder" role="img" aria-label={`Outline of page ${source.pageNumber} with the region where ${what} was read`} />}
        <span className="source-region" aria-hidden="true"
          style={{ left: place(source.region.x), top: place(source.region.y), width: place(source.region.width), height: place(source.region.height) }} />
      </div>
      <figcaption>Page {source.pageNumber} · outlined where it was read</figcaption>
    </figure>
    {canShow && !image && <button type="button" className="btn-secondary" onClick={show}>Show page {source.pageNumber}</button>}
    {status && <p role="status">{status}</p>}
  </div>;
}

/** Inputs and operation written out so the result can be checked by hand. */
export function Arithmetic({ calculation, result, currency = "USD", label }: {
  calculation: Calculation; result: number | null; currency?: string; label: string;
}) {
  const shown = (operand: Operand) => operand.minorUnits != null ? formatMoney(operand.minorUnits, currency) : operand.value ?? "Unknown";
  return <div className="arithmetic">
    {calculation.operands.length > 0 && <ol aria-label={`Calculation of ${label}`}>
      {calculation.operands.map((operand, index) => <li key={index}>
        <span className="arithmetic-operator">{operand.operator ?? ""}</span>
        <span className="arithmetic-value">{shown(operand)}</span>
        <span className="arithmetic-label">{operand.label}</span>
      </li>)}
      <li className="arithmetic-result">
        <span className="arithmetic-operator">=</span>
        <span className="arithmetic-value">{formatMoney(result, currency)}</span>
        <span className="arithmetic-label">{label}</span>
      </li>
    </ol>}
    {calculation.notes.map(note => <p key={note}>{note}</p>)}
  </div>;
}
