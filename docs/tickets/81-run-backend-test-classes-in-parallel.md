# 81 — Run backend test classes in parallel

**What to build:** The full backend suite finishes much faster without losing any isolation. `mvn clean verify` takes about seven minutes and saturates the machine, mostly because about 96 acceptance tests each start the whole application through `HttpAcceptanceHarness`, one after another. That cost slows every ticket and is why parallel workers must take turns running the full suite. Found in the 2026-10-05 codebase security review.

**Blocked by:** none.

**Status:** complete — as of 2026-10-06. Surefire runs ordinary test classes in two fresh JVMs at a time
(`test.forkCount`, 512 MiB heap each), with per-fork `java.io.tmpdir` and document storage under `target/test-forks/<n>/`;
`HttpAcceptanceHarness` gives each application its own storage path beside its random port and file database
(`HttpAcceptanceHarnessIsolationTest`). `PersistenceHardeningTest`, `SchemaAndOrmEvidenceTest` and
`DocumentPerformanceAcceptanceTest` are tagged `serial` and run afterwards in one fork (shared PostgreSQL; latency
measurement); Failsafe stays single-fork. Baseline `mvn clean verify` on 0a0effd: 8:49 on a quiet machine; slowest
classes DocumentPerformance 52 s, DealerDocument 46 s, DocumentExtraction 32 s, BuyerAccount 32 s, VehicleEvidence 24 s,
CaseDeletion 21 s, BuyingCaseIntake 21 s, PilotFeedback 20 s, EphemeralBuyingCase 18 s, SyntheticCashDeal 18 s. After:
ten consecutive passing runs, median 7:02.5 (6:56–7:08), about 20% faster; 7:25 on the integrated branch with ticket 83.
Open: reused forks were faster (about 5:10) but one fork exited unexplained during datasource shutdown, so forks are
fresh per class; fork directories are prepared for counts 1 and 2 only; `RequestEmailLimitAcceptanceTest` now allows
a Retry-After of the 900 s window plus the measured burst skew, because the quota service can answer 901 under a
concurrent burst; the CI PostgreSQL and confined-parser steps have not yet run with this configuration.

- [x] The baseline is measured first: wall-clock time for `mvn clean verify` and the ten slowest test classes, recorded in the status line.
- [x] Test classes run in parallel forks, with a fork count that both the GitHub Actions runner and a developer laptop can sustain.
- [x] Every test that starts the application gets its own HTTP port, database, document storage path and temporary directory, so parallel forks cannot see each other's state. Tests that cannot be isolated are marked to run serially, each with a stated reason.
- [x] The PostgreSQL hardening tests, the container parser integration test and the document performance test keep their current behaviour in CI.
- [x] The full suite passes ten consecutive times locally with parallel forks, with no new flaky test, and the after-change timings are recorded next to the baseline.
- [x] `AGENTS.md` guidance on taking turns with the full suite is updated if the new timing makes it unnecessary.
