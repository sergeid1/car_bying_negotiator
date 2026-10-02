# 43 — Refresh the visual design with selectable light and dark themes

**What to build:** A cleaner, more modern Buyer interface with a light and a dark theme the Buyer can
choose from the header, following the approved redesign mockup: neutral palettes, Geist type, ink-pill
actions, an example buyer's-order "X-ray" on the landing page, and a report that leads with a large
total over a soft blue light.

**Blocked by:** 29 — Lead the report with the total, the questions, and the next action.

**Status:** complete — the header's appearance menu offers System, Light and Dark, remembers the choice
without a flash on load, and every browser layout and contrast check runs in both themes, as of
2026-10-02. Regrouping the report's lower sections into one collapsible list remains open (below).

- [x] An appearance control in the header offers System, Light and Dark. System follows the device and
      updates live; a pinned choice is stored in the browser only and applied before the first paint.
- [x] The menu is keyboard operable (arrow keys, Home/End, Escape returns focus), closes on an outside
      click, stays usable at 360 px, and works when browser storage is blocked.
- [x] Both themes are defined as one set of design tokens; no component carries its own colours.
- [x] Fonts are self-hosted (Geist and Geist Mono, OFL); no font request leaves the site.
- [x] The landing page shows a decorative example buyer's order with two flagged lines; it is hidden
      from assistive technology and removed once a case is open.
- [x] The vehicle choice is a segmented control and the optional permissions are switches, keeping
      native radio and checkbox inputs.
- [x] The report's total counts up once over a soft blue light; assistive technology reads the final
      amount, and reduced-motion users see the final amount only.
- [x] Every Playwright spec runs at 360 px in light and dark, including axe colour contrast; motion never
      fades text, so contrast holds at every frame.
- [ ] Group the report's secondary sections (amounts, market evidence, evidence and calculations,
      financing, feedback, downloads, retention) into one collapsible list without breaking the deep
      links from the total and findings into the evidence. Deferred: it changes navigation, not styling.

## Implementation notes — 2026-10-02

- `src/theme.ts` and `public/theme-init.js` share the `deal-xray-theme` storage key and the
  `data-theme` attribute on `<html>`; the init script is an external file so a future
  Content-Security-Policy without inline scripts keeps working.
- Dark tokens are declared twice in `styles.css`: under `prefers-color-scheme: dark` for
  `:root:not([data-theme="light"])`, and for `:root[data-theme="dark"]`. Change both together.
- `AnimatedAmount` renders only the final text when `matchMedia` or `requestAnimationFrame` is missing,
  so jsdom tests see the same DOM as before.
- The Playwright config adds a `narrow-dark` project; `e2e/theme-menu.spec.ts` covers persistence across
  reload, the pre-paint attribute, the open menu at 360 px, and both pinned themes.
