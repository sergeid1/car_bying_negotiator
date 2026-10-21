import type { Page } from "./fixtures";

/** Opens one step of the guided case page from its progress bar, as a Buyer would by hand (ticket 112). */
export async function openCaseStep(page: Page, name: string) {
  await page.getByRole("navigation", { name: "Case progress" }).getByRole("button", { name: new RegExp(name) }).click();
}
