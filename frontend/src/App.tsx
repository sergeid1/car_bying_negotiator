import { FormEvent, useState } from "react";
import "./styles.css";
import { RecoveryAccess, RecoveryEmailOptIn, CaseDownloads } from "./CasePortability";
import { CaseLifecycle } from "./CaseLifecycle";
import { PricePlan } from "./PricePlan";
import { CoreReportLoader } from "./CoreDealReport";

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

  return (
    <main>
      <header className="hero">
        <p className="eyebrow">Buyer-aligned deal analysis</p>
        <h1>See the whole deal before you decide.</h1>
        <p className="lede">
          Run a transparent Deal X-Ray on one synthetic cash offer. Every dollar stays
          connected to its input or calculation.
        </p>
      </header>

      <RecoveryAccess onRecovered={value => { setCaseRevoked(false); setCaseCredentials(value); }} />
      <section className="panel" aria-label="Open an existing Buying Case">
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
      {caseCredentials && <CaseLifecycle key={`lifecycle-${caseCredentials.caseId}-${caseCredentials.token}`} {...caseCredentials}
        onRevoked={() => { setCaseRevoked(true); setReport(undefined); setStage(undefined); setError(undefined); }} />}
      {caseCredentials && !caseRevoked && <RecoveryEmailOptIn key={`email-${caseCredentials.caseId}-${caseCredentials.token}`} {...caseCredentials} />}
      {caseCredentials && !caseRevoked && <CaseDownloads key={`export-${caseCredentials.caseId}-${caseCredentials.token}`} {...caseCredentials} />}
      {caseCredentials && !caseRevoked && <PricePlan key={caseCredentials.caseId + caseCredentials.token} {...caseCredentials} />}
      {caseCredentials && !caseRevoked && <CoreReportLoader key={caseCredentials.caseId + caseCredentials.token} {...caseCredentials} />}

      {!report && (
        <section className="panel" aria-labelledby="case-heading">
          <div className="section-heading">
            <div>
              <p className="step">Synthetic fixture</p>
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
            <button type="submit" disabled={Boolean(stage && stage !== "COMPLETE")}>
              {stage && stage !== "COMPLETE" ? "Analyzing…" : "Analyze synthetic deal"}
            </button>
          </form>

          {stage && stage !== "COMPLETE" && (
            <p className="operation" role="status" aria-live="polite">
              <span className="spinner" aria-hidden="true" />
              {stageLabels[stage] ?? stage}
            </p>
          )}
          {error && <p className="error" role="alert">{error}</p>}
        </section>
      )}

      {report && <DealReport report={report} />}
    </main>
  );
}

function DealReport({ report }: { report: Report }) {
  const { analysis } = report;
  const economics = analysis.economics;
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
            {formatMoney(analysis.marketAssessment.low)}–
            {formatMoney(analysis.marketAssessment.high)}
          </strong>
          <small>{humanize(analysis.marketAssessment.confidence)} confidence · estimated</small>
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
