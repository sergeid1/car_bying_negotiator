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

**Status:** complete — migration V47, as of 2026-10-07. V47 adds `buyer_accounts.mailbox_generation`, which starting
a connection and disconnecting both increment while clearing pending authorizations; `MailboxConnections.complete`
refuses a stale generation at persistence and withdraws the newly exchanged grant if persistence fails. Replacing a
connection withdraws the previous grant through the previous connection's provider. A send rechecks, under the account
and case locks immediately before the provider call, that the same connection still exists and that the owner still
permits it (mandate, appointment contact release); disconnect, replacement and account deletion take the same locks,
so they win before dispatch and a dispatched call finishes first and cannot be recalled. `ConnectedMailboxAcceptanceTest`
covers these races with latches. Open: the provider call runs inside that transaction, so a slow provider holds a
database connection and the account lock for up to its 25-second deadline; revisit before high send volume. Grant
withdrawal stays best effort; live provider behaviour is ticket 92.

- [x] Disconnect invalidates outstanding authorizations; a callback begun before disconnect cannot
  silently recreate a connection, including when the token exchange is already in progress.
- [x] Preserve the previous provider with its refresh token when replacing a connection; test both
  Gmail-to-Outlook and Outlook-to-Gmail withdrawal through the correct adapter.
- [x] Withdraw newly exchanged grants if the account was deleted/disconnected or the authorization
  was superseded before persistence; keep local token deletion authoritative when withdrawal fails.
- [x] Define the send/disconnect/deletion ordering and point after which a provider call cannot be
  recalled. Test disconnect while refreshing and after an access token has been obtained, and ensure
  sends initiated after the fence cannot use it.
- [x] Exercise mandate revocation and appointment contact-release changes during a claimed send;
  recheck at the defined dispatch boundary and document already-dispatched behavior precisely.
- [x] Use barriers/latches to cover these races; verify connection/authorization rows, grant withdrawal,
  actual provider calls, audit and Buyer-visible outcomes without leaking credentials or contact values.
- [x] Update the threat model and account/outbound policies to match the tested lifecycle semantics.
