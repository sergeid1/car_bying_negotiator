import { FormEvent, useContext, useEffect, useState } from "react";
import { arrivalChannel, forgetArrivalChannel } from "./AcquisitionChannel";
import { openSidePanel } from "./SidePanel";
import { AccountSignedInContext } from "./BuyerAccount";
import { DealerFields } from "./DealerFields";
import * as format from "./i18n/format";
import { useMessages } from "./i18n/LanguageProvider";

type CaseCredentials = { caseId: string; token: string };

type IntakeOptions = { supported: string[]; consentPolicies: Record<string, string> };

type IdentityConflict = {
  id: string;
  field: string;
  confirmedValue: string;
  listingValue: string;
  status: string;
};

type VehicleEvidence = {
  identityConflicts: IdentityConflict[];
  reliableForAnalysis: boolean;
  listingSnapshot?: { listingUrl: string; source: string } | null;
};

/** A refusal or correction the Buyer can act on, tied to the field that has to change. */
type Problem = {
  detail: string;
  field?: string;
  reason?: string;
  candidateJurisdictions?: string[];
};

class IntakeProblem extends Error {
  constructor(readonly problem: Problem) {
    super(problem.detail);
  }
}

async function send<T>(path: string, options: RequestInit, failure: string): Promise<T> {
  const response = await fetch(path, options);
  if (response.ok) {
    return (await response.json()) as T;
  }
  let problem: Problem = { detail: failure };
  try {
    const body = await response.json();
    if (body && typeof body.detail === "string") {
      problem = body as Problem;
    }
  } catch {
    // A non-JSON failure carries nothing safe to show, so the generic message stands.
  }
  throw new IntakeProblem(problem);
}

