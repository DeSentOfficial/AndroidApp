# purpose

DeSent - A native Android application for private email and personal productivity on the Nostr protocol. Features Nostr-native email with spam filtering, encrypted calendar (NIP-52), private notes/contacts (NIP-78), file storage, a NIP-46 remote-signing bunker, home screen widgets, a Wear OS email companion with wrist bunker accept/deny, and deep link integration.

The name is a play on words — "desent" (decentralized, via Nostr) and "sent" (email) — not "Sentinel".

## Relay Policy

- **The app is a consumer of the Nostr network, never a poster.** Any account created in or pulled into DeSent only ever exists on `wss://desent.xyz` — profile data, settings, contacts, notes, and every other published event stay on the DeSent relay so users never leak into the wider Nostr ecosystem.
- This policy is enforced structurally, and the guards are intentional — do NOT relax them:
  - `RelayRepositoryImpl.publishEventToRelay` hard-crashes (`check`) for any target other than `wss://desent.xyz`.
  - Every `NostrWebSocketClient` for a non-DeSent relay is constructed `readOnly`; its `publish()` refuses to send EVENT frames (hard crash), it receives no broadcast subscriptions (`subscribeToEvents` skips read-only clients) and no reconnect replay (`replayPersistentSubscriptions` returns early), and it never answers NIP-42 AUTH. Third-party sockets may only carry explicit targeted REQs (kind-0 profile fetches, plus the relay-mirroring backup fetch below).
  - `ImportBackupUseCase` drops non-DeSent relays from restored backups (legacy DeSent hosts are normalized to the apex), and `connectToPersistentRelays` filters Room rows to `wss://desent.xyz` regardless of their `isPersistent` flag.
  - Pinned by tests: `RelayRepositoryImplTest`, `NostrWebSocketClientTest`, `ImportBackupUseCaseTest`.
- The app publishes ALL events and syncs ALL app data through `wss://desent.xyz` only (see `RelayConfig.EMAIL_RELAY_URL`).
- Third-party relays (`RelayConfig.PUBLIC_PROFILE_RELAYS`) are contacted read-only, and ONLY for kind-0 profile fetches: an existing Nostr user's own profile at login (`NostrRepository.fetchOwnProfileFromRelays`) and contact-profile enrichment (`ContactProfileResolverImpl`, which serves the Room `users` cache first and only refreshes stale/missing entries in the background). Those connections are temporary and torn down after the fetch. Relay order is `desent.xyz` (primary) → `relay.yadha.net` (secondary) → `relay.damus.io` → `nos.lol` → `relay.primal.net`.
- **Relay-mirroring backup fetch** (feature name: "Relay Mirroring"; see `refs/FROM_email.desent.xyz/ANDROID_DM_FANOUT.md` §5): when the 30079 `dm_fanout` opt-in is on, the app additionally issues one targeted read-only REQ `{kinds:[1059], #p:[self]}` against each relay of the user's own NIP-65 list (kind 10002, published only to `wss://desent.xyz`) — at every gift-wrap subscription sweep, after the opt-in is enabled, and after the list is republished. Those connections are read-only and torn down after EOSE; relays that demand NIP-42 AUTH simply fail per-relay (the no-AUTH guard stands). Wraps fetched this way are rumor-kind-filtered post-unwrap: anything that is not a DeSent email rumor (kind 1010 inbound/delivery-receipt, or legacy kind-14 with email tags) is dumped — foreign NIP-17 traffic never routes to the bunker, calendar, or inbox. The relay-picker's directory data comes from `directory.yadha.net` over plain HTTPS.
- **Relay-mirroring §6 mirror surface** (same doc, migration 056): the advisory relay check, per-message mirror status, and reconcile/import all go through NIP-98 HTTPS endpoints on `desent.xyz` — no third-party traffic. Delete propagation publishes a user-signed kind 5 (`["e", wrapId]`) over the `wss://desent.xyz` WS after the HTTP delete succeeds; post-import re-fetch is a targeted `{ids}` REQ against the home relay only.
- `chat.desent.xyz` and the pre-flip hosts are decommissioned and must receive nothing.
- Direct read-only HTTPS fetches to contact/sender domains are expected behavior (NIP-05 `.well-known/nostr.json` and `/favicon.ico` probes) — see `refs/CONTACTS_PROFILE_PIPELINE.md`.
- NIP-42 AUTH is only ever answered for `wss://desent.xyz`.

Website: https://desent.xyz

# Development Commands

