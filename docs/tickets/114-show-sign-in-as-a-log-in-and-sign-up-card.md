# 114 — Show sign-in as a Log in / Sign up card

**What to build:** Signed out, "Sign in" opens a centered card over the dimmed page instead of the side panel,
styled after the operator's reference screenshots (2026-10-13): a white rounded card, a large title, one
switching line under it, full-width inputs and buttons, an "or" divider and outlined provider buttons. Signed in,
the account button keeps opening the existing side panel.

**Blocked by:** none. It runs in parallel with ticket 113 and builds against 113's API with stubbed responses.
Any API change goes through the coordinator.

**Status:** complete as of 2026-10-13. Signed out, "Sign in" opens the centered card (`SidePanel` gains a
centered variant; `SignIn.tsx` holds both modes); signed in, the side panel stays. Provider buttons appear only for
providers the server lists, so they stay hidden until ticket 113's settings are configured. The success notice
names the provider from the complete response's `provider` field.

## The card

A modal dialog (`role="dialog"`, `aria-modal`, labelled by its title) centered in the viewport, with a close
button, Escape and backdrop click to close, focus trapped inside and returned to the opener, like `SidePanel`.
At 360 px it fills the width with a 16 px gutter and scrolls inside itself. Light and dark themes use the
existing tokens; the primary button uses the app's accent, not the screenshot's blue.

**Log in** (default):

1. Title "Log in". Under it: "Don't have an account? **Sign up**" (a text button that switches mode).
2. **Email** box, then a full-width primary **Continue with email**. It sends the sign-in link exactly as today
   (`/api/v1/account/sign-in-requests`); the fixed answer still does not reveal whether an account exists.
   A saved passkey is still offered from the email box's autofill.
3. Under the email box, a small "Use a password instead" text link (the existing password step).
4. An "or" divider, then outlined full-width buttons: **Continue with Google** and **Continue with Apple**
   with their official marks, then **Continue with a passkey** when passkeys are supported.
5. Footer under a hairline: the collapsed "Have a private case link?" disclosure (`CaseLinkAccess`).

**Sign up:**

1. Title "Sign up". Under it: "Already have an account? **Log in**".
2. Provider buttons first (Google, Apple), then "or", then the **Email** box and **Continue with email**.
3. Footer: "Optional. Keeps your cases in one place." No terms/privacy links: those pages do not exist yet.

Both modes call the same endpoints; Sign up and Log in differ only in order and copy. The "Check your email"
step, password step and errors show inside the same card, as `SignIn.tsx` does today.

## Google and Apple (ticket 113 API)

- On open, `GET /api/v1/account/social-sign-in/providers`; show only listed providers. If the request fails or
  lists none, hide the provider buttons and the "or" divider.
- A provider button calls `POST /api/v1/account/social-sign-in` `{"provider"}` with `Account-Request: 1` and
  navigates the window to `authorizationUrl`.
- On load, `#social-sign-in=<state>`: remove the hash (as `#sign-in=` is today), open the card, call
  `POST /api/v1/account/social-sign-in/complete` `{"state"}` with `Account-Request: 1`, then treat success
  like any other sign-in (load the account, close the card, show "Signed in with Google." / "... Apple.").
- `#social-sign-in-error=cancelled|failed`: remove the hash, open the card and show "Sign-in with your
  provider was cancelled." or the 400 detail wording from ticket 113.
- `client-content-security-policy.spec.ts` must keep passing; the provider pages are top-level navigations.

## Done when

- [ ] Signed out, "Sign in" opens the centered card in Log in mode; the mode switch works both ways, and
      signed in, the account button opens the side panel.
- [ ] Email link, password, passkey (button and autofill) and private-case-link flows work from the card.
- [ ] Provider buttons appear only for listed providers and start, return and finish against stubbed routes,
      including the cancelled and failed returns.
- [ ] Unit tests cover the modes, provider list, start, complete and error hashes; the card is in the
      `Accessibility.test.tsx` axe sweep.
- [ ] `buyer-account-narrow-width.spec.ts`, `passkeys.spec.ts` and `passwords.spec.ts` are updated for the card;
      a new browser spec covers both modes and the provider return at 360 px in both themes.
- [ ] `npm test`, `npm run build` and `npx playwright test` pass.
