import { useEffect, useRef, useState } from "react";
import { CoreDealReport, type CoreReport } from "./CoreDealReport";
import { CaseDownloads } from "./CasePortability";

import { DealerFinancingForm } from "./DealerFinancing";
import { OutsideFinancing } from "./OutsideFinancing";
import { OutcomeFeedback } from "./PilotFeedback";
import { NegotiationLoader } from "./NegotiationDraft";

type Props = { caseId: string; token: string; offerVersion?: number; pollDelayMs?: number };
const stages = ["Finding your verified offer", "Reading market evidence", "Calculating cash economics", "Preparing your report", "Opening your report"];

export function VerifiedOfferAnalysis({ caseId, token, offerVersion, pollDelayMs = 250 }: Props) {
  const [financeCase, setFinanceCase] = useState(false);
  const [stage, setStage] = useState(0);
  const [report, setReport] = useState<CoreReport>();
  const [error, setError] = useState(false);
  const [waiting, setWaiting] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const requestKey = useRef(crypto.randomUUID());
  const requestedOffer = useRef<number | undefined>(undefined);
  useEffect(() => {
    let cancelled = false;
    const base = `/api/v1/cases/${encodeURIComponent(caseId)}`;
    const headers = { "Case-Access-Token": token };
    async function request(path: string, method = "GET") {
      const response = await fetch(path, { method, headers: {
        ...headers, ...(method === "POST" ? { "Idempotency-Key": requestKey.current } : {}),
      } });
      if (!response.ok) throw new Error("Analysis unavailable");
      return response.json();
    }
    async function run() {
      setError(false); setWaiting(false); setStage(0); setReport(undefined);
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
      } catch { if (!cancelled) setError(true); }
    }
    void run();
    return () => { cancelled = true; };
  }, [caseId, token, offerVersion, pollDelayMs, attempt]);
  const labels = financeCase ? stages.map((label, index) => index === 2 ? "Cash economics do not apply to dealer financing" : label) : stages;
  return <section className="stack verified-analysis" aria-label="Verified offer analysis">
    <DealerFinancingForm caseId={caseId} token={token} />
    <OutsideFinancing caseId={caseId} token={token} />
    {!report && <div className="panel">
      <h2>Your offer analysis</h2>
      {waiting ? <p>Verify and freeze your dealer offer to continue to the report.</p> : <>
        <ol aria-label="Analysis stages">{labels.map((label, index) => <li key={label} aria-current={index === stage ? "step" : undefined}>{label}{index < stage ? " — complete" : ""}</li>)}</ol>
        {!error && <p role="status">{labels[stage]}…</p>}
      </>}
      {error && <><p role="alert">Analysis could not finish. Your accepted documents and verified offer are saved. Retry analysis; you do not need to upload them again. If access has expired, use your saved link or email recovery.</p><button onClick={() => setAttempt(value => value + 1)}>Retry analysis</button></>}
    </div>}
    {report && <><button className="btn-secondary" onClick={() => { requestKey.current = crypto.randomUUID(); setAttempt(value => value + 1); }}>Update analysis with saved terms</button><CoreDealReport report={report} /><OutcomeFeedback caseId={caseId} token={token} /><CaseDownloads caseId={caseId} token={token} reportVersion={report.version} /><NegotiationLoader caseId={caseId} token={token} reportVersion={report.version} /></>}
  </section>;
}
