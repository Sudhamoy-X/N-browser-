# NYXORA Browser Firestore Security Specification

## 1. Data Invariants
1. **Strict Multi-Tenant Isolation**: Every user document and subcollection (`/users/{userId}/bookmarks/{bookmarkId}`, `/users/{userId}/history/{historyId}`, `/users/{userId}/tabs/{tabId}`, `/users/{userId}/site_settings/{hostId}`) belongs exclusively to `userId` where `request.auth != null && request.auth.uid == userId` and `data.userId == request.auth.uid`.
2. **Schema & Bounds Enforcement**: Every `create` and `update` operation must pass a dedicated `isValid[Entity](incoming())` helper enforcing exact keys (`hasAll`, `hasOnly`), string `.size()` bounds, integer bounds, boolean types, and enum values.
3. **Temporal Integrity**: `createdAt` and `updatedAt` must be server timestamps (`is timestamp && <= request.time`), and `createdAt` + `userId` are immutable on `update`.
4. **Query Enforcer**: Every `allow list` rule enforces `isSignedIn() && request.auth.uid == userId && existing().userId == request.auth.uid`.

## 2. The "Dirty Dozen" Payloads
1. **Unauthenticated Read**: `GET /users/alice/bookmarks/b1` with `auth == null` -> REJECT.
2. **Cross-User Read (PII/Data Leak)**: `GET /users/bob/bookmarks/b1` as `alice` -> REJECT.
3. **Unfiltered List Query**: `LIST /users/alice/bookmarks` without `.where("userId", "==", "alice")` -> REJECT.
4. **Identity Spoofing on Create**: `CREATE /users/alice/bookmarks/b1` with `userId: "bob"` -> REJECT.
5. **Shadow Update (Ghost Field)**: `UPDATE /users/alice/bookmarks/b1` with `isVerified: true` -> REJECT.
6. **Immutable Field Mutation (`createdAt`)**: `UPDATE /users/alice/bookmarks/b1` changing `createdAt` -> REJECT.
7. **Immutable Field Mutation (`userId`)**: `UPDATE /users/alice/bookmarks/b1` changing `userId` -> REJECT.
8. **Resource Poisoning (Oversized URL)**: `CREATE /users/alice/bookmarks/b1` with 10,000-char `url` -> REJECT.
9. **ID Poisoning**: `CREATE /users/alice/bookmarks/bad$id!` with invalid characters -> REJECT.
10. **Value Poisoning on Update**: `UPDATE /users/alice/tabs/t1` setting `textZoom: 9999` or string -> REJECT.
11. **Invalid Enum Value**: `CREATE /users/alice/site_settings/example_com` with `cameraPermission: "ALWAYS_GRANT"` -> REJECT.
12. **Future Timestamp Spoofing**: `CREATE /users/alice/history/h1` with `createdAt` in the future -> REJECT.
