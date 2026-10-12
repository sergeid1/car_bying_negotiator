# 110 — Make sign-in one email box and one button

**What to build:** A sign-in panel a Buyer understands at a glance. Today the "Sign in" panel opens on a
"Buyer Account" heading and about 100 words that say "no account is needed" three ways. Below that, all three
sign-in methods appear together, with two separate email boxes (one for the emailed link, one for the password),
a password box, a passkey button, and "Forgot password? Use 'Email me a sign-in link' above." The header also
has two buttons that both mean "get back to my stuff": "Your case" and "Sign in". Operator request on
2026-10-12, from a design review of the running client.

**Blocked by:** none. It touches hot files (`App.tsx`, `styles.css`, `Accessibility.test.tsx`), so run it
before 111 and 112, not alongside them.

**Status:** open — agent-ready. Client only; no API or migration change is expected.

**Decision (operator, 2026-10-12):** keep the private case link and email recovery. They stay so that no
account is required, but they leave the header and sit behind a "Have a private case link?" disclosure in this
panel.

## The panel

1. Title "Sign in". One line under it: "Optional. Keeps your cases in one place." Nothing else above the form.
2. One **Email** box and one **Continue** button.
3. After Continue:
   - The browser offers the passkey prompt when one is available (conditional mediation / autofill), so a
     passkey user never has to find a separate button.
   - If the account has a password, show a password box with "Email me a link instead" as a text link.
   - Otherwise, send the sign-in link and show "Check your email. The link works for 15 minutes." with a
     "Send again" text link and "Use a different email".
   - A new email creates the account, as today. Do not tell the Buyer whether an account already exists.
4. "Forgot password?" sends the sign-in link directly; it is no longer a sentence that points to another
   button.
5. At the bottom, a collapsed **Have a private case link?** disclosure. It holds today's "Open a saved case"
   (paste the link) and, under "Lost the link?", the email recovery request.

## The header

- Signed out, with no case open: the logo, "I'm at the dealer", the theme menu and "Sign in". The "Your case"
  button goes away; the panel above covers opening and recovering a case.
- Signed in: "Sign in" becomes the account button, as today.
- With a case open, the case's own options (private link, recovery email opt-in, downloads, delete) stay
  reachable from a "This case" button until ticket 112 moves them onto the case page.
- A `#sign-in=`, `#recover=` or `#case=` link and a return from a mailbox provider still open the panel that
  finishes them, as today.

## Done when

- [ ] The signed-out panel shows one email box, one main button, one line of explanation and the collapsed
      case-link disclosure. Nothing else is visible.
- [ ] Email link, password and passkey sign-in all still work from that single box, and password reset goes
      through the emailed link.
- [ ] The header has no "Your case" button when no case is open; the saved-case and recovery flows work from
      the disclosure.
- [ ] The response for an unknown email looks the same as for a known one.
- [ ] `npm test`, `npm run build` and `npx playwright test` pass, including the passkey, password and
      buyer-account narrow-width specs, the axe sweep and the 360 px layout in both themes.
