# 90 — Fence mailbox disconnect and grant replacement

**What to build:** Define and enforce the Mailbox Connection lifecycle when OAuth completion,
disconnect, replacement, account deletion or sending overlap. `MailboxConnections.disconnect`
deletes the current connection but leaves `mailbox_authorizations`; an outstanding callback can
create another connection. `complete` exchanges a grant outside the account transaction and
revokes a previous refresh token using `pending.provider()` rather than the previous connection's
provider. `MailboxSendService` obtains a decrypted sendable token outside the case lock before
calling the provider. Sequential fake-adapter checks in `ConnectedMailboxAcceptanceTest` do not
prove revocation fences at these boundaries. Found in ticket 78's 2026-10-07 documentation review.

**Blocked by:** None; use deterministic fake providers and controlled concurrency first.

**Status:** ready-for-agent — lifecycle gap, including incorrect cross-provider grant revocation.

- [ ] Disconnect invalidates outstanding authorizations; a callback begun before disconnect cannot
  silently recreate a connection, including when the token exchange is already in progress.
- [ ] Preserve the previous provider with its refresh token when replacing a connection; test both
  Gmail-to-Outlook and Outlook-to-Gmail withdrawal through the correct adapter.
- [ ] Withdraw newly exchanged grants if the account was deleted/disconnected or the authorization
  was superseded before persistence; keep local token deletion authoritative when withdrawal fails.
- [ ] Define the send/disconnect/deletion ordering and point after which a provider call cannot be
  recalled. Test disconnect while refreshing and after an access token has been obtained, and ensure
  sends initiated after the fence cannot use it.
- [ ] Exercise mandate revocation and appointment contact-release changes during a claimed send;
  recheck at the defined dispatch boundary and document already-dispatched behavior precisely.
- [ ] Use barriers/latches to cover these races; verify connection/authorization rows, grant withdrawal,
  actual provider calls, audit and Buyer-visible outcomes without leaking credentials or contact values.
- [ ] Update the threat model and account/outbound policies to match the tested lifecycle semantics.
