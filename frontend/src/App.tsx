import { FormEvent, useState } from "react";
import "./styles.css";
import { RecoveryAccess, RecoveryEmailOptIn, CaseDownloads } from "./CasePortability";
import { CaseLifecycle } from "./CaseLifecycle";
import { PricePlan } from "./PricePlan";
import { CoreReportLoader } from "./CoreDealReport";
import { BuyingCaseIntake } from "./BuyingCaseIntake";
import { DealerDocumentUpload } from "./DealerDocumentUpload";
import { OfferVerification } from "./OfferVerification";

type MoneyAmount = {
  minorUnits: number;
  currency: string;
  precision: "EXACT" | "ESTIMATED" | "UNAVAILABLE";
};

type Report = {
  schemaVersion: string;
  version: number;
  analysis: {
    reviewStatus: string;
    dealRating: string;
    topReasons: string[];
    nextAction: string;
    economics: {
      sellingPrice: MoneyAmount;
      dealerFees: MoneyAmount;
      addOns: MoneyAmount;
      taxes: MoneyAmount;
      governmentCharges: MoneyAmount;
      outTheDoorTotal: MoneyAmount;
    };
    marketAssessment: {
      low: MoneyAmount;
      high: MoneyAmount;
      confidence: string;
    };
    limitations: string[];
  };
};

type StartedAnalysis = {
  case: { id: string; status: string };
  operation: { id: string; stage: string };
  accessToken: string;
};

type Operation = { id: string; caseId: string; stage: string; error?: string };

const fixture = {
  buyerZip: "27601",
  dealerZip: "27604",
  vehicle: {
    vin: "1HGCM82633A004352",
    year: 2021,
    make: "Honda",
    model: "Accord",
    trim: "EX-L",
    mileage: 38000,
  },
  dealerOffer: {
    currency: "USD",
    sellingPriceMinor: 2500000,
    dealerFeesMinor: 79900,
    addOnsMinor: 50000,
    taxesMinor: 194600,
    governmentChargesMinor: 23000,
  },
};

const stageLabels: Record<string, string> = {
  QUEUED: "Queued",
  LOADING_INPUTS: "Loading recorded inputs",
  FETCHING_MARKET: "Reading market evidence",
  CALCULATING: "Calculating cash economics",
  ASSEMBLING_REPORT: "Assembling immutable report",
  COMPLETE: "Complete",
  FAILED: "Failed",
};

/** Ordered so the progress meter can show how far a running analysis has come. */
const stageOrder = ["QUEUED", "LOADING_INPUTS", "FETCHING_MARKET", "CALCULATING", "ASSEMBLING_REPORT", "COMPLETE"];

async function requestJson<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(path, options);
  if (!response.ok) {
    throw new Error(`Request failed with status ${response.status}`);
  }
  return response.json() as Promise<T>;
}

function formatMoney(amount: MoneyAmount) {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: amount.currency,
  }).format(amount.minorUnits / 100);
}

function humanize(value: string) {
  const words = value.toLowerCase().replaceAll("_", " ");
  return words[0].toUpperCase() + words.slice(1);
}

