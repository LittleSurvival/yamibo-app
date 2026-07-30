## ADDED Requirements

### Requirement: Causal operation comparison
The system SHALL compare operations using observed per-device-epoch high-watermarks. It SHALL classify operations as causally ordered or concurrent without relying on device wall-clock time.

#### Scenario: Later operation observed earlier operation
- **WHEN** operation B's causal context includes operation A
- **THEN** B is treated as causally later than A

#### Scenario: Devices edit offline
- **WHEN** neither operation's causal context includes the other
- **THEN** the operations are classified as concurrent regardless of their timestamps

#### Scenario: Device clock is incorrect
- **WHEN** a device clock is far ahead or behind
- **THEN** its timestamp does not independently make its operation win or permit deletion

### Requirement: Central sync domain contracts
The system SHALL require every syncable domain to register stable identity, operation schema, reducer, validation, conflict policy, deletion authority, and policy version in one domain registry. Unsupported domains or policy versions SHALL fail closed.

#### Scenario: New setting is made syncable
- **WHEN** a developer adds a syncable setting without a complete domain contract
- **THEN** registry validation or contract coverage tests fail

#### Scenario: Unsupported remote domain arrives
- **WHEN** a valid envelope contains an operation for an unknown domain or policy version
- **THEN** that operation is quarantined and unrelated supported operations continue

### Requirement: Deterministic scalar resolution with retained history
For causally ordered scalar writes, the causal successor SHALL win. For truly concurrent writes to the same scalar field, every device SHALL select the same winner by stable operation-id ordering and SHALL retain the losing operation with its resolution metadata.

#### Scenario: Same setting changes concurrently
- **WHEN** two offline devices assign different values to the same setting key
- **THEN** both devices choose the same operation-id winner and retain the losing operation in conflict history without requesting user input

#### Scenario: Different fields change concurrently
- **WHEN** devices update different fields of the same entity
- **THEN** field-level reducers preserve both changes

### Requirement: Stable identity independent of display names
The system SHALL identify categories, collections, records, and relations with immutable sync ids or immutable natural keys. Mutable titles and names SHALL be synchronized fields and SHALL NOT be merge identity.

#### Scenario: Category is renamed on one device
- **WHEN** another device still has the old category name
- **THEN** both versions refer to the same category sync id and do not create a duplicate solely because names differ

#### Scenario: Two categories share a name
- **WHEN** a user creates distinct categories with identical display names
- **THEN** they remain separate entities with separate sync ids

### Requirement: Explicit tombstone deletion
The system SHALL represent record deletion and relationship removal only as explicit tombstone operations produced by authorized user actions. Absence from a snapshot or local database SHALL mean no opinion.

#### Scenario: Local database is recreated
- **WHEN** syncable tables are empty after recreation
- **THEN** no delete or relation-remove operation is generated

#### Scenario: Old put arrives after deletion
- **WHEN** an old operation predating an observed tombstone is replayed
- **THEN** it does not resurrect the deleted entity

#### Scenario: User intentionally recreates deleted content
- **WHEN** a user explicitly creates content after observing its tombstone
- **THEN** the system uses a new entity generation and synchronizes it as a new creation

### Requirement: Domain-specific concurrent policies
The initial registry SHALL use per-key registers for settings, per-field registers for favorite records/containers, remove-wins semantics for concurrent membership add/remove, and remove-wins semantics for concurrent record update/delete unless a domain explicitly defines safe recreation. Reading progress SHALL use a documented monotonic domain rule where valid and deterministic scalar resolution otherwise.

#### Scenario: Membership add and remove race
- **WHEN** an add and remove for the same item-container relation are truly concurrent
- **THEN** removal wins and the add remains visible in conflict history

#### Scenario: Favorite fields change independently
- **WHEN** one device changes a favorite note and another changes a separate favorite field
- **THEN** both field changes survive reduction

#### Scenario: Progress representations are not comparable
- **WHEN** concurrent reading-progress values cannot be safely ordered by the domain's monotonic rule
- **THEN** deterministic operation-id resolution applies and records the losing value

### Requirement: Invalid operation quarantine
The system SHALL preserve semantically invalid, unauthorized, or unsupported operations in typed quarantine without applying them and without blocking unrelated operations. Quarantine release SHALL rerun full validation and remain idempotent.

#### Scenario: Delete lacks user-action authority
- **WHEN** an incoming delete operation has no valid explicit-delete origin or authorization
- **THEN** it is quarantined and live data remains unchanged

#### Scenario: Quarantine is retried after upgrade
- **WHEN** a later app version supports the operation's schema and the user/account binding remains valid
- **THEN** the operation is revalidated and applied at most once
