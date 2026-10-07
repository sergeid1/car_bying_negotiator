import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";
import { BuyingCaseIntake } from "./BuyingCaseIntake";
import { AccountSignedInContext } from "./BuyerAccount";

const OPTIONS = {
  supported: ["US-NC"],
  consentPolicies: {
    serviceProcessing: "service-processing.v1",
    humanReview: "human-review.v1",
    anonymizedResearch: "anonymized-research.v1",
  },
};

type Reply = { status?: number; body: unknown };

/** Routes each call by URL and method so a test states only the replies it cares about. */
function stubApi(replies: { cases?: Reply[]; evidence?: Reply[]; resolution?: Reply[] }) {
  const queues = {
    cases: [...(replies.cases ?? [])],
    evidence: [...(replies.evidence ?? [])],
    resolution: [...(replies.resolution ?? [])],
  };
  const calls: { url: string; body?: unknown; headers?: Record<string, string> }[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const url = String(input);
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    calls.push({ url, body, headers: init?.headers as Record<string, string> | undefined });
    let reply: Reply | undefined;
    if (url.endsWith("/intake-options")) reply = { body: OPTIONS };
    else if (url.includes("/resolution")) reply = queues.resolution.shift();
    else if (url.includes("/vehicle-evidence")) reply = queues.evidence.shift();
    else if (url.endsWith("/api/v1/cases")) reply = queues.cases.shift();
    if (!reply) throw new Error(`Unexpected request to ${url}`);
    return new Response(JSON.stringify(reply.body), {
      status: reply.status ?? 200,
      headers: { "Content-Type": "application/json" },
    });
  });
  return calls;
}

const CREATED = { case: { id: "case-1" }, accessToken: "case-secret" };
const CLEAN_EVIDENCE = { identityConflicts: [], reliableForAnalysis: true };

async function fillRequiredFields(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText(/your zip code/i), "27601");
  await user.type(screen.getByLabelText(/dealer zip code/i), "27604");
  await user.type(screen.getByLabelText(/^vin$/i), "1HGCM82633A004352");
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

test("a case is created from entered location and vehicle, with service processing consent recorded", async () => {
  const calls = stubApi({ cases: [{ body: CREATED }], evidence: [{ body: CLEAN_EVIDENCE }] });
  const opened = vi.fn();
  render(<BuyingCaseIntake onCaseOpened={opened} />);
  const user = userEvent.setup();

  await waitFor(() => expect(screen.getByText(/north carolina/i)).toBeInTheDocument());
  await fillRequiredFields(user);
  await user.click(screen.getByRole("button", { name: /start my case/i }));

  await waitFor(() => expect(opened).toHaveBeenCalledWith({ caseId: "case-1", token: "case-secret" }));

  const creation = calls.find(call => call.url.endsWith("/api/v1/cases"))?.body as Record<string, any>;
  expect(creation.buyerPostalCode).toBe("27601");
  expect(creation.dealerPostalCode).toBe("27604");
  expect(creation.consents.serviceProcessing).toEqual({
    granted: true,
    policyVersion: "service-processing.v1",
  });
  expect(creation.consents.anonymizedResearch.granted).toBe(false);

  const evidence = calls.find(call => call.url.includes("/vehicle-evidence"))?.body as Record<string, any>;
  expect(evidence.vin).toBe("1HGCM82633A004352");
  expect(evidence.confirmed).toBe(true);
});

test("a signed-in Buyer is told the case will be saved to the account before it is created", async () => {
  const calls = stubApi({ cases: [{ body: { ...CREATED, savedToAccount: true } }], evidence: [{ body: CLEAN_EVIDENCE }] });
  const saved = vi.fn();
  render(<AccountSignedInContext.Provider value={true}>
    <BuyingCaseIntake onCaseOpened={vi.fn()} onSavedToAccount={saved} />
  </AccountSignedInContext.Provider>);
  expect(screen.getByRole("note")).toHaveTextContent("this case will be saved to your Buyer Account");
  const user = userEvent.setup();
  await waitFor(() => expect(screen.getByText(/north carolina/i)).toBeInTheDocument());
  await fillRequiredFields(user);
  await user.click(screen.getByRole("button", { name: /start my case/i }));
  await waitFor(() => expect(saved).toHaveBeenCalledTimes(1));
  expect(calls.find(call => call.url.endsWith("/api/v1/cases"))?.headers?.["Account-Request"]).toBe("1");
});