## Build Commands
```bash
# Build debug APK
./gradlew assembleDebug

# Build debug APKs (app + wear)
./gradlew :app:assembleDebug :wear:assembleDebug

# Build release APK
./gradlew assembleRelease

# Install debug version to connected device
./gradlew installDebug

# Clean build
./gradlew clean

# Run all tests
./gradlew test

# Run single test class (variant-scoped task is required for --tests filtering)
./gradlew :app:testDebugUnitTest --tests "xyz.desent.EmailRepositoryTest"

# Run connected Android tests
./gradlew connectedAndroidTest

# Generate lint report
./gradlew lint

# Verify 16 KB page-size support of built APKs (Google Play requirement for
# targetSdk 35+). Checks zip alignment of uncompressed .so entries and ELF
# PT_LOAD alignment of every bundled native library. Requires build-tools
# 35.0.0+ and binutils readelf. Run after bumping any dependency that ships
# native libraries (e.g. CameraX, ML Kit).
scripts/check-16kb-alignment.sh app/build/outputs/apk/release/app-release.apk \
  wear/build/outputs/apk/release/wear-release.apk

# Note: ktlint is not configured in this project; rely on `lint` and the
# Kotlin compiler for style/static checks.
```
# Architecture & Structure
## Tech Stack
- **Language**: Kotlin (primary)
- **UI**: Jetpack Compose
- **Architecture**: MVVM with Clean Architecture
- **Database**: Room
- **Networking**: OkHttp + WebSocket for relays
- **Async**: Coroutines + Flow
- **Testing**: JUnit5, Mockk, Compose Test
- **Nostr Library**: nostr-java 2.0.0

## Package Structure

