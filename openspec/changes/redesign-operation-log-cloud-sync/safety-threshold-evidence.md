# Destructive-operation threshold evidence

## Evidence boundary

The feature flag remains disabled by default. The cleared Pixel 8 fixture contains zero
rows in all destructive domains, so it is not used as a population claim. Current
thresholds are based on the product command contract and deterministic boundary
fixtures:

- normal single-item delete commands emit one tombstone;
- every intentional bulk command records one durable authorization and embeds the
  same scope, count, and expiry proof in every delete operation;
- an unproved remote batch is therefore anomalous, but accumulated offline
  single-item deletes must retain a generous automatic path;
- quarantine requires exceeding both the absolute limit and 20% of the receiving
  domain, so a small domain is not stopped by percentage alone and a large domain is
  not stopped by count alone.

## Initial thresholds

| Domain | Absolute count | Percentage | Boundary rationale |
|---|---:|---:|---|
| settings | 20 | 20% | settings are normally changed or removed one key at a time |
| favorite.item | 50 | 20% | allows a large offline cleanup without proof |
| favorite.category | 20 | 20% | container deletion is normally explicit and small |
| favorite.collection | 30 | 20% | container deletion is normally explicit and small |
| detail-note | 50 | 20% | allows accumulated individual note removals |
| bookmark | 50 | 20% | allows accumulated individual bookmark removals |
| reading.thread | 100 | 20% | history cleanup can legitimately accumulate quickly |
| reading.image | 100 | 20% | history cleanup can legitimately accumulate quickly |
| reading.tag-manga | 100 | 20% | history cleanup can legitimately accumulate quickly |
| reading.time | 100 | 20% | date-bucket cleanup can span many records |

`BulkDeleteGuardTest.everyConfiguredDomainUsesBothAbsoluteAndPercentageBoundary`
proves each configured domain accepts the exact boundary and quarantines an
unauthorized batch only when both limits are exceeded. Portable-proof tests prove a
confirmed bulk command remains automatic on another device after delayed delivery.

These are conservative pre-rollout thresholds, not measured user-distribution
percentiles. Reliability telemetry must report quarantine frequency before the
feature flag can default to enabled; lowering a threshold must not be done from one
account or one emulator sample.