test("a signed-out Buyer is told no account is required and the case is not saved to one", async () => {
  const calls = stubApi({ cases: [{ body: CREATED }], evidence: [{ body: CLEAN_EVIDENCE }] });
  const saved = vi.fn();
  render(<AccountSignedInContext.Provider value={false}>
    <BuyingCaseIntake onCaseOpened={vi.fn()} onSavedToAccount={saved} />
  </AccountSignedInContext.Provider>);
  expect(screen.getByRole("note")).toHaveTextContent("No account is required");
  expect(screen.getByRole("link", { name: "sign in with a Buyer Account" })).toHaveAttribute("href", "#buyer-account");
  const user = userEvent.setup();
  await waitFor(() => expect(screen.getByText(/north carolina/i)).toBeInTheDocument());
  await fillRequiredFields(user);
  await user.click(screen.getByRole("button", { name: /start my case/i }));
  await waitFor(() => expect(calls.some(call => call.url.includes("/vehicle-evidence"))).toBe(true));
  expect(calls.find(call => call.url.endsWith("/api/v1/cases"))?.headers?.["Account-Request"]).toBeUndefined();
  expect(saved).not.toHaveBeenCalled();
});

test("manual vehicle details are sent when the Buyer has no VIN", async () => {
  const calls = stubApi({ cases: [{ body: CREATED }], evidence: [{ body: CLEAN_EVIDENCE }] });
  render(<BuyingCaseIntake onCaseOpened={vi.fn()} />);
  const user = userEvent.setup();

  await user.type(screen.getByLabelText(/your zip code/i), "27601");
  await user.type(screen.getByLabelText(/dealer zip code/i), "27604");
  await user.click(screen.getByRole("radio", { name: /enter the vehicle details/i }));
  await user.type(screen.getByLabelText(/^year$/i), "2021");
  await user.type(screen.getByLabelText(/^make$/i), "Honda");
  await user.type(screen.getByLabelText(/^model$/i), "Accord");
  await user.type(screen.getByLabelText(/^trim$/i), "EX-L");
  await user.type(screen.getByLabelText(/^mileage$/i), "38000");
  await user.click(screen.getByRole("button", { name: /start my case/i }));

  await waitFor(() => expect(calls.some(call => call.url.includes("/vehicle-evidence"))).toBe(true));
  const evidence = calls.find(call => call.url.includes("/vehicle-evidence"))?.body as Record<string, any>;
  expect(evidence.vin).toBeNull();
  expect(evidence.manualFacts).toEqual({
    year: 2021,
    make: "Honda",
    model: "Accord",
    trim: "EX-L",
    mileage: 38000,
  });
});

test("a validation failure names the field and the required correction", async () => {
  stubApi({
    cases: [{
      status: 400,
      body: {
        detail: "Enter a five-digit United States ZIP code.",
        field: "buyerPostalCode",
      },
    }],
  });
  render(<BuyingCaseIntake onCaseOpened={vi.fn()} />);
  const user = userEvent.setup();

  await fillRequiredFields(user);
  await user.click(screen.getByRole("button", { name: /start my case/i }));

  const alert = await screen.findByRole("alert");
  expect(alert).toHaveTextContent("Enter a five-digit United States ZIP code.");
  expect(screen.getByLabelText(/your zip code/i)).toHaveAccessibleDescription(
    "Enter a five-digit United States ZIP code.",
  );
});

test("a location outside the pilot jurisdiction is explained and no case is opened", async () => {
  stubApi({
    cases: [{
      status: 422,
      body: {
        detail:
          "The service does not support purchases in that jurisdiction yet. Nothing you entered has been stored.",
        field: "buyerPostalCode",
        reason: "JURISDICTION_NOT_SUPPORTED",
        candidateJurisdictions: ["US-NC"],
      },
    }],
  });
  const opened = vi.fn();
  render(<BuyingCaseIntake onCaseOpened={opened} />);
  const user = userEvent.setup();

  await fillRequiredFields(user);
  await user.click(screen.getByRole("button", { name: /start my case/i }));

  expect(await screen.findByRole("alert")).toHaveTextContent(/does not support purchases/i);
  expect(opened).not.toHaveBeenCalled();
});

