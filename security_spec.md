# Hey Shuvo Security Specification (Phase 0 TDD)

## 1. Data Invariants
1. **Zero Private Data in Cloud**: Personal memories, chat transcripts, SMS/call counts, Tally Khata ledgers, and local documents NEVER enter Firestore; they remain strictly on-device in Room.
2. **Owner Supremacy & Anti-Escalation**: Only a registered administrator in `/admins/{uid}` or the immutable write-once `/system_bootstrap/master_owner` (`ownerUid == request.auth.uid`) may assign `accountRole == 'owner'`, approve/suspend accounts, issue invitations, manage skills, publish `/customer_configs/{configId}`, or write audit logs. No hardcoded personal emails appear anywhere in rules or client code.
3. **Self-Registration Guard**: Non-owner users creating `/accounts/{userId}` can ONLY set `accountRole == 'member'` and `accessStatus == 'pending'`, and `userId` MUST match `request.auth.uid`.
4. **Strict Multi-Tenant Query Isolation**: Non-owner members CANNOT list `/accounts`, `/invitations`, or `/audit_logs`. Non-owner members listing `/feature_requests` MUST filter `requesterUid == request.auth.uid`.
5. **Master-to-Customer Live Remote Config**: `/customer_configs/{configId}` is readable by signed-in Customer/Master clients for real-time sync, and writable strictly by `isOwnerAdmin()` with schema & field-whitelist validation.
6. **Immutable Audit Trail**: `/audit_logs/{logId}` and `/system_bootstrap/master_owner` are immutable (`allow update, delete: if false`).
7. **Temporal & Structural Integrity**: All timestamps require `is timestamp && <= request.time`, `createdAt` is immutable on update, all IDs satisfy `isValidId()`, and every string enforces strict `.size()` limits.

## 2. The Dirty Dozen Adversarial Payloads
1. Unauthenticated read on `/accounts/victim_uid` or `/customer_configs/global` -> REJECTED.
2. Authenticated member self-assigning `accountRole: "owner"` or `accessStatus: "approved"` on `/accounts/{uid}` create -> REJECTED.
3. Authenticated member updating `accessStatus` on their own `/accounts/{uid}` -> REJECTED (only `displayName`, `deviceModel`, `cityRegion`, `updatedAt` allowed for self-update).
4. Cross-user read on `/accounts/other_uid` by non-owner -> REJECTED (PII Blanket Test).
5. Non-admin caller attempting to create or modify `/admins/{uid}`, overwrite `/system_bootstrap/master_owner`, write `/customer_configs/global`, or list `/accounts` -> REJECTED.
6. Ghost field injection (`isSuperAdmin: true`) on `/accounts/{uid}` or `/customer_configs/{configId}` -> REJECTED (`hasOnly` gate).
7. Unfiltered `list` query on `/feature_requests` by regular member -> REJECTED (`resource.data.requesterUid == request.auth.uid` enforced).
8. Member attempting to approve their own `/feature_requests/{id}` -> REJECTED (status transition restricted to Owner).
9. Member attempting to create or modify `/skills/{skillId}` -> REJECTED.
10. Invalid rollout percentage (`rolloutPercentage: 77`) or oversized instructions (`> 4000` chars) on `/skills/{skillId}` -> REJECTED.
11. Attempting to update or delete an existing `/audit_logs/{logId}` -> REJECTED (Immutable audit log).
12. Poisoned 500-char document ID or invalid characters in `{requestId}` -> REJECTED (`isValidId` guard).
