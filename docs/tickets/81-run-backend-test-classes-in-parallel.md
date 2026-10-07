# 81 — Run backend test classes in parallel

**What to build:** The full backend suite finishes much faster without losing any isolation. `mvn clean verify` takes about seven minutes and saturates the machine, mostly because about 96 acceptance tests each start the whole application through `HttpAcceptanceHarness`, one after another. That cost slows every ticket and is why parallel workers must take turns running the full suite. Found in the 2026-10-05 codebase security review.

**Blocked by:** none.

**Status:** complete — as of 2026-10-07. Surefire runs ordinary test classes in two reused JVMs (`test.forkCount`
2, `test.heapSize` 2 GiB each), with per-fork `java.io.tmpdir` and document storage under `target/test-forks/<n>/`;
`HttpAcceptanceHarness` gives each application its own storage path beside its random port and file database
(`HttpAcceptanceHarnessIsolationTest`). `PersistenceHardeningTest`, `SchemaAndOrmEvidenceTest` and
`DocumentPerformanceAcceptanceTest` are tagged `serial` and run afterwards in one fork (shared PostgreSQL; latency
measurement); Failsafe stays single-fork. Each test JVM writes a GC log to `target/surefire-reports/gc-<pid>.log`. Acceptance applications bind and are called on
`127.0.0.1`, as is the ClamAV stub, because with a wildcard bind another local process that binds 127.0.0.1 on the
same port number receives the connection (seen once as a foreign 401 in `PilotFeedbackAcceptanceTest`).
Baseline `mvn clean verify` on 0a0effd: 8:49 locally on a quiet machine, 9:31 in CI; slowest classes
DocumentPerformance 52 s, DealerDocument 46 s, DocumentExtraction 32 s, BuyerAccount 32 s, VehicleEvidence 24 s,
CaseDeletion 21 s, BuyingCaseIntake 21 s, PilotFeedback 20 s, EphemeralBuyingCase 18 s, SyntheticCashDeal 18 s.
After: ten consecutive local passes, median 5:09.5 (5:09–5:11); CI 8:03. A first version with a fresh 512 MiB JVM
per class took 7:04 locally but 27:36 in CI (cold JVM starts), and reused 512 MiB JVMs took 13:27 in CI (GC pressure);
model-client tests now allow a 10 s budget for the first call in a cold JVM. Open: a reused 512 MiB fork once exited
with code 0 during datasource shutdown in ShortlistAcceptanceTest; normal-order and random-order replays did not
reproduce it and its cause is unknown (if it recurs, check `*.dumpstream` for Surefire's self-kill and the GC logs for
a long pause). Fork directories are prepared for counts 1 and 2 only. `RequestEmailLimitAcceptanceTest` allows a
Retry-After of the 900 s window plus the measured burst skew, because the quota service can answer 901 under a
concurrent burst.

- [x] The baseline is measured first: wall-clock time for `mvn clean verify` and the ten slowest test classes, recorded in the status line.
- [x] Test classes run in parallel forks, with a fork count that both the GitHub Actions runner and a developer laptop can sustain.
- [x] Every test that starts the application gets its own HTTP port, database, document storage path and temporary directory, so parallel forks cannot see each other's state. Tests that cannot be isolated are marked to run serially, each with a stated reason.
- [x] The PostgreSQL hardening tests, the container parser integration test and the document performance test keep their current behaviour in CI.
- [x] The full suite passes ten consecutive times locally with parallel forks, with no new flaky test, and the after-change timings are recorded next to the baseline.
- [x] `AGENTS.md` guidance on taking turns with the full suite is updated if the new timing makes it unnecessary.
