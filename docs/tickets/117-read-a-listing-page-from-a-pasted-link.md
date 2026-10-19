# 117 — Read a listing page from a pasted link

**What to build:** The Buyer pastes the link to a dealer's listing and the server fetches that one page,
once, at the Buyer's request, and reads its price, VIN, mileage and dealer into a Listing Snapshot (most
dealer sites publish these as schema.org `Vehicle` or `Car` data). This saves the Buyer the capture step
in ticket 115, which remains the fallback. Filed on 2026-10-14 after comparing the product with CoPilot,
whose search appears to come from its own crawl of dealer websites.

**Blocked by:** the operator, on two decisions:
1. **Spec amendment.** Spec 2.2 excludes "inventory discovery from unlicensed sources or scraping" (section
   2.2) and allows a Listing Snapshot only "when licensed access supports it" (section 5.2). A single
   Buyer-requested fetch is not inventory discovery, but the spec must say so explicitly in an amendment
   before this is built (the next spec version after 2.5: 2.3 added insurance and loan quotes, 2.4 the three pilot states and 2.5 Toto, ticket 118).
2. **Terms review.** Dealer sites' and listing portals' terms of use, and whether storing the fetched
   facts (not photos or description text) is acceptable, recorded in `docs/provider-inventory.md`.
Also 115 (the snapshot source and price check this reuses).

**Status:** blocked on the operator — filed on 2026-10-14.

## Scope once unblocked

- A `ListingEvidenceProvider` implementation that fetches only the URL the Buyer pasted, with no link
  following, crawling or background refresh, and stores no photos or description text.
- Server-side request forgery protection: HTTPS only, ports 443 only, the resolved address checked against
  private, loopback, link-local and metadata ranges at connect time (not only at parse time), no
  redirects to another host, a short timeout and a body size cap.
- Reads schema.org JSON-LD first; a page without it is `UNSUPPORTED` and the Buyer is sent to ticket 115's
  capture. No per-site parsers.
- Identifies itself honestly in its `User-Agent`, respects `robots.txt` for that path, and is off in pilot
  until the terms decision is recorded.

## Done when

- [ ] The spec amendment states the single-fetch exception and the operator's terms review is recorded.
- [ ] The fetch refuses private and metadata addresses, DNS rebinding, redirects off host, oversized and
      slow responses, with tests for each.
- [ ] A fetched listing becomes the same Buyer-confirmed Listing Snapshot as ticket 115 and feeds the same
      price check.
- [ ] Unsupported pages fall back to ticket 115's capture without an error.