export default function App({ pollDelayMs = 250 }: { pollDelayMs?: number }) {
  const [caseRevoked, setCaseRevoked] = useState(false);
  const [stage, setStage] = useState<string>();
  const [report, setReport] = useState<Report>();
  const [error, setError] = useState<string>();
  const [caseCredentials, setCaseCredentials] = useState<{ caseId: string; token: string }>();
  const [frozenOfferVersion, setFrozenOfferVersion] = useState<number>();

  async function startAnalysis(event: FormEvent) {
    event.preventDefault();
    setError(undefined);
    setReport(undefined);
    try {
      const started = await requestJson<StartedAnalysis>("/api/v1/cases/synthetic", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Idempotency-Key": crypto.randomUUID(),
        },
        body: JSON.stringify(fixture),
      });
      setStage(started.operation.stage);

      let operation: Operation;
      do {
        if (pollDelayMs > 0) {
          await new Promise((resolve) => window.setTimeout(resolve, pollDelayMs));
        }
        operation = await requestJson<Operation>(
          `/api/v1/operations/${started.operation.id}`,
          { headers: { "Case-Access-Token": started.accessToken } },
        );
        setStage(operation.stage);
      } while (operation.stage !== "COMPLETE" && operation.stage !== "FAILED");

      if (operation.stage === "FAILED") {
        throw new Error(operation.error ?? "Analysis could not be completed");
      }
      setReport(
        await requestJson<Report>(
          `/api/v1/cases/${started.case.id}/reports/latest`,
          { headers: { "Case-Access-Token": started.accessToken } },
        ),
      );
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Analysis could not be completed");
    }
  }

  const running = Boolean(stage && stage !== "COMPLETE");

  return (
    <>
      <a className="skip-link" href="#case-workflow" onClick={event => {
        event.preventDefault(); document.getElementById("case-workflow")?.focus();
      }}>Skip to case workflow</a>

      <header className="masthead">
        <div className="masthead-inner">
          <span className="wordmark"><span className="mark" aria-hidden="true" />Deal X-Ray</span>
          <nav aria-label="Page sections">
            <a href="#case-workflow">Your case</a>
            <a href="#scope">Scope</a>
          </nav>
        </div>
      </header>

      <main>
        <div className="hero">
          <p className="eyebrow">Buyer-aligned deal analysis</p>
          <h1>See the whole deal before you decide.</h1>
          <p className="lede">
            Enter the vehicle you are negotiating for and get a transparent Deal X-Ray. Every
            dollar stays connected to its input or calculation.
          </p>
          <ul className="trust-strip">
            <li>Every amount linked to its source</li>
            <li>Deterministic, versioned math</li>
            <li>No account required</li>
            <li>Ephemeral by default</li>
          </ul>
        </div>

        <div className="workspace">
          <div className="column">
            {!report && !caseCredentials && (
              <BuyingCaseIntake
                onCaseOpened={value => {
                  setCaseRevoked(false);
                  setCaseCredentials(value);
                }}
              />
            )}

            {!report && import.meta.env.DEV && (
              <section className="panel" aria-labelledby="case-heading">
                <div className="section-heading">
                  <div>
                    <p className="step">Development fixture</p>
                    <h2 id="case-heading">2021 Honda Accord EX-L</h2>
                  </div>
                  <span className="cash-badge">Cash purchase</span>
                </div>

                <dl className="facts">
                  <div><dt>VIN</dt><dd>1HGCM82633A004352</dd></div>
                  <div><dt>Mileage</dt><dd>38,000 mi</dd></div>
                  <div><dt>Buyer ZIP</dt><dd>27601</dd></div>
                  <div><dt>Dealer ZIP</dt><dd>27604</dd></div>
                  <div><dt>Selling price</dt><dd>$25,000.00</dd></div>
                  <div><dt>Quoted components</dt><dd>Fees, add-ons, taxes, government charges</dd></div>
                </dl>

                <form onSubmit={startAnalysis}>
                  <button type="submit" disabled={running}>
                    {running ? "Analyzing…" : "Analyze synthetic deal"}
                  </button>
                </form>

                {running && (
                  <>
                    <p className="operation" role="status" aria-live="polite">
                      <span className="spinner" aria-hidden="true" />
                      {stageLabels[stage!] ?? stage}
                    </p>
                    <ol className="stage-track" aria-hidden="true">
                      {stageOrder.slice(0, -1).map((name, index) => (
                        <li key={name} data-done={index <= stageOrder.indexOf(stage!)} />
                      ))}
                    </ol>
                  </>
                )}
                {error && <p className="error" role="alert">{error}</p>}
              </section>
            )}

            {report && <DealReport report={report} />}

            {caseCredentials && !caseRevoked && <DealerDocumentUpload key={`documents-${caseCredentials.caseId}-${caseCredentials.token}`} {...caseCredentials} />}
            {caseCredentials && !caseRevoked && <OfferVerification key={`verify-${caseCredentials.caseId}-${caseCredentials.token}`} {...caseCredentials}
              onOfferFrozen={offer => setFrozenOfferVersion(offer.version)} />}
            {caseCredentials && !caseRevoked && <PricePlan key={caseCredentials.caseId + caseCredentials.token} {...caseCredentials} />}
            {caseCredentials && !caseRevoked && <CoreReportLoader key={caseCredentials.caseId + caseCredentials.token} {...caseCredentials} />}
          </div>

          <aside className="rail" aria-label="Case access and retention">
            <p className="rail-title">Your Buying Case</p>

            <section id="case-workflow" tabIndex={-1} className="panel" aria-label="Open an existing Buying Case">
              <h2>Open a saved case</h2>
              <p className="muted">Enter the Case ID and access token you saved when the case was created.</p>
              <form onSubmit={event => {
                event.preventDefault();
                const values = new FormData(event.currentTarget);
                setCaseRevoked(false);
                setCaseCredentials({ caseId: String(values.get("caseId")), token: String(values.get("token")) });
              }}>
                <label>Buying Case ID<input name="caseId" required /></label>
                <label>Case access token<input name="token" type="password" required autoComplete="off" /></label>
                <button>Open price plan</button>
              </form>
            </section>

            <RecoveryAccess onRecovered={value => { setCaseRevoked(false); setCaseCredentials(value); }} />
            {caseCredentials && !caseRevoked && <RecoveryEmailOptIn key={`email-${caseCredentials.caseId}-${caseCredentials.token}`} {...caseCredentials} />}
            {caseCredentials && !caseRevoked && <CaseDownloads key={`export-${caseCredentials.caseId}-${caseCredentials.token}`} {...caseCredentials} />}
            {caseCredentials && <CaseLifecycle key={`lifecycle-${caseCredentials.caseId}-${caseCredentials.token}`} {...caseCredentials}
              onRevoked={() => { setCaseRevoked(true); setReport(undefined); setStage(undefined); setError(undefined); }} />}
          </aside>
        </div>
      </main>

      <footer className="page-footer">
        <div className="page-footer-inner">
          <section className="limitations" id="scope" aria-label="Commercial relationships and scope">
            <strong>What this is, and what it is not</strong>
            <div className="scope-columns">
              <p>
                This product represents you, the Buyer. No dealer, lender, or other commercial party
                pays for placement, leads, or referrals, and no commercial relationship affects any
                market range, rating, finding, or suggested boundary.
              </p>
              <p>
                It reviews the dealer's own paperwork for financial consistency. It is not legal, tax,
                or mechanical advice, it does not establish that a document is safe to sign, and it
                does not guarantee savings or any negotiating outcome.
              </p>
            </div>
          </section>
        </div>
      </footer>
    </>
  );
}

