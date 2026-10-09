# 99 — Deploy the frontend on Cloudflare and the backend on AWS

**What to build:** A repeatable pilot deployment. The operator decided on 2026-10-08 to host the static
React client on Cloudflare, where the domain and DNS already live, and everything behind it on AWS: the
Spring Boot backend, PostgreSQL, document storage (ticket 98) and email (ticket 97). The site and the API
share one origin (`/api/*` routed by Cloudflare to the AWS backend) so the `account_session` cookie
(`SameSite=Strict`, `Path=/api/v1`) works without CORS. Expected pilot cost is about $40–70 a month before
model usage.

**Blocked by:** Ticket 93 (AWS and Cloudflare accounts owned by the company). The infrastructure code and
runbook can be written before then; applying them cannot. Tickets 97 and 98 should land first.

**Status:** open — infrastructure-as-code and runbook are agent work; provisioning and DNS are operator actions.

- [ ] Infrastructure is described as code (Terraform or AWS CDK, chosen and recorded) in the repository,
      with no secrets in it: one EC2 instance (about 2 vCPU, 4 GB) with an instance role limited to the
      document bucket and SES, RDS PostgreSQL (smallest class, encrypted, automatic backups, not publicly
      reachable), the S3 bucket from ticket 98, and security groups that admit HTTPS only from Cloudflare.
- [ ] The backend runs as a container with the ClamAV daemon and the document parser's confinement
      (`document.parser.mode`) set up as the hardening runbook requires; secrets come from SSM Parameter
      Store or Secrets Manager, never from files in the image.
- [ ] The client builds and deploys to Cloudflare (Pages or Workers static assets) from CI. Security
      headers and the Content Security Policy from ticket 79 are served at the edge, SPA fallback routes
      work, and `/api/*` is routed to the AWS origin over TLS with origin verification (authenticated
      origin pulls or an origin secret header) so the backend refuses traffic that bypasses Cloudflare.
- [ ] Cloudflare's upload size limit is checked against the largest accepted document and the result is
      recorded; uploads are not cached and API responses are `no-store`.
- [ ] The deployment declares `SPRING_PROFILES_ACTIVE=pilot`, and every production guard passes at startup
      (malware scanner, parser confinement, storage keys, evidence providers, mailbox and relay guards).
- [ ] The hardening runbook gains deploy, rollback, database migration, backup restore and secret rotation
      steps for this stack, and the deployed checks it already lists (HTTPS redirect, HSTS, headers, a
      synthetic case's document preview) are recorded against the real deployment.
- [ ] `docs/provider-inventory.md` lists Cloudflare (it terminates TLS and sees uploads in transit) and the
      AWS services with their regions; `docs/pilot-readiness-record.md` updates its
      `infrastructure-and-privacy-inventory` prerequisite.
