# Disposable document parsing

Upload inspection, normalized page rendering and PDF text extraction use
`DocumentParserProcess`. It starts a fresh `DocumentParserWorker` JVM for each request;
PDFBox and ImageIO never decode Buyer uploads in the application JVM. The worker does not
start Spring, connect to the database, or receive case credentials, storage keys or object
paths. Original bytes travel over stdin. Stdout is a bounded binary response; parser logs
and exceptions are not returned to Buyers or copied into application diagnostics.

The application persists outputs only after a complete response and successful child exit.
Rendered pages pass byte-length, PNG-header and dimension checks without decoding in the
application. Normalization retains its existing cleanup/retry behavior: a failed page cannot
advance the document to extraction-ready, and partial encrypted outputs are removed. Text
extraction must return exactly the persisted page count. The source check in
`scripts/security-rules.yml` prevents direct parsing outside the worker implementation.

## Resource limits

| Resource | Default and maximum |
| --- | --- |
| Worker wall time | 20 seconds per inspection, rendered page or whole-document text request; configurable to at most 60 seconds |
| Worker Java heap | 512 MiB; configurable 64–1024 MiB |
| Concurrent worker requests | 2 per application process; configurable 1–8; excess calls fail immediately |
| Other JVM allocations | Direct buffers 32 MiB, metaspace 128 MiB, code cache 64 MiB; these are not a total RSS cap |
| Original bytes | 20 MiB maximum; configurable lower |
| Pages | 25 maximum; configurable lower |
| Image/page pixels | 40 million maximum; configurable lower |
| PDF decompressed page content | Smaller of 64 MiB and original bytes multiplied by the configured expansion ratio |
| Expansion ratio | 10,000 maximum; configurable lower |
| Encoded normalized page | 64 MiB maximum |
| Extracted text | 100,000 characters per page; excess text fails rather than silently truncating evidence |

Configure `DOCUMENT_PARSER_TIMEOUT`, `DOCUMENT_PARSER_HEAP_MB` and
`DOCUMENT_PARSER_MAX_CONCURRENT` for worker limits. Existing file/page/pixel/expansion
settings still apply, with the ceilings above enforced at startup. Rendering DPI is 36–300
(default 144). The existing `DOCUMENT_DECODER_TIMEOUT` limits HEIC conversion (default
10 seconds, maximum 60 seconds), also subject to the enclosing worker deadline.

The application clears the child environment, including Java option injection variables.
Each child gets a private temporary directory for font/native decoder scratch files, which
is removed on success or failure. Heap dumps and native core dumps are disabled by default;
JVM crash logs stay in the temporary directory. The application kills the worker and known
descendants on timeout or shutdown. Failed startup/capacity returns a retryable 503; malformed
input, excessive expansion and parser deadlines return a safe rejection. No in-process
fallback bypasses these limits.

## Confined execution for the pilot

The `pilot` Spring profile refuses to start in local process mode. Configure a host-run
application with a dedicated local container daemon and an already-built immutable parser image:

```sh
docker build -f backend/Dockerfile.parser -t deal-analyzer-parser:release backend
docker image inspect deal-analyzer-parser:release --format '{{.Id}}'
```

Set `SPRING_PROFILES_ACTIVE=pilot`, `DOCUMENT_PARSER_MODE=container`,
`DOCUMENT_PARSER_CONTAINER_IMAGE` to that `sha256:...` image ID,
`DOCUMENT_PARSER_CONTAINER_EXECUTABLE` to the absolute Docker CLI path, and
`DOCUMENT_PARSER_CONTAINER_ENDPOINT` to the local `unix:///...` daemon socket.
The launcher never pulls an image or falls back to a local JVM. Use a dedicated rootless
runtime owned by the service user where available; daemon access is trusted control-plane
access. Do not mount a privileged host Docker socket into the application container.
The supplied application Dockerfile does not provision a parser control plane: this mode
currently supports the host-run application with a local daemon. A different deployment
must provide and test an equivalent boundary before pilot use.

Every request gets a fresh container with no network, no host mounts, UID/GID 10001,
a read-only root filesystem, all capabilities dropped and no-new-privileges. Limits are
1536 MiB total memory including native processes, no swap, one CPU, 64 processes/threads,
128 MiB temporary space, 1 MiB shared memory and 128 open files per process. Native HEIC
conversion runs inside that same boundary. The release parser image includes `heif-dec`
and its HEVC decoding plugin; no application keys, database credentials, case storage,
Docker socket or environment are passed into it. The image contains trusted application
code and dependencies, not live storage. JVM heap limits still apply within the total cap.

At the default concurrency of two, this allows at most 3 GiB parser memory and two CPUs
per application instance. Budget the application and every replica separately. These
are hard upper limits, not a throughput guarantee. Docker resource-limit behavior follows
[the runtime documentation](https://docs.docker.com/engine/containers/resource_constraints/).

The application force-removes the named container on every exit and verifies its absence.
If cleanup cannot be verified, that concurrency slot remains unavailable until restart
rather than admitting more work. An independent in-container wall timer kills the worker
and native children even if the application crashes. After a daemon outage, verify that
no `document-parser-*` containers remain before restarting the application; test daemon
and host crash recovery in the eventual deployment. Container startup time counts against
the request deadline. Logs are disabled for these containers.

## Executable evidence

`DocumentParserProcessTest` exercises compressed PDF content, child heap exhaustion,
an endless native decoder, saturation, environment isolation and recovery.
`DocumentParserPackagingIT` checks the executable Boot JAR. `ContainerParserIT` exercises
inspection, rendering, text extraction, real synthetic HEIC decoding, invalid-input recovery,
native timeout cleanup and subsequent processing through the application dispatcher.
Its test-only native decoder inspects cgroup limits, verifies no network route, capabilities
or application storage, rejects root-filesystem writes, and exhausts temporary space to
prove the quota applies to native output. The probe is absent from the release image.

CI builds both release and probe images and supplies their immutable IDs to the integration
suite. These container tests are intentionally skipped in ordinary `mvn verify` without
image properties; the separate CI container gate must pass. Trivy scans both application
and parser release images. The 2×2 HEIC fixture was generated from a synthetic blank PNG
with libheif 1.21.2/x265; it contains no Buyer data.

Local `process` mode remains useful for development and unit tests. It is a failure barrier,
not an OS security sandbox, and must not be used for pilot uploads.