function DealReport({ report }: { report: Report }) {
  const { analysis } = report;
  const economics = analysis.economics;
  const market = analysis.marketAssessment;
  const span = market.high.minorUnits - market.low.minorUnits;
  const position = span > 0
    ? Math.min(1, Math.max(0, (economics.sellingPrice.minorUnits - market.low.minorUnits) / span))
    : null;
  return (
    <article className="report" aria-labelledby="report-heading">
      <div className="report-meta">
        <span>Report v{report.version}</span>
        <span>{report.schemaVersion}</span>
      </div>
      <section className="outcome">
        <p className="eyebrow">Deal Analysis Report</p>
        <h2 id="report-heading">Deal Analysis Report</h2>
        <div className="status-row">
          <strong className="warning">{analysis.reviewStatus === "MATCHES_APPROVED_BASELINE" ? "Matches approved deal" : humanize(analysis.reviewStatus)}</strong>
          <span>{humanize(analysis.dealRating)}</span>
        </div>
        <p className="next-action">{analysis.nextAction}</p>
      </section>

      <section className="report-grid" aria-label="Deal totals">
        <div className="total-card">
          <span>Out-the-door total</span>
          <strong>{formatMoney(economics.outTheDoorTotal)}</strong>
          <small>{humanize(economics.outTheDoorTotal.precision)} calculation</small>
        </div>
        <div className="market-card">
          <span>Synthetic market range</span>
          <strong>
            {formatMoney(market.low)}–
            {formatMoney(market.high)}
          </strong>
          {position !== null && (
            <div className="range" aria-hidden="true">
              <span className="range-marker" style={{ left: `${position * 100}%` }} />
            </div>
          )}
          <small>{humanize(market.confidence)} confidence · estimated</small>
        </div>
      </section>

      <section className="panel detail">
        <h3>What makes up the total</h3>
        <dl className="money-list">
          <MoneyRow label="Selling price" amount={economics.sellingPrice} />
          <MoneyRow label="Dealer fees" amount={economics.dealerFees} />
          <MoneyRow label="Add-ons" amount={economics.addOns} />
          <MoneyRow label="Taxes" amount={economics.taxes} />
          <MoneyRow label="Government charges" amount={economics.governmentCharges} />
        </dl>
      </section>

      <section className="panel detail">
        <h3>Why this needs review</h3>
        <ol>{analysis.topReasons.map((reason) => <li key={reason}>{reason}</li>)}</ol>
      </section>

      <aside className="limitations" aria-label="Report limitations">
        <strong>Scope and limitations</strong>
        {analysis.limitations.map((limitation) => <p key={limitation}>{limitation}</p>)}
      </aside>
    </article>
  );
}

function MoneyRow({ label, amount }: { label: string; amount: MoneyAmount }) {
  return (
    <div>
      <dt>{label}<small>{humanize(amount.precision)}</small></dt>
      <dd>{formatMoney(amount)}</dd>
    </div>
  );
}
