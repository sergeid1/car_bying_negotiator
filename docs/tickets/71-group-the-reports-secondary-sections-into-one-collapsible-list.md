# 71 — Group the report's secondary sections into one collapsible list

**What to build:** Below the total, the findings, the questions and the next action, the report's secondary sections (amounts, market evidence, evidence and calculations, financing, feedback, downloads, retention) sit in one collapsible list, so a Buyer on a phone reaches what matters without scrolling past every panel. Deep links from the total and the findings into the evidence still land on the right amount. Carved from ticket 43, whose last item it is.

**Blocked by:** 43 — Refresh the visual design with selectable themes; 30 — Render field-to-document evidence.

**Status:** ready-for-agent — filed on 2026-10-05 from ticket 43's open item.

- [ ] The secondary sections are one list of collapsible sections, each with a heading that names it and states whether it is open; the total, findings, questions and next action stay above the list and are never collapsed.
- [ ] Following a link from the total, a finding, or any other in-report link to an amount, evidence region or calculation opens the section that holds the target, moves focus to it and scrolls it into view; the browser back button and a link opened in a new tab behave the same.
- [ ] Sections are collapsed by default except where the Buyer followed a link; the choice is not stored server-side.
- [ ] The list is keyboard operable and announced correctly by assistive technology; it is registered in `Accessibility.test.tsx` and passes axe in both themes.
- [ ] The PDF export and the print view still include every section expanded, in the same order as before.
- [ ] Every Playwright spec still passes at 360 px in light and dark, with no horizontal overflow and no section content hidden from a test that previously reached it without an explicit open.
