# DeSent

**DeSent** — a play on *desent* (decentralized via Nostr) and *sent* (email). A native Android application for private email and personal productivity on the Nostr protocol.

Made by **InexorableWeb** · [desent.xyz](https://desent.xyz)

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android-green.svg)](https://www.android.com)
[![Kotlin](https://img.shields.io/badge/kotlin-2.0-orange.svg)](https://kotlinlang.org)

## Overview

DeSent is a privacy-focused Android app for private email and personal productivity (notes, encrypted calendar, contacts, file storage) built on the Nostr protocol. Accounts sync exclusively through the DeSent service relay at `wss://desent.xyz`; third-party relays are only ever contacted — read-only, and temporarily — to fetch an existing Nostr user's profile details during login.

### Key Features

- **📧 Private Email** - Nostr-native email with a client-side spam filter
- **🗓 Encrypted Calendar** - NIP-52 calendar with sharing and RSVPs
- **📝 Private Notes & Contacts** - NIP-78 self-encrypted private storage
- **📦 File Storage** - Encrypted blob storage on desent.xyz
- **🔐 Secure Key Management** - Private keys stored in Android Keystore with biometric authentication
- **🔑 NIP-46 Remote Signing** - Use this phone as a bunker for your other devices and apps (e.g. Amethyst)
- **⌚ Wear OS Companion** - Watch app synced from the phone
- **🎨 Material You Design** - Dynamic theming with Material Design 3

## Download

Download the latest release from the [Releases](https://github.com/DeSentOfficial/AndroidApp/releases) page.

## Deep Link Integration

DeSent supports both Nostr standard URIs (NIP-21) and custom app schemes:

### Nostr URIs (`nostr:`)

- `nostr:npub1...` - View user profile
- `nostr:note1...` - View event
- `nostr:nprofile1...` - View profile with relay hints
- `nostr:nevent1...` - View event with relay hints
- `nostr:naddr1...` - View replaceable events

### DeSent URIs (`desent:`)

- `desent://profile` - View profile
- `desent://settings` - Settings
- `desent://sign?event=<base64>&callback=<scheme>` - Sign external event

## Signing Service

External applications can request Nostr event signatures through DeSent:

### Usage

```
desent://sign?event=<base64_encoded_event>&callback=myapp
```

### Flow

1. External app opens `desent://sign` with event JSON
2. DeSent displays event details for user review
3. User approves or denies the request
4. If approved, DeSent signs the event with the user's private key
5. DeSent returns the signed event via callback: `myapp://signed?event=<signed_event>`

### Security Features

- ✅ Rate limiting (max 10 requests per minute)
- ✅ User approval required for all signatures
- ✅ Clear event preview before signing
- ✅ Callback scheme validation
- ✅ Audit logging of all signing requests

## Architecture

DeSent follows Clean Architecture principles with MVVM pattern:

```
xyz.desent/
├── crypto/           # Key management and cryptography
├── data/            # Data layer (Room, Relay, Repository)
├── domain/          # Business logic (UseCases, Models)
├── presentation/    # UI layer (Compose, ViewModels)
├── widget/          # Home screen widgets
└── di/              # Dependency injection
```

### Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose
- **Architecture**: MVVM + Clean Architecture
- **Database**: Room
- **Networking**: OkHttp + WebSocket
- **Nostr**: nostr-java 2.0.0
- **DI**: Manual DI with AppContainer
- **Testing**: JUnit5, Mockk, Compose Test

## Building

### Prerequisites

- JDK 21
- Android SDK 36 (compileSdk); AGP 8.6 + Gradle 8.7 via the wrapper
- The `nostr-java` dependency is not on Maven Central — the build pulls it
  from `https://maven.398ja.xyz/releases` (declared in `settings.gradle.kts`)

### Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires signing properties, see below)
./gradlew assembleRelease

# Run tests
./gradlew test

# Install on device
./gradlew installDebug
```

### Release signing

Release builds read four Gradle properties (e.g. from `~/.gradle/gradle.properties`):

```
DESENT_STORE_FILE=/path/to/release.jks
DESENT_STORE_PASSWORD=…
DESENT_KEY_ALIAS=…
DESENT_KEY_PASSWORD=…
```

Without them, release APKs are built unsigned.

`app/src/main/assets/adi-registration.properties` is a Google Play
App-Integrity registration token — an identifier that is safe to keep in
source control and ships inside the APK by design.

## Development

### Code Style

- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- 4 space indentation
- Maximum line length: 100 characters

### Testing

```bash
# Unit tests
./gradlew test

# Integration tests
./gradlew connectedAndroidTest

# Lint
./gradlew lint
```

## Security

### Key Management

- Private keys are stored in Android Keystore
- Biometric authentication required for key access
- Keys never leave the device
- No logging of sensitive data

### Signing Service

- All signing requests require explicit user approval
- Rate limiting prevents abuse
- Audit trail of all signing operations
- Callback scheme validation

## Contributing

Contributions are welcome! Open an issue or a pull request against this
repository. For protocol and wire-contract details, see the documents under
[`refs/`](refs/).

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Resources

- [Website](https://desent.xyz)
- [Protocol & API references](refs/) — wire contracts the app implements
- [Nostr Protocol](https://nostr.com)
- [NIPs Repository](https://github.com/nostr-protocol/nips)

## Support

- [Issue Tracker](https://github.com/DeSentOfficial/AndroidApp/issues)
- [Discussions](https://github.com/DeSentOfficial/AndroidApp/discussions)

## Acknowledgments

- [Nostr Protocol](https://nostr.com) - The decentralized social protocol
- [nostr-java](https://github.com/tcheeric/nostr-java) - Java/Kotlin Nostr library
- [Material Design 3](https://m3.material.io) - Design system

---

**Note**: This is a beta release. Use at your own risk and report any issues you encounter.
