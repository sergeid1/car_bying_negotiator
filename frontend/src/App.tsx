import { FormEvent, useEffect, useMemo, useState } from "react";
import "./styles.css";
import "./Masthead.css";
import { CaseLinkAccess, RecoveryAccess, RecoveryEmailOptIn, CaseDownloads, SavedCaseAccess } from "./CasePortability";
import { CaseLifecycle } from "./CaseLifecycle";
import { AccountControls, AccountHeader, AccountSignedInContext, BuyerAccount, isAccountPass } from "./BuyerAccount";
import { BuyingJourneys } from "./BuyingJourneys";
import { BuyingCaseIntake } from "./BuyingCaseIntake";
import { type FreshDeskCase } from "./DeskCheck";
import { CaseFlow } from "./CaseFlow";
import { AtTheDealer, DealerCameraEntry } from "./AtTheDealer";
import { ThemeMenu } from "./ThemeMenu";
import { OfferTerms } from "./OneTimeOffer";
import { ExampleReportPage, requestedExampleReport } from "./SampleReport";
import { ExplainerGuide, GuideLinks, requestedGuide, requestedGuideIndex } from "./ExplainerGuides";
import { partnerKitLink, ReferralPartnerKit, requestedPartnerKit } from "./ReferralPartnerKit";
import { HowItWorks, requestedHowItWorks } from "./HowItWorks";
import { HomeHero } from "./HomeHero";
import { SiteNav } from "./SiteNav";
import logo from "./assets/otdog-logo.png";
import logoDark from "./assets/otdog-logo-dark.png";
import { SidePanel, type SidePanelView, useSidePanelRequests } from "./SidePanel";

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
  const [recoveryCaseId, setRecoveryCaseId] = useState<string>();
  const [caseRevoked, setCaseRevoked] = useState(false);
  const [stage, setStage] = useState<string>();
  const [report, setReport] = useState<Report>();
  const [error, setError] = useState<string>();
  const [caseCredentials, setCaseCredentials] = useState<{ caseId: string; token: string }>();
  const [referral, setReferral] = useState<{ caseId: string; text: string }>();
  // A case started at the desk for another car, remembered so it is shown apart from the agreed one.
  const [deskFresh, setDeskFresh] = useState<FreshDeskCase>();
  // Photos taken from the header's "I'm at the dealer"; while set, that flow leads the page.
  const [atDealer, setAtDealer] = useState<{ files: File[]; started: number }>();
  // Counts hand-overs from that flow; each one opens the case's At the dealer step.
  const [dealerRequest, setDealerRequest] = useState(0);
  const [accountSignedIn, setAccountSignedIn] = useState(false);
  const [accountControls, setAccountControls] = useState<AccountControls>();
  const [accountRecheck, setAccountRecheck] = useState(0);
  const [signInMode, setSignInMode] = useState<"login" | "signup">("login");
  const [accountNotice, setAccountNotice] = useState("");
  // A sign-in or recovery link, or a return from a mailbox provider's consent page, opens the panel that finishes it.
  const [sidePanel, setSidePanel] = useState<SidePanelView | undefined>(() => {
    const query = new URLSearchParams(window.location.search);
    return window.location.hash.startsWith("#sign-in=") || window.location.hash.startsWith("#social-sign-in") || window.location.hash.startsWith("#recover=")
      || (query.has("state") && (query.has("code") || query.has("error"))) ? "account" : undefined;
  });
  // Opens the sign-in panel's private case link disclosure, such as after a case link fails to open.
  const [caseLinkExpand, setCaseLinkExpand] = useState(0);
  function openPanel(view: SidePanelView) { setSignInMode("login"); setAccountNotice(""); setSidePanel(view); }
  useSidePanelRequests(openPanel);
  const closeSidePanel = () => setSidePanel(undefined);
  // The header's tabs change the address without reloading, so the page is read again on every history move.
  const [address, setAddress] = useState(0);
  useEffect(() => {
    const moved = () => setAddress(value => value + 1);
    window.addEventListener("popstate", moved);
    return () => window.removeEventListener("popstate", moved);
  }, []);
  const { guide, guideIndex, partnerKit, howItWorks, exampleReport } = useMemo(() => {
    const guide = requestedGuide();
    return { guide, guideIndex: !guide && requestedGuideIndex(), partnerKit: requestedPartnerKit(),
      howItWorks: requestedHowItWorks(), exampleReport: !guide && requestedExampleReport() };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [address]);
  // A guide, the partner kit or the walkthrough replaces the start screen at its own shareable address.
  const standalone = Boolean(guide || guideIndex || partnerKit || howItWorks || exampleReport);
  // A case opened from the Buyer Account closes when the sign-in ends; its private case link is unaffected.
  const openedFromAccount = Boolean(caseCredentials && isAccountPass(caseCredentials.token));
  useEffect(() => {
    if (!accountSignedIn && openedFromAccount) {
      setCaseCredentials(undefined); setReport(undefined); setStage(undefined); setError(undefined);
    }
  }, [accountSignedIn, openedFromAccount]);

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
  const caseOpen = Boolean(caseCredentials && !caseRevoked);

  return (
    <>
      <div className="page" inert={sidePanel ? true : undefined}>
      <a className="skip-link" href="#case-workflow" onClick={event => {
        event.preventDefault(); document.getElementById("case-workflow")?.focus();
      }}>Skip to case workflow</a>

      <header className="masthead">
        <div className="masthead-inner">
          <a className="wordmark" href="/" aria-label="otdog home">
            <img className="wordmark-light" src={logo} alt="" width="114" height="40" />
            <img className="wordmark-dark" src={logoDark} alt="" width="114" height="40" />
          </a>
          {!caseOpen && !partnerKit && <SiteNav page={howItWorks ? "how-it-works" : exampleReport ? "example-report" : guide || guideIndex ? "guides" : standalone ? "other" : "home"} />}
          {!partnerKit && <DealerCameraEntry onPhotos={files => setAtDealer({ files, started: Date.now() })} />}
          <ThemeMenu />
          {!partnerKit && <AccountHeader controls={accountControls} onOpen={() => openPanel("account")} />}
        </div>
      </header>

      <main>
        {accountNotice && <p role="status">{accountNotice}</p>}
        {!standalone && !caseOpen && <HomeHero />}

        <div className="workspace">
          <AccountSignedInContext.Provider value={accountSignedIn}>
          <div className="column" id="case-workflow" tabIndex={-1}>
            {guide && !atDealer && !caseOpen && <ExplainerGuide slug={guide} />}
            {guideIndex && !atDealer && !caseOpen && <GuideLinks />}
            {exampleReport && !atDealer && !caseOpen && <ExampleReportPage />}
            {!partnerKit && atDealer && <AtTheDealer key={atDealer.started} files={atDealer.files}
              open={caseCredentials && !caseRevoked ? caseCredentials : undefined}
              onCaseOpened={value => {
                setReferral(undefined); setCaseRevoked(false); setCaseCredentials(value); setRecoveryCaseId(value.caseId);
                setReport(undefined); setStage(undefined); setError(undefined);
              }}
              onFreshCase={value => {
                setDeskFresh(value); setCaseRevoked(false); setCaseCredentials({ caseId: value.caseId, token: value.token }); setRecoveryCaseId(value.caseId);
              }}
              onDeskCheck={() => { setAtDealer(undefined); setDealerRequest(value => value + 1); }} onClose={() => setAtDealer(undefined)} />}
            {!guide && partnerKit && <ReferralPartnerKit />}
            {!guide && !partnerKit && howItWorks && !atDealer && !caseOpen && <HowItWorks />}
            {!standalone && accountSignedIn && <BuyingJourneys
              onSessionEnded={() => { setAccountSignedIn(false); setAccountRecheck(value => value + 1); }}
              onCaseOpened={(value, disclosure) => {
                setReferral(disclosure ? { caseId: value.caseId, text: disclosure } : undefined);
                setCaseRevoked(false); setCaseCredentials(value); setRecoveryCaseId(value.caseId);
              }} />}
            {!standalone && !report && !caseCredentials && !atDealer && (
              <BuyingCaseIntake
                onSavedToAccount={() => setAccountRecheck(value => value + 1)}
                onCaseOpened={(value, disclosure) => {
                  setReferral(disclosure ? { caseId: value.caseId, text: disclosure } : undefined);
                  setCaseRevoked(false);
                  setCaseCredentials(value);
                  setRecoveryCaseId(value.caseId);
                }}
              />
            )}

            {!standalone && !report && !caseCredentials && !atDealer && <OfferTerms />}

            {!standalone && !report && !caseOpen && import.meta.env.DEV && new URLSearchParams(window.location.search).has("fixture") && (
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

            {caseCredentials && !caseRevoked && <CaseFlow key={caseCredentials.caseId} {...caseCredentials} openedFromAccount={openedFromAccount}
              referral={referral?.caseId === caseCredentials.caseId ? referral.text : undefined} deskFresh={deskFresh}
              atDealer={Boolean(atDealer)} dealerRequest={dealerRequest} pollDelayMs={pollDelayMs}
              onFreshCase={value => {
                setDeskFresh(value); setCaseRevoked(false); setCaseCredentials({ caseId: value.caseId, token: value.token }); setRecoveryCaseId(value.caseId);
              }}
              onBackToAgreed={() => {
                if (!deskFresh) return;
                setCaseRevoked(false); setCaseCredentials(deskFresh.agreed); setRecoveryCaseId(deskFresh.agreed.caseId); setDeskFresh(undefined);
              }} />}
          </div>
          </AccountSignedInContext.Provider>
        </div>
      </main>

      <footer className="page-footer">
        <div className="page-footer-inner">
          <section className="limitations" id="scope" aria-label="Commercial relationships and scope">
            <strong>What this is, and what it is not</strong>
            <div className="scope-columns">
              <p>
                otdog is a tool on your side, the Buyer's. We don't work with dealers. We help
                people. It helps you find cars, check quotes, draft your emails to dealers, and check
                desk paperwork. You send every email from your own email and make every decision, and
                otdog never contacts a dealer itself.
              </p>
              <p>
                No dealer, lender, or other commercial party pays for placement, leads, or referrals,
                and no commercial relationship affects any market range, rating, finding, or suggested
                boundary.
              </p>
              <p>
                It reviews the dealer's own paperwork for financial consistency. It is not legal, tax,
                or mechanical advice, it does not establish that a document is safe to sign, and it
                does not guarantee savings or any negotiating outcome.
              </p>
            </div>
            <p><a href={partnerKitLink}>Referral partner kit</a></p>
          </section>
        </div>
      </footer>
      </div>

      <SidePanel id="case-panel" title="This case" open={sidePanel === "case" && Boolean(caseCredentials)} onClose={closeSidePanel}>
        {caseCredentials && !caseRevoked && <RecoveryEmailOptIn key={`email-${caseCredentials.caseId}-${caseCredentials.token}`} {...caseCredentials} />}
        {caseCredentials && !caseRevoked && <CaseDownloads key={`export-${caseCredentials.caseId}-${caseCredentials.token}`} {...caseCredentials} />}
        {caseCredentials && <CaseLifecycle key={`lifecycle-${caseCredentials.caseId}-${caseCredentials.token}`} {...caseCredentials}
          onRevoked={() => { setCaseRevoked(true); setReport(undefined); setStage(undefined); setError(undefined); }} />}
      </SidePanel>
      {!partnerKit && <SidePanel id="account-panel" title={accountSignedIn ? "Your account" : signInMode === "login" ? "Log in" : "Sign up"}
        centered={!accountSignedIn}
        open={sidePanel === "account"} onClose={closeSidePanel}>
        <BuyerAccount caseCredentials={caseCredentials && !caseRevoked ? caseCredentials : undefined}
          signInMode={signInMode} onSignInMode={setSignInMode}
          onSignedIn={setAccountSignedIn} onControls={setAccountControls} recheck={accountRecheck} showing={sidePanel === "account"}
          onSignInComplete={notice => { if (notice) setAccountNotice(notice); closeSidePanel(); }}
          onCaseOpened={value => {
            closeSidePanel();
            setReferral(undefined); setDeskFresh(undefined); setCaseRevoked(false); setCaseCredentials(value);
            setRecoveryCaseId(value.caseId); setReport(undefined); setStage(undefined); setError(undefined);
          }} />
        <div className="account-access-footer" hidden={!accountSignedIn && signInMode === "signup"}>
        <CaseLinkAccess expand={caseLinkExpand}>
          <SavedCaseAccess onOpened={value => { closeSidePanel(); setCaseRevoked(false); setCaseCredentials(value); setRecoveryCaseId(value.caseId); setReport(undefined); setStage(undefined); setError(undefined); }}
            onUnavailable={caseId => { setSidePanel("account"); setCaseLinkExpand(value => value + 1); setCaseCredentials(undefined); setRecoveryCaseId(caseId); setReport(undefined); setStage(undefined); setError(undefined); }} />
          <RecoveryAccess caseId={recoveryCaseId ?? caseCredentials?.caseId} onRecovered={value => { closeSidePanel(); setCaseRevoked(false); setCaseCredentials(value); setRecoveryCaseId(value.caseId); setReport(undefined); setStage(undefined); setError(undefined); }} />
        </CaseLinkAccess>
        </div>
      </SidePanel>}
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
