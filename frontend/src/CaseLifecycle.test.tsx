import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { CaseLifecycle } from "./CaseLifecycle";

afterEach(() => vi.unstubAllGlobals());

it("revokes the displayed case only after acceptance and distinguishes pending from verified deletion", async () => {
  const fetcher = vi.fn().mockResolvedValueOnce({ ok: true, json: async () => ({ state: "DELETION_PENDING", backupStatus: "UNKNOWN" }) })
    .mockResolvedValueOnce({ ok: true, json: async () => ({ state: "DELETED", backupStatus: "UNKNOWN" }) });
  vi.stubGlobal("fetch", fetcher);
  const revoked = vi.fn();
  render(<CaseLifecycle caseId="case-17" token="secret" onRevoked={revoked} />);
  expect(screen.getByRole("button", { name: "Delete Buying Case" })).toBeDisabled();
  fireEvent.click(screen.getByRole("checkbox"));
  fireEvent.click(screen.getByRole("button", { name: "Delete Buying Case" }));
  await screen.findByText(/Case access has been revoked/);
  expect(revoked).toHaveBeenCalledOnce();
  expect(screen.queryByText(/Active-system removal verified/)).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Check deletion progress" }));
  await screen.findByText(/Active-system removal verified/);
  expect(screen.getByText(/Backup expiry is unverified/)).toBeInTheDocument();
  expect(fetcher.mock.calls[0][0]).toBe("/api/v1/cases/case-17/deletion");
});

it("keeps case access available after a failed request and supports retry", async () => {
  const fetcher = vi.fn().mockResolvedValue({ ok: false });
  vi.stubGlobal("fetch", fetcher);
  const revoked = vi.fn();
  render(<CaseLifecycle caseId="case-17" token="secret" onRevoked={revoked} />);
  fireEvent.click(screen.getByRole("checkbox"));
  fireEvent.click(screen.getByRole("button", { name: "Delete Buying Case" }));
  await screen.findByRole("alert");
  expect(revoked).not.toHaveBeenCalled();
  await waitFor(() => expect(screen.getByRole("button", { name: "Delete Buying Case" })).toBeEnabled());
});
