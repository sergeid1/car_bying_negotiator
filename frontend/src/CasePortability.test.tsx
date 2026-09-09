import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { CaseDownloads, RecoveryAccess } from "./CasePortability";
afterEach(() => { vi.unstubAllGlobals(); window.history.replaceState(null, "", "/"); });
it("verifies a recovery fragment only when requested and clears it from the URL", async () => {
  window.history.replaceState(null, "", "/#recover=secret");
  const fetcher=vi.fn().mockResolvedValue({ ok:true, json:async()=>({caseId:"case",accessToken:"new-token"}) });
  vi.stubGlobal("fetch",fetcher); const recovered=vi.fn();
  render(<RecoveryAccess onRecovered={recovered} />);
  expect(fetcher).not.toHaveBeenCalled();
  expect(window.location.hash).toBe("");
  fireEvent.click(screen.getByRole("button",{name:"Verify recovery link"}));
  await waitFor(()=>expect(recovered).toHaveBeenCalledWith({caseId:"case",token:"new-token"}));
  expect(fetcher.mock.calls[0][1].body).toBe(JSON.stringify({token:"secret"}));
});
it("retries a failed download with the same export key", async () => {
  const fetcher=vi.fn().mockRejectedValue(new Error("offline")); vi.stubGlobal("fetch",fetcher);
  render(<CaseDownloads caseId="case" token="token" />);
  fireEvent.click(screen.getByRole("button",{name:"Export retained case data"}));
  await screen.findByRole("alert");
  fireEvent.click(screen.getByRole("button",{name:"Export retained case data"}));
  await waitFor(()=>expect(fetcher).toHaveBeenCalledTimes(2));
  expect(fetcher.mock.calls[0][1].headers["Idempotency-Key"]).toBe(fetcher.mock.calls[1][1].headers["Idempotency-Key"]);
  expect(fetcher.mock.calls[0][0]).toBe("/api/v1/cases/case/exports");
});
