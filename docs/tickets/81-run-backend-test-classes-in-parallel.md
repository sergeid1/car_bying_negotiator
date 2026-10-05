# 81 — Run backend test classes in parallel

**What to build:** The full backend suite finishes much faster without losing any isolation. `mvn clean verify` takes about seven minutes and saturates the machine, mostly because about 96 acceptance tests each start the whole application through `HttpAcceptanceHarness`, one after another. That cost slows every ticket and is why parallel workers must take turns running the full suite. Found in the 2026-10-05 codebase security review.

**Blocked by:** none.

**Status:** ready-for-agent — filed on 2026-10-05 from a codebase security review.

- [ ] The baseline is measured first: wall-clock time for `mvn clean verify` and the ten slowest test classes, recorded in the status line.
- [ ] Test classes run in parallel forks, with a fork count that both the GitHub Actions runner and a developer laptop can sustain.
- [ ] Every test that starts the application gets its own HTTP port, database, document storage path and temporary directory, so parallel forks cannot see each other's state. Tests that cannot be isolated are marked to run serially, each with a stated reason.
- [ ] The PostgreSQL hardening tests, the container parser integration test and the document performance test keep their current behaviour in CI.
- [ ] The full suite passes ten consecutive times locally with parallel forks, with no new flaky test, and the after-change timings are recorded next to the baseline.
- [ ] `AGENTS.md` guidance on taking turns with the full suite is updated if the new timing makes it unnecessary.
