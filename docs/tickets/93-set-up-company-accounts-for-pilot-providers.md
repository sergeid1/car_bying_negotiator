# 93 — Set up company accounts for pilot providers

**What to build:** Every external account the pilot depends on is owned by ClearSight Labs LLC, signed
up with a company address on otdog.com and paid with the company card, so provider terms can be
executed in the company's name. Several pilot prerequisites are blocked only because no legal entity
existed to sign them: `provider-terms` (Anthropic, MarketCheck, Google and Microsoft as send-only
mailbox processors), `infrastructure-and-privacy-inventory` (hosting, storage, mail) and
`payment-provider` (voluntary contributions). Microsoft publisher verification in particular verifies
the legal business and cannot start without it. Filed on 2026-10-08 after the LLC filing was submitted.

**Blocked by:** Sunbiz approval of the Articles of Organization, then the EIN and a business bank account.

**Status:** blocked on the operator — filed on 2026-10-08. Every criterion is an operator action
(sign-ups, contracts, verification reviews), so this is not agent-ready. Agent work it unblocks
(provider inventory updates, tickets 97 to 99) is listed under each item.

## Foundation

- [ ] ClearSight Labs LLC appears on Sunbiz with its document number; the EIN confirmation letter
      (CP 575) and Articles are stored outside the repository.
- [ ] Company mail on otdog.com: at least one real mailbox (`admin@`) that owns every account below,
      with `support@` and `privacy@` as aliases. SPF, DKIM and DMARC are published for the domain.
- [ ] A company password manager holds every credential below; every account has 2FA enabled.
- [ ] The otdog.com registrar contact is the company, auto-renew is on and billing uses the company card.

## Accounts the code is waiting on

- [ ] **Anthropic:** a Claude Console organization for ClearSight Labs. Its commercial terms are
      reviewed for retention and exclusion of customer documents from training, and the result is
      recorded in `backend/src/main/resources/ai/model-vendor-terms.json`. The API key lives only in
      the operator's environment.
- [ ] **MarketCheck:** the evaluation account and written terms required by ticket 86 are held in the
      company's name.
- [ ] **Google Cloud:** a project owned by the company with the Gmail OAuth client for ticket 83
      (scope `gmail.send` only), a verified otdog.com domain, a public homepage and privacy policy,
      and the OAuth consent screen submitted for verification.
- [ ] **Microsoft:** a Microsoft AI Cloud Partner Program account verified for ClearSight Labs, an
      Entra app registration for ticket 83 (`Mail.Send`, `offline_access`) and publisher
      verification completed against that partner account.
- [ ] **Payments:** a Stripe (or chosen provider) account in the company's name for voluntary
      contributions, linked to the business bank account; ticket 32 owns the integration.

## Hosting and email

The stack was chosen on 2026-10-08: the React client on Cloudflare, where otdog.com's DNS already lives,
and the Spring backend, PostgreSQL, the ClamAV daemon, encrypted document storage and transactional email
on AWS. Ticket 99 deploys it, ticket 98 moves documents to S3 and ticket 97 sends mail through SES.

- [ ] **AWS:** an account owned by the company (root user on `admin@`, MFA on, root not used day to day),
      an administrator IAM Identity Center user for the operator, billing alerts at $50 and $100 a month,
      and one region chosen and recorded for every service below.
- [ ] **Amazon SES:** otdog.com verified with Easy DKIM, a custom MAIL FROM subdomain, SPF and DMARC
      published in Cloudflare DNS, pay-as-you-go pricing (not a bundled plan), and production access
      requested for transactional mail only (sign-in links, resets, case recovery, notices). Request it
      a week or two before the pilot; the sandbox allows only verified recipients.
- [ ] **Amazon S3, RDS and EC2:** created by ticket 99's infrastructure code once the account exists, not
      by hand; the operator approves the plan and the region.
- [ ] **Cloudflare:** the account that holds otdog.com is owned by the company with 2FA, and the client
      is deployed there (Pages or Workers static assets) with `/api/*` routed to the AWS backend.
- [ ] `docs/provider-inventory.md` records Cloudflare, Amazon SES, S3, RDS and EC2 with region and the
      Buyer data each one handles.

## Housekeeping

- [ ] The repository is moved to a GitHub organization owned by the company.
- [ ] A privacy policy and terms of service are published on otdog.com (needed by Google and
      Microsoft verification and by Buyers).
- [ ] General liability and errors-and-omissions/cyber insurance are in place, the local business tax
      receipt is obtained and bookkeeping is set up.
- [ ] `docs/pilot-readiness-record.md` and `docs/provider-inventory.md` are updated for each provider
      whose terms are executed.