export function BuyingCaseIntake({ onCaseOpened, onSavedToAccount, initial, title }: {
  onCaseOpened: (value: CaseCredentials, referralDisclosure?: string) => void;
  /** Called when the new case was saved to the signed-in Buyer Account. */
  onSavedToAccount?: () => void;
  /** Starting values the Buyer can still change, such as the VIN read from desk paperwork. */
  initial?: { vin?: string; dealerPostalCode?: string; dealerLocation?: string; purchaseMethod?: string };
  title?: string;
}) {
  const { m: { account: { intake: t, stateNames } }, fill, fillNodes } = useMessages();
  const stateName = (jurisdiction: string) => (stateNames as Record<string, string>)[jurisdiction] ?? jurisdiction;
  const [options, setOptions] = useState<IntakeOptions>();
  const [buyerPostalCode, setBuyerPostalCode] = useState("");
  const [identifyBy, setIdentifyBy] = useState<"vin" | "details">("vin");
  const [statedJurisdiction, setStatedJurisdiction] = useState("");
  const [crossingCandidates, setCrossingCandidates] = useState<string[]>();
  const [countyCandidates, setCountyCandidates] = useState<string[]>();
  const [statedCounty, setStatedCounty] = useState("");
  const [problem, setProblem] = useState<Problem>();
  const [submitting, setSubmitting] = useState(false);
  // Kept across a vehicle-evidence failure so a correction never restarts the case.
  const [openCase, setOpenCase] = useState<CaseCredentials>();
  const [conflicts, setConflicts] = useState<IdentityConflict[]>();
  const [referralDisclosure, setReferralDisclosure] = useState<string>();
  // Read when the case starts, so the link parameter leaves the address bar straight away.
  const [channel] = useState(arrivalChannel);
  // Undefined outside the app shell, where there is no account panel to point to.
  const signedIn = useContext(AccountSignedInContext);

  useEffect(() => {
    let cancelled = false;
    fetch("/api/v1/intake-options")
      .then(response => (response.ok ? response.json() : undefined))
      .then(value => {
        if (!cancelled && value) setOptions(value as IntakeOptions);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  const supported = options?.supported ?? [];
  const policies = options?.consentPolicies ?? {};

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const values = new FormData(form);
    setProblem(undefined);
    setSubmitting(true);
    try {
      let credentials = openCase;
      let disclosure = referralDisclosure;
      if (!credentials) {
        const decision = (granted: boolean, policy: string) => ({
          granted,
          policyVersion: policy,
        });
        const created = await send<{
          case: { id: string; referralDisclosure?: string | null };
          accessToken: string;
          savedToAccount?: boolean;
        }>("/api/v1/cases", {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "Idempotency-Key": crypto.randomUUID(),
            // Only a signed-in Buyer who was told below asks for the case to be saved to the account.
            ...(signedIn ? { "Account-Request": "1" } : {}),
          },
          body: JSON.stringify({
            buyerPostalCode: String(values.get("buyerPostalCode") ?? "").trim(),
            dealerCity: String(values.get("dealerCity") ?? "").trim(),
            dealerState: String(values.get("dealerState") ?? ""),
            dealerName: String(values.get("dealerName") ?? "").trim() || undefined,
            buyerJurisdiction: statedJurisdiction || undefined,
            buyerCounty: statedCounty || undefined,
            purchaseMethod: String(values.get("purchaseMethod") ?? "CASH"),
            buyerLimits: values.get("maxOutTheDoorPrice")
              ? {
                  maxOutTheDoorPriceMinor: Math.round(
                    Number(values.get("maxOutTheDoorPrice")) * 100,
                  ),
                }
              : undefined,
            consents: {
              serviceProcessing: decision(true, policies.serviceProcessing ?? "unknown"),
              humanReview: decision(
                values.get("humanReview") === "on",
                policies.humanReview ?? "unknown",
              ),
              anonymizedResearch: decision(
                values.get("anonymizedResearch") === "on",
                policies.anonymizedResearch ?? "unknown",
              ),
            },
            channel,
          }),
        }, t.requestFailed);
        forgetArrivalChannel();
        if (created.savedToAccount) onSavedToAccount?.();
        credentials = { caseId: created.case.id, token: created.accessToken };
        disclosure = created.case.referralDisclosure ?? undefined;
        setReferralDisclosure(disclosure);
        setOpenCase(credentials);
        setCrossingCandidates(undefined);
      }

      const listingUrl = String(values.get("listingUrl") ?? "").trim();
      const evidence = await send<VehicleEvidence>(
        `/api/v1/cases/${encodeURIComponent(credentials.caseId)}/vehicle-evidence`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json", "Case-Access-Token": credentials.token },
          body: JSON.stringify({
            vin: identifyBy === "vin" ? String(values.get("vin") ?? "").trim() : null,
            manualFacts:
              identifyBy === "details"
                ? {
                    year: Number(values.get("year")),
                    make: String(values.get("make") ?? "").trim(),
                    model: String(values.get("model") ?? "").trim(),
                    trim: String(values.get("trim") ?? "").trim(),
                    mileage: Number(values.get("mileage")),
                  }
                : null,
            listingUrl: listingUrl || null,
            confirmed: true,
          }),
        },
        t.requestFailed,
      );

      const unresolved = evidence.identityConflicts.filter(item => item.status === "UNRESOLVED");
      if (unresolved.length > 0) {
        setConflicts(unresolved);
        return;
      }
      if (disclosure) onCaseOpened(credentials, disclosure);
      else onCaseOpened(credentials);
    } catch (caught) {
      if (caught instanceof IntakeProblem) {
        setProblem(caught.problem);
        if (caught.problem.reason === "POSTAL_CODE_CROSSES_STATE_LINE") {
          setCrossingCandidates(caught.problem.candidateJurisdictions ?? []);
        }
        if (caught.problem.reason === "POSTAL_CODE_CROSSES_COUNTY_LINE") {
          setCountyCandidates(caught.problem.candidateJurisdictions ?? []);
        }
      } else {
        setProblem({ detail: t.requestFailed });
      }
    } finally {
      setSubmitting(false);
    }
  }

  async function resolveConflict(conflictId: string, resolution: string) {
    if (!openCase) return;
    setProblem(undefined);
    try {
      const evidence = await send<VehicleEvidence>(
        `/api/v1/cases/${encodeURIComponent(openCase.caseId)}/vehicle-evidence/conflicts/${encodeURIComponent(conflictId)}/resolution`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json", "Case-Access-Token": openCase.token },
          body: JSON.stringify({ resolution }),
        },
        t.requestFailed,
      );
      const unresolved = evidence.identityConflicts.filter(item => item.status === "UNRESOLVED");
      setConflicts(unresolved.length > 0 ? unresolved : undefined);
      if (unresolved.length === 0) {
        if (referralDisclosure) onCaseOpened(openCase, referralDisclosure);
        else onCaseOpened(openCase);
      }
    } catch (caught) {
      setProblem(
        caught instanceof IntakeProblem
          ? caught.problem
          : { detail: t.requestFailed },
      );
    }
  }

  const fieldProblem = (field: string) => (problem?.field === field ? problem.detail : undefined);

  return (
    <section className="panel" id="buying-case-intake" aria-labelledby="intake-heading">
      <div className="section-heading">
        <div>
          <p className="step">{t.step}</p>
          <h2 id="intake-heading">{title ?? t.title}</h2>
        </div>
      </div>

      <p className="muted">
        {supported.length > 0
          ? fill(t.supported, { states: supported.map(stateName).join(", ") })
          : t.enterVehicle}
      </p>

      {!openCase && signedIn === true && (
        <p className="account-save-note" role="note">{t.signedInNote}</p>
      )}
      {!openCase && signedIn === false && (
        <p className="account-save-note" role="note">
          {fillNodes(t.signedOutNote, { link: <a href="#account-panel" onClick={event => {
            event.preventDefault(); openSidePanel("account");
          }}>{t.signInLink}</a> })}
        </p>
      )}

      {problem && !problem.field && (
        <p className="error" role="alert">
          {problem.detail}
        </p>
      )}

      {conflicts ? (
        <div>
          <h3>{t.conflictHeading}</h3>
          <p className="muted">{t.conflictIntro}</p>
          {conflicts.map(conflict => (
            <div key={conflict.id} className="status-row">
              <dl className="facts">
                <div>
                  <dt>{t.field}</dt>
                  <dd>{conflict.field}</dd>
                </div>
                <div>
                  <dt>{t.youConfirmed}</dt>
                  <dd>{conflict.confirmedValue}</dd>
                </div>
                <div>
                  <dt>{t.listingSays}</dt>
                  <dd>{conflict.listingValue}</dd>
                </div>
              </dl>
              <div className="actions">
                <button type="button" onClick={() => resolveConflict(conflict.id, "KEEP_CONFIRMED_VEHICLE")}>
                  {t.keepConfirmed}
                </button>
                <button
                  type="button"
                  className="btn-secondary"
                  onClick={() => resolveConflict(conflict.id, "USE_LISTING_VEHICLE")}
                >
                  {t.useListing}
                </button>
              </div>
            </div>
          ))}
        </div>
      ) : (
        <form onSubmit={submit}>
          <label>
            {t.zip}
            <input
              name="buyerPostalCode"
              inputMode="numeric"
              autoComplete="postal-code"
              required
              onChange={event => {
                setBuyerPostalCode(event.target.value);
                setStatedCounty(""); setCountyCandidates(undefined);
                setStatedJurisdiction(""); setCrossingCandidates(undefined);
              }}
              aria-describedby={fieldProblem("buyerPostalCode") ? "buyer-postal-error" : undefined}
            />
          </label>
          {fieldProblem("buyerPostalCode") && (
            <p className="error" id="buyer-postal-error" role="alert">
              {fieldProblem("buyerPostalCode")}
            </p>
          )}

          {crossingCandidates && (
            <>
              <label>
                {t.registrationState}
                <select
                  name="buyerJurisdiction"
                  value={statedJurisdiction}
                  onChange={event => setStatedJurisdiction(event.target.value)}
                  required
                >
                  <option value="">{t.selectState}</option>
                  {crossingCandidates.map(candidate => (
                    <option key={candidate} value={candidate}>
                      {stateName(candidate)}
                    </option>
                  ))}
                </select>
              </label>
              {fieldProblem("buyerJurisdiction") && (
                <p className="error" role="alert">
                  {fieldProblem("buyerJurisdiction")}
                </p>
              )}
            </>
          )}

          {countyCandidates && (
            <>
              <label>
                {t.registrationCounty}
                <select name="buyerCounty" value={statedCounty} required
                  aria-describedby="county-tax-explanation"
                  onChange={event => setStatedCounty(event.target.value)}>
                  <option value="">{t.selectCounty}</option>
                  {countyCandidates.map(county => <option key={county} value={county}>{fill(t.county, { county })}</option>)}
                </select>
              </label>
              <p id="county-tax-explanation" className="muted">
                {statedCounty ? fill(t.countyTaxNamed, { county: statedCounty, amount: format.money(500000, { minimumFractionDigits: 0 }) })
                  : fill(t.countyTaxUnnamed, { amount: format.money(500000, { minimumFractionDigits: 0 }) })}
              </p>
              {fieldProblem("buyerCounty") && <p className="error" role="alert">{fieldProblem("buyerCounty")}</p>}
            </>
          )}

          <DealerFields buyerPostalCode={buyerPostalCode} buyerState={statedJurisdiction}
            initialLocation={initial?.dealerLocation ?? initial?.dealerPostalCode} />
          {fieldProblem("dealerCity") && <p className="error" role="alert">{fieldProblem("dealerCity")}</p>}
          {fieldProblem("dealerState") && <p className="error" role="alert">{fieldProblem("dealerState")}</p>}
          <label>
            {t.paying}
            <select name="purchaseMethod" defaultValue={initial?.purchaseMethod ?? "CASH"}>
              <option value="CASH">{t.cash}</option>
              <option value="FINANCE">{t.dealerFinancing}</option>
            </select>
          </label>

          <fieldset>
            <legend>{t.whichVehicle}</legend>
            <div className="segmented">
              <label>
                <input
                  type="radio"
                  name="identifyBy"
                  value="vin"
                  checked={identifyBy === "vin"}
                  onChange={() => setIdentifyBy("vin")}
                />
                {t.haveVin}
              </label>
              <label>
                <input
                  type="radio"
                  name="identifyBy"
                  value="details"
                  checked={identifyBy === "details"}
                  onChange={() => setIdentifyBy("details")}
                />
                {t.enterDetails}
              </label>
            </div>

            {identifyBy === "vin" ? (
              <label>
                {t.vin}
                <input name="vin" required autoComplete="off" defaultValue={initial?.vin} />
              </label>
            ) : (
              <>
                <label>
                  {t.year}
                  <input name="year" type="number" min="1900" required />
                </label>
                <label>
                  {t.make}
                  <input name="make" required />
                </label>
                <label>
                  {t.model}
                  <input name="model" required />
                </label>
                <label>
                  {t.trim}
                  <input name="trim" />
                </label>
                <label>
                  {t.mileage}
                  <input name="mileage" type="number" min="0" required aria-describedby="mileage-hint" />
                </label>
                <p id="mileage-hint" className="muted">{t.mileageHint}</p>
              </>
            )}
          </fieldset>

          <details>
          <summary>{t.addMore}</summary>
          <label>
            {t.listingUrl}
            <input name="listingUrl" type="url" inputMode="url" />
          </label>

          <label>
            {t.maxOutTheDoor}
            <input name="maxOutTheDoorPrice" type="number" min="0" step="0.01" />
          </label>

          <fieldset className="switches">
            <legend>{t.permissions}</legend>
            <label>
              <input type="checkbox" name="humanReview" />
              {t.humanReview}
            </label>
            <label>
              <input type="checkbox" name="anonymizedResearch" />
              {t.research}
            </label>
          </fieldset>
          </details>
          <p className="muted">{t.processing}</p>
          <button type="submit" disabled={submitting}>
            {submitting ? t.starting : openCase ? t.continue : t.start}
          </button>
        </form>
      )}
    </section>
  );
}
