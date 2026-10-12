import { FormEvent, useContext, useEffect, useState } from "react";
import { arrivalChannel, forgetArrivalChannel } from "./AcquisitionChannel";
import { openSidePanel } from "./SidePanel";
import { AccountSignedInContext } from "./BuyerAccount";
import { DealerFields, STATE_NAMES } from "./DealerFields";

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


function stateName(jurisdiction: string) {
  return STATE_NAMES[jurisdiction] ?? jurisdiction;
}

async function send<T>(path: string, options: RequestInit): Promise<T> {
  const response = await fetch(path, options);
  if (response.ok) {
    return (await response.json()) as T;
  }
  let problem: Problem = { detail: "That request could not be completed." };
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
        });
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
        setProblem({ detail: "That request could not be completed." });
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
          : { detail: "That request could not be completed." },
      );
    }
  }

  const fieldProblem = (field: string) => (problem?.field === field ? problem.detail : undefined);

  return (
    <section className="panel" id="buying-case-intake" aria-labelledby="intake-heading">
      <div className="section-heading">
        <div>
          <p className="step">Step 1</p>
          <h2 id="intake-heading">{title ?? "Start your Buying Case"}</h2>
        </div>
      </div>

      <p className="muted">
        {supported.length > 0
          ? `Supported during the pilot: ${supported.map(value => value === "US-NC"
              ? "North Carolina (synthetic development fixture)" : stateName(value)).join(", ")}.`
          : "Enter the vehicle you are negotiating for."}
      </p>

      {!openCase && signedIn === true && (
        <p className="account-save-note" role="note">
          You are signed in, so this case will be saved to your Buyer Account. Its private case link works too.
        </p>
      )}
      {!openCase && signedIn === false && (
        <p className="account-save-note" role="note">
          No account is required. To keep your cases in one place, you can{" "}
          <a href="#account-panel" onClick={event => {
            event.preventDefault(); openSidePanel("account");
          }}>sign in with a Buyer Account</a> first.
        </p>
      )}

      {problem && !problem.field && (
        <p className="error" role="alert">
          {problem.detail}
        </p>
      )}

      {conflicts ? (
        <div>
          <h3>Resolve the vehicle conflict</h3>
          <p className="muted">
            The listing and the vehicle you confirmed disagree. Choose which to keep before the
            analysis continues. Your entered details are kept either way.
          </p>
          {conflicts.map(conflict => (
            <div key={conflict.id} className="status-row">
              <dl className="facts">
                <div>
                  <dt>Field</dt>
                  <dd>{conflict.field}</dd>
                </div>
                <div>
                  <dt>You confirmed</dt>
                  <dd>{conflict.confirmedValue}</dd>
                </div>
                <div>
                  <dt>The listing says</dt>
                  <dd>{conflict.listingValue}</dd>
                </div>
              </dl>
              <div className="actions">
                <button type="button" onClick={() => resolveConflict(conflict.id, "KEEP_CONFIRMED_VEHICLE")}>
                  Keep what I confirmed
                </button>
                <button
                  type="button"
                  className="btn-secondary"
                  onClick={() => resolveConflict(conflict.id, "USE_LISTING_VEHICLE")}
                >
                  Use the listing value
                </button>
              </div>
            </div>
          ))}
        </div>
      ) : (
        <form onSubmit={submit}>
          <label>
            Your ZIP code
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
                State where the vehicle will be registered
                <select
                  name="buyerJurisdiction"
                  value={statedJurisdiction}
                  onChange={event => setStatedJurisdiction(event.target.value)}
                  required
                >
                  <option value="">Select a state</option>
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
                Florida county where the vehicle will be registered
                <select name="buyerCounty" value={statedCounty} required
                  aria-describedby="county-tax-explanation"
                  onChange={event => setStatedCounty(event.target.value)}>
                  <option value="">Select a county</option>
                  {countyCandidates.map(county => <option key={county} value={county}>{county} County</option>)}
                </select>
              </label>
              <p id="county-tax-explanation" className="muted">
                {statedCounty ? `${statedCounty} County` : "Your registration county"} sets the county surtax rate.
                The surtax applies to the first $5,000 of the taxable price after the trade-in.
              </p>
              {fieldProblem("buyerCounty") && <p className="error" role="alert">{fieldProblem("buyerCounty")}</p>}
            </>
          )}

          <DealerFields buyerPostalCode={buyerPostalCode} buyerState={statedJurisdiction}
            initialLocation={initial?.dealerLocation ?? initial?.dealerPostalCode} />
          {fieldProblem("dealerCity") && <p className="error" role="alert">{fieldProblem("dealerCity")}</p>}
          {fieldProblem("dealerState") && <p className="error" role="alert">{fieldProblem("dealerState")}</p>}
          <label>
            How you are paying
            <select name="purchaseMethod" defaultValue={initial?.purchaseMethod ?? "CASH"}>
              <option value="CASH">Cash</option>
              <option value="FINANCE">Dealer financing</option>
            </select>
          </label>

          <fieldset>
            <legend>Which vehicle?</legend>
            <div className="segmented">
              <label>
                <input
                  type="radio"
                  name="identifyBy"
                  value="vin"
                  checked={identifyBy === "vin"}
                  onChange={() => setIdentifyBy("vin")}
                />
                I have the VIN
              </label>
              <label>
                <input
                  type="radio"
                  name="identifyBy"
                  value="details"
                  checked={identifyBy === "details"}
                  onChange={() => setIdentifyBy("details")}
                />
                Enter the vehicle details
              </label>
            </div>

            {identifyBy === "vin" ? (
              <label>
                VIN
                <input name="vin" required autoComplete="off" defaultValue={initial?.vin} />
              </label>
            ) : (
              <>
                <label>
                  Year
                  <input name="year" type="number" min="1900" required />
                </label>
                <label>
                  Make
                  <input name="make" required />
                </label>
                <label>
                  Model
                  <input name="model" required />
                </label>
                <label>
                  Trim (optional)
                  <input name="trim" />
                </label>
                <label>
                  Mileage
                  <input name="mileage" type="number" min="0" required aria-describedby="mileage-hint" />
                </label>
                <p id="mileage-hint" className="muted">Use 0 if new.</p>
              </>
            )}
          </fieldset>

          <details>
          <summary>Add more (optional)</summary>
          <label>
            Listing URL (optional)
            <input name="listingUrl" type="url" inputMode="url" />
          </label>

          <label>
            Most you will pay out the door (optional)
            <input name="maxOutTheDoorPrice" type="number" min="0" step="0.01" />
          </label>

          <fieldset className="switches">
            <legend>Optional permissions</legend>
            <label>
              <input type="checkbox" name="humanReview" />
              Let a person check redacted fields when a number needs checking
            </label>
            <label>
              <input type="checkbox" name="anonymizedResearch" />
              Use my case for research with identifying details removed
            </label>
          </fieldset>
          </details>
          <p className="muted">Starting means otdog may process what you enter to produce your report.</p>
          <button type="submit" disabled={submitting}>
            {submitting ? "Starting…" : openCase ? "Continue" : "Start my case"}
          </button>
        </form>
      )}
    </section>
  );
}