### Modules
- `:app` — phone application (xyz.desent)
- `:core:wearsync` — shared phone↔watch sync contract (xyz.desent.data.wearsync): Data Layer paths, `@Serializable` payloads (config, inbox, calendar, bunker), and tolerant-JSON/GZIP codecs, consumed by both `:app` and `:wear`.
- `:wear` — Wear OS companion (xyz.desent.wear): read-only email + calendar mirror, plus a NIP-46 bunker prompt mirror. The phone decrypts (NIP-44 unwrap happens at ingestion) and pushes gzipped snapshots over the Data Layer — email (≤25 inbox + ≤25 spam threads, HTML-stripped plaintext bodies, unread badge data) and calendar (today+14d occurrence window, recurrence already expanded, contact anniversaries). The watch holds no keys and contacts no relays; its interactions are "Open on phone" (RemoteActivityHelper → `desent://email?threadKey=` / `desent://calendar?date=`), the spam-sync and bunker-sync toggles, local new-mail/new-event/bunker-request notifications, and bunker accept/deny decisions (with optional always-allow) relayed to the phone over the Data Layer — the phone signs and publishes, never the watch. Bunker prompts carry a phone-anchored expiry (`PROMPT_TIMEOUT_SECONDS`, shared with the phone dialog's auto-deny); late watch decisions are ignored by request-id match.

```
xyz.desent/
├── crypto/                 # Nostr key management (SecureKeyManager, BiometricAuthManager)
├── data/
│   ├── local/              # Room database
│   ├── nostr/              # Nostr event processing
│   ├── relay/             # Relay communication
│   ├── repository/         # Repository implementations
│   └── mapper/             # Data transformation
├── domain/
│   ├── model/              # Domain models (User, Follow, SignRequest)
│   ├── repository/         # Repository interfaces
│   └── usecase/            # Business logic
├── presentation/
│   ├── ui/
│   │   ├── login/           # Login screen
│   │   ├── summary/         # Home dashboard
│   │   ├── settings/       # App settings
│   │   ├── sign/            # Sign request screen
│   │   └── splash/          # Splash screen
│   ├── viewmodel/          # ViewModels
│   ├── navigation/         # Navigation
│   ├── deeplink/           # Deep link handling
│   └── theme/              # UI theme
├── widget/                 # Home screen widgets
└── di/                     # Dependency injection (AppContainer)
```
# Deep Link Integration
DeSent supports both Nostr standard URIs (NIP-21) and custom app schemes:
## Nostr URIs (nostr:)
- `nostr:npub1...` - View user profile
- `nostr:note1...` - View event
- `nostr:nprofile1...` - View profile with relay hints
- `nostr:nevent1...` - View event with relay hints
- `nostr:naddr1...` - View replaceable events
## DeSent URIs (desent:)
- `desent://profile` - View profile
- `desent://settings` - Settings
- `desent://sign?event=<base64>&callback=<scheme>` - Sign external event
## Signing Service
External apps can request Nostr event signatures:
1. Open `desent://sign?event=<base64>&callback=<scheme>`
2. User reviews and approves/denies
3. If approved, DeSent signs event with user's private key
4. DeSent returns signed event via callback: `<scheme>://signed?event=<signed_event>`
Security Features:
- Rate limiting (max 10 requests per minute)
- User approval required for all signatures
- Clear event preview before signing
- Callback validation
# Code Style Guidelines
## Kotlin Conventions
- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use 4 space indentation, never tabs
- Maximum line length: 100 characters
- Use expression functions where appropriate
- Prefer val over var, immutable over mutable
## Naming Conventions
- **Classes**: PascalCase (e.g., `EmailRepository`, `SignRequestViewModel`)
- **Functions/Properties**: camelCase (e.g., `publishEvent()`, `signEvent()`)
- **Constants**: UPPER_SNAKE_CASE (e.g., `MAX_RETRY_ATTEMPTS`)
- **Package names**: lowercase, separated by dots
- **Test classes**: Append `Test` (e.g., `EmailRepositoryTest`)
- **UI components**: Compose function names in PascalCase
## Import Organization
1. Standard library imports first
2. Android framework imports
3. Third-party libraries (alphabetical)
4. Project imports (alphabetical)
## Type Guidelines
- Use explicit return types for public APIs
- Prefer sealed classes/enums for limited sets of values
- Use data classes for state containers
- Avoid `!!` operator - use safe calls or explicit checks
## Error Handling
- Use sealed class hierarchy for error types
- Repository layer returns `Result<T>` wrapper
- ViewModels expose errors as UI state
- Log errors with appropriate levels and context
# Nostr Implementation Guidelines
## Key Security
- **Never log or expose nsec/private keys**
- Store keys in Android Keystore
- Use BiometricPrompt for key access
- Implement key rotation mechanism
- Clear keys from memory when not in use
## Relay Communication
- Implement connection pooling (5-10 relays)
- Use exponential backoff for reconnection
- Prioritize user's preferred relays (NIP-65)
- Implement subscription management with IDs
- Handle rate limiting gracefully
## Event Handling
- Always verify event signatures
- Validate event structure before processing
- Implement proper event ID generation
- Use appropriate tags for references
- Handle replaceable events correctly
# Android Best Practices
## Lifecycle Management
- Use ViewModel with SavedStateHandle
- Implement proper lifecycle observers
- Use WorkManager for background sync work
- Handle configuration changes properly
- Clean up resources in lifecycle callbacks
## Widget Implementation
- Use RemoteViews for widget layouts
- Implement proper update mechanisms
- Handle widget configuration activities
- Use appropriate refresh intervals
- Optimize widget layouts for performance
## Permissions & Security
- Request permissions at runtime
- Implement permission rationale dialogs
- Use network security configuration
- Implement certificate pinning for relays
- Follow Android security best practices
# Testing Strategy
## Unit Tests
- Repository layer with mocked relays
- ViewModel with coroutines test rules
- Use cases with isolated dependencies
- Crypto utilities with test vectors
- Sign request validation
## Integration Tests
- End-to-end relay communication
- Database operations with Room
- Widget update mechanisms
- Key storage and retrieval
- Background workflow execution
- Deep link handling
## UI Tests
- Compose UI testing with compose-test
- Widget interaction testing
- Navigation flow testing
- Accessibility testing
- Performance testing
## Test Data
- Use deterministic test vectors
- Mock relays for consistent behavior
- Create reusable test fixtures
- Test edge cases and error conditions
- Validate against reference implementations
# Security Considerations
## Key Management
- Never hardcode keys or tokens
- Use Android Keystore for cryptographic operations
- Implement secure key backup/restore
- Support multiple user accounts
- Clear sensitive data from logs
## Network Security
- Use HTTPS for all non-WebSocket traffic
- Implement certificate pinning
- Validate relay certificates
- Use secure defaults for connections
- Monitor for suspicious relay behavior
## Data Protection
- Encrypt sensitive data at rest
- Use encrypted preferences for settings
- Implement proper data retention policies
- Clear sensitive data on logout
- Follow data minimization principles
## Signing Service Security
- Rate limit signing requests (10/minute)
- Require user approval for all signatures
- Validate callback schemes (whitelisting)
- Log all signing requests (audit trail)
- Clear event preview before signing
- No automatic signing without explicit user consent
# Performance Guidelines
## Optimization
- Use coroutines properly (avoid blocking)
- Implement connection pooling
- Cache relay responses appropriately
- Use DiffUtil for list updates
- Profile with Android Profiler
## Memory Management
- Avoid memory leaks with weak references
- Clean up observers in lifecycle callbacks
- Use view binding properly
- Manage bitmap memory efficiently
- Monitor memory usage patterns
