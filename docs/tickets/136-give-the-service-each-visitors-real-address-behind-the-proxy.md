# 136 — Give the service each visitor's real address behind the proxy

**What to build:** Every request reaches the service through the Cloudflare Worker and the pilot nginx proxy, and both
clear forwarding headers so a client cannot forge its address (`server.forward-headers-strategy=none`). The service
therefore sees the proxy's address for every visitor. All visitors share one API peer quota, one sign-in and recovery
email quota, and, since ticket 120, one Toto client cap of ten questions a day for everyone without an account. Pass
the visitor's address from the edge in a way a client cannot forge, and use it for these per-client limits.

**Blocked by:** none. Toto stays off until tickets 118 and 122, but this must land before it is enabled.

**Status:** complete — 2026-10-16. No migration. The Worker sets `X-Otdog-Client-Address` from `CF-Connecting-IP`
after deleting any client copy; the pilot nginx passes it, and now `X-Origin-Verify`, only on verified requests.
`api.ClientAddress` is the one resolver for the API, request-email, Toto and password sign-in limits: it trusts the
header only with exactly one `X-Origin-Verify` matching `security.client-address.origin-secret` (`ORIGIN_SECRET`,
compared in constant time) and one literal address, keys IPv6 by /64, and otherwise uses the peer.
`ClientAddressProductionGuard` refuses a pilot or production start without the secret. Remaining limits: nginx's own
`limit_req` zones still key on Cloudflare's address, and visitors behind one NAT or one IPv6 /64 share a bucket.

## Scope

- The Worker sets one code-owned header from Cloudflare's own connecting address (for example `CF-Connecting-IP`),
  after deleting any client-supplied copy, next to the `X-Origin-Verify` secret it already sets.
- The pilot proxy passes that header only on requests it accepted from the Worker, and clears it otherwise, as it does
  for the forwarding headers today.
- The service reads the header only when the request carried a valid origin verification, and falls back to the peer
  address otherwise. Keep `server.forward-headers-strategy=none`; do not trust `X-Forwarded-For` or `Forwarded`.
- One resolver used by `ApiRateLimitFilter` (API and email quotas) and `TotoController`, so the limits cannot disagree.
- Consider IPv6: bucket by a /64 prefix so one host cannot rotate through its own block.
- Update `docs/hardening-threat-model.md` ("Proxy peers share a bucket") and ticket 120's known limits.
- The address stays a quota key only: never stored, logged or exported.

## Done when

- [x] Worker tests show a client-supplied copy of the header is replaced, and the header carries Cloudflare's address.
- [x] Acceptance tests show two visitors behind the proxy get separate quotas, a forged header without origin
      verification is ignored, and the Toto client cap counts per visitor.
- [x] No test or log line shows the address persisted or logged.
