# AppSync legacy field boundary

Updated: 2026-09-17. This is a v1/v2 compatibility checkpoint, not the final v3 registry.

`AppSyncLegacyFieldRegistry` explicitly lists the fields used by the 19 existing domains.
`AppSyncPortabilityPolicy` denies undeclared domain/field combinations, including familiar
field names used in the wrong domain. Setting keys still require their separate explicit
allowlist. No prefix grants permission to a future field or domain.

## Current behavior

| Boundary | Implemented behavior |
| --- | --- |
| Single, batch, command recording | Keep local mutation callbacks; omit unknown domains/local-only settings; remove unknown fields. Within the store transaction, compare patches against entity-scoped state using canonical scalar equality; omit unchanged fields and do not allocate a sequence for a no-op. Preserve v2-required fields when a real change remains. |
| Authorized delete batches | Filter excluded entities before counting/signing the portable batch; preserve all three v2 proof fields. |
| Legacy recovery classification | Use the same field decisions; preserve raw source evidence until the existing verified activation protocol permits supersession. |
| Reduction | Sanitize incoming and base-state fields and winning operations; a valid empty patch is consumed without creating an unmaterializable empty entity. Unknown domains remain unsupported. |
| Checkpoint construction | Remove unknown domains/fields and excluded setting projections, including copies inside provenance; reject callers bypassing projection sanitization. |
| Ordinary local data/backups | No field removal by this boundary. Cover data and unknown local preferences remain subject to existing local-backup policy. |

## Compatibility retained deliberately

- Entity identity fields (`targetType`, `targetId`, `authorId`, etc.) remain in v2 bodies.
- Parent labels and presentation fields (`subscriptionTitle`, `subscriptionQuery`, `forumName`,
  `postTitle`, etc.) remain declared until reconstruction/default adapters are implemented.
- Cover fields are explicitly excluded even for remote URLs. V2 Put encoders may still emit
  required null cover keys for older readers, never image content.
- `appsyncBulkDeleteScope`, `appsyncBulkDeleteCount`, and `appsyncBulkDeleteExpiresAt` remain
  embedded v2 proof fields. The registry must not strip them before a shared batch-proof adapter
  replaces that representation.
- Ordinary newly recorded Delete operations carry no stale entity fields. Authorized deletes
  carry only their v2 proof. RelationRemove retains only the legacy contract's identity fields
  (plus authorization proof if supplied), pending structured-key decoding on older clients.
- History patches for tag catalog and both RSS histories retain their identity and lastVisitTime;
  RSS subscription patches retain updatedAt. Older readers validate these even when unchanged.
- Repeated targets inside one batch use transaction-local reducer state with the actual
  prospective operation identity/context. Only retained operations consume sequences. This
  preserves A-to-B-to-A changes and the existing monotonic, tombstone, generation, and invalid
  operation semantics. Puts are retained; patches do not compare against another generation.
  Preparation does not persist intermediate projections and is discarded after the command.
- A setting no-op preserves its winner and timestamp while still updating the local store.
  Equivalent numeric spellings (including signed zero) do not mark it as awaiting bootstrap.

These compatibility declarations do **not** classify all retained fields as Essential for v3.
The six-class classification, numeric IDs, types, normalization, title budgets, derived/parent
field removal and v3-only minimal bodies remain separate unfinished work.

## Limits and next gates

- This does not rebuild every already-persisted projection or physically prune raw journals.
  Retained source evidence, carry-forward mappings, and recovery envelopes remain protected by
  the checkpoint/index coverage requirements.
- The journal encoder is not yet a final fail-closed boundary. Historical raw journal writes
  and null compensation patches need an explicit compatibility treatment there.
- Redacted exclusion diagnostics and comprehensive budget enforcement remain pending.
- Contract coverage tests cover every registered domain and contract field; mutation routing,
  snapshot/checkpoint, lifecycle, and engine-convergence suites exercise production producers.
  Optional fields emitted by new producers must be explicitly reviewed and added to this list;
  complete producer/field classification and cross-platform acceptance are not claimed.

Regression fixtures now use real domain fields rather than synthetic `settings.title`,
`settings.note`, or `reading.thread.position`. A category filter containing both a stable ID
and a local-only ID drops the local ID; one missing its required stable ID is still quarantined.
