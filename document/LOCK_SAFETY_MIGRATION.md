# Lock safety migration plan

## Current guard and limitations

The core executor rejects conflicting lock storage strategies for the same qualified resource key **within one JVM**. The guard is deliberately sticky for the JVM lifetime to avoid an unsafe switch after a lease is released. It is not distributed coordination, and it does not protect independent application instances, rolling deployments, or processes using different backends. Do not treat this as cross-backend mutual exclusion.

For production, assign exactly one authoritative storage backend per lock domain and enforce that policy consistently across all deployments. Migrate domains with a controlled cutover and no overlapping writers. Do not switch backends per request for the same domain.

## P0 follow-up

1. Design a durable lock-domain-to-backend registry or fail-closed configuration validation across deployments.
2. Change acquisition results to expose a monotonic fencing token. The protected downstream resource must reject stale tokens atomically.
3. Propagate lease loss as a lock-session state; never rely on watchdog renewal alone to guarantee write safety.
4. Test paused holder / lease expiry / new holder / resumed stale writer against the actual protected resource.

## P1 follow-up

- Validate the DB lease algorithm against real MySQL transaction isolation and server time.
- Add multi-key renewal and partial-acquisition fault injection.
- Manage watchdog executor shutdown through Spring lifecycle.
- Document key canonicalization and namespace compatibility.

## Validation

Run `mvn --batch-mode --no-transfer-progress clean verify` and perform Redis/MySQL integration tests before merging. This branch has not been locally compiled or tested by the authoring assistant.
