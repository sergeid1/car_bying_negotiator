# Cloudflare and AWS pilot deployment (ticket 99)

**Written, not provisioned.** Company accounts, credentials and provider approvals are still
pending ticket 93. All deployed outcomes below are PENDING. These instructions are for the
operator after those prerequisites, not authorization to deploy or enroll Buyers now.

## Choices and boundaries

Terraform in `infra/pilot` describes AWS; it has no remote backend or secret values. Terraform
was chosen over CDK so the VPC, IAM and deletion controls are reviewable without an application
build or generated CloudFormation. Use Terraform 1.6–1.x and AWS provider 6.x. After the first
offline `init`, `.terraform.lock.hcl` pins AWS provider 6.68.0 with registry checksums. Review
upgrades deliberately. Protect state and saved plans as confidential infrastructure metadata.
Before applying, configure an independently managed encrypted, versioned state backend with
locking and restricted access; never commit state or use this document as approval of a plan.

The default proposed AWS region is **us-east-1**, not an assertion of actual residency. EC2
`t3.medium` (2 vCPU/4 GiB), private encrypted PostgreSQL 17 RDS `db.t4g.micro`, S3, SES/SNS,
SSM and ECR share that region. RDS has seven-day automatic backups and deletion protection.
Private DB subnets have no internet route. The public origin has only TCP 443 ingress from
operator-supplied current Cloudflare IPv4 CIDRs, no IPv6, SSH or public application port.
Egress allows TLS, SES STARTTLS and PostgreSQL; use HTTPS package repositories. SSM Session
Manager handles administration. The instance role has bucket-scoped storage/inspection,
one SES identity, one SSM parameter/KMS key, one ECR repository and SSM management access.
The SSM management policy and registry authorization are operational exceptions to document/
mail permissions, not access to arbitrary application secrets. SES SMTP uses separately
created identity-restricted SMTP credentials; EC2 role credentials do not authenticate SMTP.

S3 has all four public-access blocks, enforced ownership, SSE-S3 and TLS-only policy.
Versioning, replication and Object Lock are never enabled. Application AES-GCM remains
mandatory. AWS physical deletion timing, independent backup expiry and restore are unverified.
RDS master credentials are service-managed in Secrets Manager; do not give them to the app.
Create a schema owner/migrator and a separate least-privilege runtime SQL user after provisioning.
Runtime rights must cover existing worker writes, erasure and schema validation; migration DDL
belongs to the migrator. Backups retain personal data until expiry; do not claim immediate purge.

Cloudflare Workers static assets serve the React build, including SPA navigations. The Worker
runs before every asset so the canonical `frontend/client-headers.json` applies to HTML,
fallbacks and assets. `/api/*` streams to a separate HTTPS AWS origin, with redirects refused,
no cache and preserved cookie/export CSP headers. It overwrites the verification header.
Nginx checks a shared random secret, then proxies to loopback `127.0.0.1:8080`; missing or wrong
headers return 403 before reaching Spring. The network allowlist alone is insufficient because
other Cloudflare customers also use those ranges. Use a publicly trusted DNS-01 TLS certificate
for the origin; a Cloudflare Origin CA certificate alone is not this setup's TLS trust design.
The Worker and origin share no browser cross-origin calls. `account_session` retains its
Secure/SameSite=Strict/Path=/api/v1 behavior. Workers preview URLs and workers.dev are disabled.
Logs/traces at the Worker and Nginx ingress are disabled to avoid recording request content.
The Worker also deletes any client copy of `X-Otdog-Client-Address` and sets it from
Cloudflare's own `CF-Connecting-IP`. Nginx passes that header and the verification header only
on requests carrying the secret, and clears `Forwarded` and `X-Forwarded-*`. Spring reads the
same `ORIGIN_SECRET` (from `backend.env`, as `security.client-address.origin-secret`) and keys
its per-client quotas on the visitor address only when the request carries it; otherwise it keys
on the loopback peer. `server.forward-headers-strategy=none` stays, so forwarding headers never
select a key. IPv6 visitors share a bucket per /64. The address is an in-memory quota key only,
never stored or logged. Nginx's own `limit_req` zones still key on the Cloudflare egress address.

## Offline review

Run from the repository root; no command here provisions infrastructure:

```sh
terraform -chdir=infra/pilot fmt -check
terraform -chdir=infra/pilot init -backend=false
terraform -chdir=infra/pilot validate
node --test deploy/cloudflare/worker.test.mjs
python3 -m unittest discover -s deploy/pilot -p 'test_*.py'
mvn -f backend/pom.xml -DskipTests package
docker build -f backend/Dockerfile.pilot -t otdog-pilot-backend:local backend
docker build -f backend/Dockerfile.parser --target release -t otdog-pilot-parser:local backend
```

