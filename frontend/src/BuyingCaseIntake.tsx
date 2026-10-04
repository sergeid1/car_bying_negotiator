import { FormEvent, useEffect, useState } from "react";
import { arrivalChannel, forgetArrivalChannel } from "./AcquisitionChannel";

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

const STATE_NAMES: Record<string, string> = {
  "US-AL": "Alabama", "US-AK": "Alaska", "US-AZ": "Arizona", "US-AR": "Arkansas",
  "US-CA": "California", "US-CO": "Colorado", "US-CT": "Connecticut", "US-DE": "Delaware",
  "US-DC": "District of Columbia", "US-FL": "Florida", "US-GA": "Georgia", "US-HI": "Hawaii",
  "US-ID": "Idaho", "US-IL": "Illinois", "US-IN": "Indiana", "US-IA": "Iowa", "US-KS": "Kansas",
  "US-KY": "Kentucky", "US-LA": "Louisiana", "US-ME": "Maine", "US-MD": "Maryland",
  "US-MA": "Massachusetts", "US-MI": "Michigan", "US-MN": "Minnesota", "US-MS": "Mississippi",
  "US-MO": "Missouri", "US-MT": "Montana", "US-NE": "Nebraska", "US-NV": "Nevada",
  "US-NH": "New Hampshire", "US-NJ": "New Jersey", "US-NM": "New Mexico", "US-NY": "New York",
  "US-NC": "North Carolina", "US-ND": "North Dakota", "US-OH": "Ohio", "US-OK": "Oklahoma",
  "US-OR": "Oregon", "US-PA": "Pennsylvania", "US-RI": "Rhode Island", "US-SC": "South Carolina",
  "US-SD": "South Dakota", "US-TN": "Tennessee", "US-TX": "Texas", "US-UT": "Utah",
  "US-VT": "Vermont", "US-VA": "Virginia", "US-WA": "Washington", "US-WV": "West Virginia",
  "US-WI": "Wisconsin", "US-WY": "Wyoming", "US-AS": "American Samoa", "US-GU": "Guam",
  "US-MP": "Northern Mariana Islands", "US-PR": "Puerto Rico", "US-VI": "U.S. Virgin Islands",
};

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

export function BuyingCaseIntake({ onCaseOpened, initial, title }: {
  onCaseOpened: (value: CaseCredentials, referralDisclosure?: string) => void;
  /** Starting values the Buyer can still change, such as the VIN read from desk paperwork. */
  initial?: { vin?: string; dealerPostalCode?: string; purchaseMethod?: string };
  title?: string;
}) {
  const [options, setOptions] = useState<IntakeOptions>();
  const [identifyBy, setIdentifyBy] = useState<"vin" | "details">("vin");
  const [statedJurisdiction, setStatedJurisdiction] = useState("");
  const [crossingCandidates, setCrossingCandidates] = useState<string[]>();
  const [problem, setProblem] = useState<Problem>();
  const [submitting, setSubmitting] = useState(false);
  // Kept across a vehicle-evidence failure so a correction never restarts the case.
  const [openCase, setOpenCase] = useState<CaseCredentials>();
  const [conflicts, setConflicts] = useState<IdentityConflict[]>();
  const [referralDisclosure, setReferralDisclosure] = useState<string>();
  // Read when the case starts, so the link parameter leaves the address bar straight away.
  const [channel] = useState(arrivalChannel);

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
        }>("/api/v1/cases", {
          method: "POST",
          headers: { "Content-Type": "application/json", "Idempotency-Key": crypto.randomUUID() },
          body: JSON.stringify({
            buyerPostalCode: String(values.get("buyerPostalCode") ?? "").trim(),
            dealerPostalCode: String(values.get("dealerPostalCode") ?? "").trim(),
            buyerJurisdiction: statedJurisdiction || undefined,
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
    <section className="panel" aria-labelledby="intake-heading">
      <div className="section-heading">
        <div>
          <p className="step">Step 1 of 3</p>
          <h2 id="intake-heading">{title ?? "Start your Buying Case"}</h2>
        </div>
      </div>

      <p className="muted">
        {supported.length > 0
          ? `Supported during the pilot: ${supported.map(stateName).join(", ")}.`
          : "Enter the vehicle you are negotiating for."}
      </p>

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
              aria-describedby={fieldProblem("buyerPostalCode") ? "buyer-postal-error" : undefined}
            />
          </label>
          {fieldProblem("buyerPostalCode") && (
            <p className="error" id="buyer-postal-error" role="alert">
              {fieldProblem("buyerPostalCode")}
            </p>
          )}

          <label>
            Dealer ZIP code
            <input
              name="dealerPostalCode"
              inputMode="numeric"
              required
              defaultValue={initial?.dealerPostalCode}
              aria-describedby={fieldProblem("dealerPostalCode") ? "dealer-postal-error" : undefined}
            />
          </label>
          {fieldProblem("dealerPostalCode") && (
            <p className="error" id="dealer-postal-error" role="alert">
              {fieldProblem("dealerPostalCode")}
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
                  Trim
                  <input name="trim" required />
                </label>
                <label>
                  Mileage
                  <input name="mileage" type="number" min="0" required />
                </label>
              </>
            )}
          </fieldset>

          <label>
            Listing URL (optional)
            <input name="listingUrl" type="url" inputMode="url" />
          </label>

          <label>
            Most you will pay out the door (optional)
            <input name="maxOutTheDoorPrice" type="number" min="0" step="0.01" />
          </label>

          <fieldset className="switches">
            <legend>Permissions</legend>
            <p className="muted">
              Processing your entered details to produce your own report is required. The rest are
              optional and can be changed later.
            </p>
            <label>
              <input type="checkbox" name="humanReview" />
              Let a reviewer look at redacted fields if a number needs checking
            </label>
            <label>
              <input type="checkbox" name="anonymizedResearch" />
              Allow anonymized use of my case for research
            </label>
          </fieldset>

          <button type="submit" disabled={submitting}>
            {submitting ? "Starting…" : openCase ? "Continue" : "Start my case"}
          </button>
        </form>
      )}
    </section>
  );
}