test("a postal code crossing a state line asks for the state and then proceeds", async () => {
  const calls = stubApi({
    cases: [
      {
        status: 422,
        body: {
          detail: "That postal code crosses a state line, so the state cannot be determined from it.",
          field: "buyerJurisdiction",
          reason: "POSTAL_CODE_CROSSES_STATE_LINE",
          candidateJurisdictions: ["US-NC", "US-VA"],
        },
      },
      { body: CREATED },
    ],
    evidence: [{ body: CLEAN_EVIDENCE }],
  });
  render(<BuyingCaseIntake onCaseOpened={vi.fn()} />);
  const user = userEvent.setup();

  await fillRequiredFields(user);
  await user.click(screen.getByRole("button", { name: /start my case/i }));

  const selector = await screen.findByLabelText(/state where the vehicle will be registered/i);
  expect(screen.getByRole("option", { name: "Virginia" })).toBeInTheDocument();
  await user.selectOptions(selector, "US-NC");
  await user.click(screen.getByRole("button", { name: /start my case/i }));

  await waitFor(() => expect(calls.filter(call => call.url.endsWith("/api/v1/cases")).length).toBe(2));
  const retry = calls.filter(call => call.url.endsWith("/api/v1/cases"))[1].body as Record<string, any>;
  expect(retry.buyerJurisdiction).toBe("US-NC");
});

test("an unresolved vehicle conflict blocks progression and resolves without re-entering data", async () => {
  const conflict = {
    id: "conflict-1",
    field: "vin",
    confirmedValue: "1HGCM82633A004352",
    listingValue: "1HGCM82633A099999",
    status: "UNRESOLVED",
  };
  const calls = stubApi({
    cases: [{ body: CREATED }],
    evidence: [{ body: { identityConflicts: [conflict], reliableForAnalysis: false } }],
    resolution: [{ body: CLEAN_EVIDENCE }],
  });
  const opened = vi.fn();
  render(<BuyingCaseIntake onCaseOpened={opened} />);
  const user = userEvent.setup();

  await fillRequiredFields(user);
  await user.click(screen.getByRole("button", { name: /start my case/i }));

  expect(await screen.findByText(/resolve the vehicle conflict/i)).toBeInTheDocument();
  expect(screen.getByText("1HGCM82633A099999")).toBeInTheDocument();
  expect(opened).not.toHaveBeenCalled();

  await user.click(screen.getByRole("button", { name: /keep what i confirmed/i }));

  await waitFor(() => expect(opened).toHaveBeenCalledWith({ caseId: "case-1", token: "case-secret" }));
  const resolution = calls.find(call => call.url.includes("/resolution"))?.body as Record<string, any>;
  expect(resolution.resolution).toBe("KEEP_CONFIRMED_VEHICLE");
  // The case was created once; correcting the conflict did not restart intake.
  expect(calls.filter(call => call.url.endsWith("/api/v1/cases")).length).toBe(1);
});

test("every intake control is reachable by keyboard in a single forward traversal", async () => {
  stubApi({});
  render(<BuyingCaseIntake onCaseOpened={vi.fn()} />);
  const user = userEvent.setup();

  const order = [
    screen.getByLabelText(/your zip code/i),
    screen.getByLabelText(/dealer zip code/i),
    screen.getByLabelText(/how you are paying/i),
    screen.getByRole("radio", { name: /i have the vin/i }),
  ];
  for (const control of order) {
    await user.tab();
    expect(control).toHaveFocus();
  }

  // Radio groups move with arrow keys, so the VIN field follows the group rather than a tab stop.
  await user.tab();
  expect(screen.getByLabelText(/^vin$/i)).toHaveFocus();
  await user.tab();
  expect(screen.getByLabelText(/listing url/i)).toHaveFocus();
  await user.tab();
  expect(screen.getByLabelText(/most you will pay out the door/i)).toHaveFocus();
  await user.tab();
  expect(screen.getByRole("checkbox", { name: /let a reviewer look/i })).toHaveFocus();
  await user.tab();
  expect(screen.getByRole("checkbox", { name: /anonymized use/i })).toHaveFocus();
  await user.tab();
  expect(screen.getByRole("button", { name: /start my case/i })).toHaveFocus();
});
