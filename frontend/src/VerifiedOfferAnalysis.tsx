import { useEffect, useRef, useState } from "react";
import { CoreDealReport, ReportSection, type CoreReport } from "./CoreDealReport";
import { CaseDownloads } from "./CasePortability";

import { DealerFinancingForm } from "./DealerFinancing";
import { OutsideFinancing } from "./OutsideFinancing";
import { OutcomeFeedback } from "./PilotFeedback";
import { NegotiationLoader } from "./NegotiationDraft";
import { PurchasePoint } from "./OneTimeOffer";

type Props = { caseId: string; token: string; offerVersion?: number; pollDelayMs?: number };
/** A paid endpoint refused this unpurchased case: the payment point, not a failure. */
class PurchaseRequired extends Error {}

const stages = ["Finding your verified offer", "Reading market evidence", "Calculating cash economics", "Preparing your report", "Opening your report"];

export function VerifiedOfferAnalysis({ caseId, token, offerVersion, pollDelayMs = 250 }: Props) {
  const [financeCase, setFinanceCase] = useState(false);
  const [stage, setStage] = useState(0);
  const [report, setReport] = useState<CoreReport>();
  const [error, setError] = useState(false);
  const [waiting, setWaiting] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [purchaseRequired, setPurchaseRequired] = useState(false);
  const requestKey = useRef(crypto.randomUUID());
  const requestedOffer = useRef<number | undefined>(undefined);
  // Set by "Update analysis"; otherwise a report already made from this offer is reopened, not regenerated.
  const forceRun = useRef(false);
  useEffect(() => {
    let cancelled = false;
    const base = `/api/v1/cases/${encodeURIComponent(caseId)}`;
    const headers = { "Case-Access-Token": token };
    async function request(path: string, method = "GET") {
      const response = await fetch(path, { method, headers: {
        ...headers, ...(method === "POST" ? { "Idempotency-Key": requestKey.current } : {}),
      } });
      if (response.status === 402) throw new PurchaseRequired();
      if (!response.ok) throw new Error("Analysis unavailable");
      return response.json();
    }
    async function run() {
      setError(false); setWaiting(false); setPurchaseRequired(false); setStage(0); setReport(undefined);
      try {
        const response = await fetch(`${base}/dealer-offers/latest`, { headers });
        if (response.status === 404 && offerVersion === undefined) { if (!cancelled) setWaiting(true); return; }
        if (!response.ok) throw new Error("Offer unavailable");
        const offer = await response.json();
        if (cancelled) return;
        if (offer.status !== "VERIFIED") { if (!cancelled) setWaiting(true); return; }
        // The existing endpoints use the latest frozen offer. Never silently analyze a different version.
        if (offerVersion !== undefined && offer.version !== offerVersion) throw new Error("Offer changed");
        if (requestedOffer.current !== undefined && requestedOffer.current !== offer.version) requestKey.current = crypto.randomUUID();
        if (cancelled) return;
        if (!forceRun.current) {
          const existing = await fetch(`${base}/reports/latest`, { headers });
          // Checked before any analysis work, so an unpurchased case stops here.
          if (existing.status === 402) throw new PurchaseRequired();
          const saved = existing.ok ? await existing.json() : undefined;
          if (cancelled) return;
          if (offer.id && saved?.evidence?.dealerOffer?.id === offer.id && saved.analysis?.materialAmounts) {
            setStage(4); setReport(saved); return;
          }
        }
        forceRun.current = false;
        requestedOffer.current = offer.version;
        setReport(undefined); setStage(1);
        await request(`${base}/market-assessments`, "POST");
        if (cancelled) return;
        setStage(2);
        const buyingCase = await request(base);
        if (cancelled) return;
        setFinanceCase(buyingCase.purchaseMethod === "FINANCE");
        if (buyingCase.purchaseMethod !== "FINANCE") await request(`${base}/cash-economics`, "POST");
        if (cancelled) return;
        setStage(3);
        let { operation } = await request(`${base}/analyses`, "POST");
        while (operation.stage !== "COMPLETE" && operation.stage !== "FAILED") {
          await new Promise(resolve => window.setTimeout(resolve, pollDelayMs));
          if (cancelled) return;
          operation = await request(`/api/v1/operations/${encodeURIComponent(operation.id)}`);
        }
        if (cancelled) return;
        if (operation.stage === "FAILED") { requestKey.current = crypto.randomUUID(); throw new Error("Analysis failed"); }
        if (cancelled) return;
        setStage(4);
        const payload = await request(`${base}/reports/latest`);
        if (!payload.analysis?.materialAmounts) throw new Error("Report unavailable");
        if (!cancelled) setReport(payload);
      } catch (caught) {
        if (cancelled) return;
        if (caught instanceof PurchaseRequired) setPurchaseRequired(true); else setError(true);
      }
    }
    void run();
    return () => { cancelled = true; };
  }, [caseId, token, offerVersion, pollDelayMs, attempt]);
  const labels = financeCase ? stages.map((label, index) => index === 2 ? "Cash economics do not apply to dealer financing" : label) : stages;
  return <section className="stack verified-analysis" aria-label="Verified offer analysis">
    {!report && <DealerFinancingForm caseId={caseId} token={token} />}
    {!report && <OutsideFinancing caseId={caseId} token={token} />}
    {!report && purchaseRequired && <PurchasePoint caseId={caseId} token={token} onPurchased={() => setAttempt(value => value + 1)} />}
    {!report && !purchaseRequired && <div className="panel">
      <h2>Your offer analysis</h2>
      {waiting ? <p>Your report appears here once you check the dealer's offer against the paperwork in this step.</p> : <>
        <ol aria-label="Analysis stages">{labels.map((label, index) => <li key={label} aria-current={index === stage ? "step" : undefined}>{label}{index < stage ? " — complete" : ""}</li>)}</ol>
        {!error && <p role="status">{labels[stage]}…</p>}
      </>}
      {error && <><p role="alert">Analysis could not finish. Your accepted documents and verified offer are saved. Retry analysis; you do not need to upload them again. If access has expired, use your saved link or email recovery.</p><button onClick={() => setAttempt(value => value + 1)}>Retry analysis</button></>}
    </div>}
    {report && <><CoreDealReport report={report} caseId={caseId} token={token}>
      <ReportSection id="core-feedback" title="Optional outcome feedback"><OutcomeFeedback caseId={caseId} token={token} headless /></ReportSection>
      <ReportSection id="core-downloads" title="Save a copy"><CaseDownloads caseId={caseId} token={token} reportVersion={report.version} headless /></ReportSection>
    </CoreDealReport><DealerFinancingForm caseId={caseId} token={token} /><OutsideFinancing caseId={caseId} token={token} /><button className="btn-secondary" onClick={() => { requestKey.current = crypto.randomUUID(); forceRun.current = true; setAttempt(value => value + 1); }}>Update analysis with saved terms</button><NegotiationLoader caseId={caseId} token={token} reportVersion={report.version} /></>}
  </section>;
}
