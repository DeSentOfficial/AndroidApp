# R8 rules for DeSent release builds.
#
# NOTE: isMinifyEnabled is currently false for both build types — these rules
# only take effect if/when shrinking is enabled. They are kept correct so
# flipping the flag on is a one-line change.

# Crypto providers are accessed reflectively.
-keep class org.bouncycastle.** { *; }

# Keep Nostr crypto helpers (bech32/secp256k1 via nostr-java).
-keep class xyz.desent.crypto.** { *; }

# Room entities and DAOs are referenced via generated impls.
-keep class xyz.desent.data.local.database.entity.** { *; }
-keep class xyz.desent.data.local.database.dao.** { *; }

# kotlinx.serialization.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-dontnote kotlinx.serialization.AnnotationsKt