`init` downloads public providers but does not contact cloud accounts. For containerized
Terraform use `hashicorp/terraform:1.9.8`, mounting only `infra/pilot` as `/work`.
Review Nginx with synthetic TLS/secret fixtures before rollout; never run `nginx -T` against
the real configuration because it prints the origin secret. Do not print `docker compose
config`, inspect container environments or enable shell tracing with real runtime material.

## Operator provisioning and release

1. Finish ticket 93. Review AWS/Cloudflare processor terms, actual region and privacy copy.
   Set monitored AWS budgets ($50/$100) and alert/acknowledgment targets. Costs have not been
   measured; EBS, public IPv4, RDS storage/backups, registry, transfer and Cloudflare Worker usage
   add to instance costs. Validate 4 GiB capacity under representative concurrent uploads.
2. Supply ignored `infra/pilot/operator.tfvars` with the AMI, globally unique bucket name,
   reviewed region, all [current Cloudflare IPv4 ranges](https://www.cloudflare.com/ips-v4),
   exact runtime parameter ARN and customer KMS key ARN. Create that key and SecureString
   through the company secret-management process, outside Terraform state. Use an advanced
   SSM SecureString (8 KiB capacity); confirm runtime JSON including PEM material fits.
   Review a saved Terraform plan, then the operator applies it. No apply has been performed.
3. SES identity must be verified before notification-topic configuration can succeed: bootstrap
   only `aws_ses_domain_identity.company` and `aws_ses_domain_dkim.company` with a reviewed
   targeted plan, publish the returned verification/DKIM records and wait for verification;
   then review and apply a full plan. Record outputs privately. Publish custom MAIL FROM/SPF/DMARC
   per ticket 97 and obtain production access. Publish an origin DNS-only A record to the EIP,
   obtain/renew its DNS-01 public certificate using a narrowly scoped DNS credential held outside
   this instance, set Cloudflare TLS to Full (strict), and keep origin port 80 closed.
4. Through SSM, install reviewed Docker Engine/Compose >=2.30, AWS CLI v2, Python 3, SSM agent,
   and Docker rootless prerequisites on Ubuntu 24.04. Install the release checkout under
   `/opt/otdog` without `.git`, development secrets or credential files. Keep host updates and
   Docker/AppArmor/default seccomp enabled. There is no secret-bearing EC2 user data.
5. Create a dedicated host user `parser-runtime` UID/GID 10001 with a private home and subordinate
   UID/GID ranges. Enable linger and install rootless Docker for that user; its socket must be
   `/run/user/10001/docker.sock`. Delegate cgroup v2 CPU/memory/PID controllers to its user
   service and verify limits actually apply. This daemon owns parser images only, has no cloud
   credentials or other workloads, and is not the host's privileged Docker daemon. Set an
   aggregate 1600 MiB memory ceiling on its user service/slice. Failure to enforce quotas blocks
   rollout. The app mounts only this dedicated socket. Never substitute `/var/run/docker.sock`.
   Backend (640 MiB), ClamAV (1280 MiB), ingress (64 MiB) and that parser ceiling total
   3584 MiB, leaving 512 MiB for the OS on a 4 GiB host. These are initial limits, not a measured
   capacity claim; ClamAV signature growth and representative parser load may require resizing.
6. Configure the GitHub `pilot` environment with required operator review and restricted release
   branches. Supply Terraform's optional `github_repository` and `github_oidc_provider_arn`
   (the company GitHub OIDC provider must already exist), then review/apply the build role.
   Its trust is restricted by repository, `environment:pilot` subject and
   `sts.amazonaws.com` audience, permitting push only to the Terraform ECR repository plus
   registry authorization. Store its ARN as `PILOT_IMAGE_BUILD_ROLE`; set `PILOT_REGION` and
   `PILOT_IMAGE_REPOSITORY`. Both new workflows are **workflow_dispatch only**; existing CI
   is unchanged. Dispatch image build after integrated CI passes. Scan both images and the
   chosen ClamAV/Nginx digests under the hardening policy before rollout. No image was published.
7. Pull immutable amd64 image digests on the host. Load the parser image into the dedicated
   rootless daemon and obtain its local immutable `sha256:…` image ID there (not the registry
   manifest digest). Record matching app/parser release commits. In `/etc/otdog-release.env`
   put nonsecret `BACKEND_IMAGE`, `PARSER_IMAGE_ID`, `CLAMAV_IMAGE`, `NGINX_IMAGE`, `AWS_REGION`
   and `RUNTIME_PARAMETER_NAME`. Use tested official `clamav/clamav` and `nginx` release digests,
   not moving tags. Pre-fetch fresh ClamAV databases and verify loopback PING and a malware
   rejection; no app startup or enrollment until its daemon is ready.
8. Store a JSON object of string values in the advanced SSM parameter. Required entries:
   `ORIGIN_SECRET` (32 random bytes as 64 lowercase hex), `TLS_CERTIFICATE`, `TLS_PRIVATE_KEY`,
   `DATABASE_URL` (JDBC with `sslmode=verify-full&sslrootcert=/etc/ssl/rds-ca.pem`),
   `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `CASE_ACCESS_PEPPER`, the full document key ring
   and active ID, `DOCUMENT_STORAGE_S3_BUCKET`, `DOCUMENT_STORAGE_S3_REGION`,
   `CASE_RECOVERY_PUBLIC_URL=https://otdog.com/`, `CASE_RECOVERY_FROM`, `SES_REGION`,
   `SES_SMTP_HOST`, `SES_SMTP_USERNAME`, `SES_SMTP_PASSWORD`, and exact `SES_SNS_TOPIC_ARNS`.
   Include approved evidence budget and real provider settings. Register passkey RP ID
   `otdog.com` and origin `https://otdog.com` using `ACCOUNT_PASSKEYS_RPID` and
   `ACCOUNT_PASSKEYS_ORIGINS` (Spring removes hyphens when mapping environment names).
   Preserve export/relay legacy keys as documented; no key is generated by Terraform.
   Install the reviewed current RDS CA bundle at `/etc/otdog/rds-ca.pem`; Compose mounts it
   read-only at that JDBC path. Certificates are public, keys are not.
9. `load-runtime.py REGION PARAMETER_NAME`, run as root, fetches using the instance role and
   renders root-only files under RAM-backed `/run/otdog-pilot`. Confirm `/run` is tmpfs and
   swap is disabled; restrict Docker/systemd administrative access. Docker retains environment
   metadata on encrypted EBS: only privileged operators can inspect it. Restart re-fetches the
   parameter via `otdog-pilot.service`; install/enable the supplied unit only after preparation.
   Compose explicitly enforces `SPRING_PROFILES_ACTIVE=pilot`, S3, ClamAV, parser container
   mode and disabled mailbox/relay. **Do not switch profiles or fake providers to clear a guard.**
10. Run Flyway ahead of traffic with the release jar and migrator credentials injected by the
    same secret-store process. Use an offline Flyway runner (see below), then runtime credentials
    and Hibernate validation. Start ClamAV, backend, then ingress while public enrollment is
    fenced. Current readiness, synthetic evidence/inventory/trade/payment adapters and other
    prerequisites still refuse pilot startup. Their owning tickets and approvals must land
    before this step can pass; infrastructure code does not make them production-ready.
11. Put `ORIGIN_URL` (HTTPS origin root URL) and matching `ORIGIN_SECRET` into Worker secrets
    through Cloudflare's secret UI/interactive Wrangler, never arguments or workflow logs.
    Set GitHub `CLOUDFLARE_API_TOKEN` (Worker deployment only) and `CLOUDFLARE_ACCOUNT_ID`.
    Disable zone cache rules that override API no-store and add a zone `/api/*` cache bypass.
    Dispatch the client workflow on the approved commit; it builds and deploys static assets.
    Subscribe both SNS topics to `https://otdog.com/api/v1/email/ses-notifications` with raw
    delivery off, after exact topic ARNs are loaded and the guarded backend is running. The
    Worker forwards signed SNS traffic through the same origin verification; no bypass route.
12. Complete every deployed check below, release-owner approval and readiness evidence before
    opening enrollment. Refresh Cloudflare CIDRs through reviewed Terraform changes as needed.

## Migrations and rollback

Fence traffic and stop application writers first. Snapshot RDS and copy encrypted objects with
a manifest/checksums into an independently protected backup; preserve the entire key ring and
independent deletion ledger outside that backup lineage. Use the shipped Flyway migrations in
the jar through a separately provisioned Flyway CLI/runner with the migrator user. Set locations
to the extracted `BOOT-INF/classes/db/migration` from that exact jar, run `validate`, `info`,
`migrate`, then `validate`; record versions/checksums and inspect locks. Use no `clean`, Hibernate
generation or startup bypass. A migration runner is an operator prerequisite, not installed by
Terraform. Spring keeps Flyway enabled as a second validation/no-pending-migration check; runtime
credentials must not need DDL after this pretraffic migration step. No migration is added here.

For application rollback, fence writes, stop backend and check the dedicated daemon has no
`document-parser-*` leftovers. Select the previous scanned backend digest and its matching parser
ID, verify schema compatibility (including migrations added by newer code), then restart and
repeat synthetic preview/report/deletion checks before opening ingress. Roll back the client
to a recorded previous Worker version with its matching asset bundle using Cloudflare version
rollback; confirm secrets/headers/API routing afterward. Never roll back the database blindly.
An incompatible schema requires a forward repair or an isolated restore, not down migrations.

## Backup restore and rotation

RDS daily backups alone do not back up documents. With all writers fenced/stopped, take an
RDS snapshot and independently encrypted S3 object copy with a DB/object/key/ledger cutoff and
manifest. Restrict the backup role separately from the application role; declare and measure
expiry and deletion obligations for these copies. Preserve the latest deletion ledger separately.
Restore RDS into new private subnets/SG and objects into a new private, unversioned bucket;
point an isolated runtime at them with compatible keys. Mount the latest protected ledger and
set restore mode plus both analysis/document workers disabled as in the
[quarantined restoration procedure](hardening-runbook.md#quarantined-restoration).
Reconcile pending and post-backup deletions, including orphaned objects. Verify retained synthetic
reads, denied deleted cases, Flyway/Hibernate checks and absent erased artifacts. Record measured
RPO/RTO, counts and cutoffs; keep ingress fenced on unknown ledger freshness or missing keys.
Terraform does not automate backup copies/expiry or certify physical deletion.

Rotate SMTP/DB credentials through new secret-store versions, re-render and **recreate** containers
(a restart alone keeps old environment values), verify delivery/DB connection then revoke old
credentials. Stop ingress for origin-secret rotation, update SSM and Worker secrets together,
recreate Nginx/Worker and verify old/missing secrets refused before reopening. Renew TLS before
expiry through the same parameter flow; recreate ingress to pick up replaced bind-mount files.
Document key rotation follows the offline runbook, retaining old generations for backups.
Pepper rotation needs the existing coordinated lookup/suppression migration; never simply change
it. Rotate GitHub deployment tokens and OIDC trust independently, with operator review.

## Deployed evidence checklist — all PENDING

Record date, release commit/image digests, actual regions, tester and safe outcomes in the
private release record; omit secrets, account IDs, recipients, link tokens and document content.

- Confirm HTTPS redirect at `http://otdog.com`, valid edge/origin TLS, one-year HSTS and exact
  canonical client headers on `/`, an asset and a SPA deep link. Check framing refusal.
- Create a synthetic case; upload, preview a normalized page, verify terms, save/reopen a report,
  export and delete. Require no CSP violations in both themes, no CORS and a secure account cookie.
- Confirm every API outcome (including 4xx/5xx and exports) is no-store; repeat uploads and reads
  and check no Cloudflare cache hit. Verify an attacker-supplied origin header gets overwritten.
- From an unrelated network, origin TCP443 must be blocked by SG. From the origin host or a
  controlled allowed Cloudflare test, missing/wrong verification header must return 403 and
  the correct header must reach Spring; app 8080 and scanner 3310 must be loopback only.
- Check the zone plan and Network **Maximum Upload Size** setting. The application accepts
  **20 MiB files**, **21 MiB multipart requests**. Published Cloudflare Free/Pro default is
  **100 MB**, larger than both, but a zone can lower it. Record the actual configured value,
  successful 20 MiB synthetic upload with multipart overhead, expected rejection over 20 MiB
  and scanner/ingress timeouts. An unconfigured account's actual limit is not claimed.
- Check all startup guards, ClamAV outage/recovery and malware refusal; run the parser
  confinement escape/timeout/removal drills against the dedicated daemon and immutable image.
  Verify cgroup quotas and memory/CPU/load on the 4 GiB host; resize through reviewed IaC if needed.
- Inspect S3 role isolation, all public blocks, bucket policy/ACL, no versioning/replication/
  Object Lock/access-point exposure and region. Measure physical object absence after deletion
  and backup expiry; execute isolated restore and compatible rollback drills.
- Verify SES domain/production access, both SNS subscriptions, delivered sign-in and simulator
  bounce/complaint suppression per ticket 97. Test monitored alerts and acknowledgment.
- Record migration versions/checksums, Hibernate validation, provider readiness and all pilot
  prerequisite approvals. These deployment checks cannot approve provider terms by themselves.

Sources checked for this design: [Cloudflare static asset routing](https://developers.cloudflare.com/workers/static-assets/binding/),
[Cloudflare upload limits](https://developers.cloudflare.com/support/troubleshooting/http-status-codes/4xx-client-error/error-413/),
and [AWS container IMDS hop-limit guidance](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/configuring-IMDS-new-instances.html).
