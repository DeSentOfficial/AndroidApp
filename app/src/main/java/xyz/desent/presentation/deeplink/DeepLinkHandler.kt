package xyz.desent.presentation.deeplink

import android.net.Uri
import android.util.Log
import xyz.desent.crypto.Bech32Utils

sealed class NostrUri {
    data class Profile(val npub: String, val relays: List<String>?) : NostrUri()
    data class Event(val noteId: String, val relays: List<String>?) : NostrUri()
    data class Address(val kind: Long, val pubkey: String, val identifier: String, val relays: List<String>?) : NostrUri()
}

sealed class DeSentUri {
    object Profile : DeSentUri()
    object Settings : DeSentUri()
    data class Sign(val eventJson: String, val callback: String?) : DeSentUri()
}

object DeepLinkHandler {
    private const val TAG = "DeepLinkHandler"
    
    fun parseUri(uri: Uri): Pair<NostrUri?, DeSentUri?> {
        return when (uri.scheme) {
            "nostr" -> parseNostrUri(uri) to null
            "desent" -> null to parseDeSentUri(uri)
            "https" -> parseWebUri(uri)
            else -> {
                Log.w(TAG, "Unknown scheme: ${uri.scheme}")
                null to null
            }
        }
    }
    
    private fun parseNostrUri(uri: Uri): NostrUri? {
        val uriString = uri.toString()
        val nostrPart = uriString.removePrefix("nostr:")
        
        return try {
            when {
                nostrPart.startsWith("npub1") || nostrPart.startsWith("nprofile1") -> {
                    val decoded = Bech32Utils.npubToHex(nostrPart)
                    val relays = uri.getQueryParameters("relay")
                    NostrUri.Profile(decoded, relays)
                }
                nostrPart.startsWith("note1") || nostrPart.startsWith("nevent1") -> {
                    val decoded = Bech32Utils.npubToHex(nostrPart)
                    val relays = uri.getQueryParameters("relay")
                    NostrUri.Event(decoded, relays)
                }
                nostrPart.startsWith("naddr1") -> {
                    val decoded = Bech32Utils.npubToHex(nostrPart)
                    val parts = decoded.split(":")
                    if (parts.size >= 3) {
                        val relays = uri.getQueryParameters("relay")
                        NostrUri.Address(
                            kind = parts[0].toLongOrNull() ?: 0L,
                            pubkey = parts[1],
                            identifier = parts[2],
                            relays = relays
                        )
                    } else null
                }
                else -> {
                    Log.w(TAG, "Unknown nostr entity: $nostrPart")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse nostr URI: $uriString", e)
            null
        }
    }
    
    private fun parseDeSentUri(uri: Uri): DeSentUri? {
        return when (uri.host) {
            "profile" -> DeSentUri.Profile
            "settings" -> DeSentUri.Settings
            "sign" -> {
                val eventJson = uri.getQueryParameter("event")
                val callback = uri.getQueryParameter("callback")
                if (eventJson != null) {
                    DeSentUri.Sign(eventJson, callback)
                } else null
            }
            else -> {
                Log.w(TAG, "Unknown desent path: ${uri.host}")
                null
            }
        }
    }
    
    private fun parseWebUri(uri: Uri): Pair<NostrUri?, DeSentUri?> {
        if (uri.host == "desent.xyz") {
            val path = uri.path?.removePrefix("/") ?: ""
            return when {
                path.startsWith("npub1") || path.startsWith("nprofile1") -> {
                    val decoded = Bech32Utils.npubToHex(path.split("?")[0])
                    val relays = uri.getQueryParameters("relay")
                    NostrUri.Profile(decoded, relays) to null
                }
                path.startsWith("note1") || path.startsWith("nevent1") -> {
                    val decoded = Bech32Utils.npubToHex(path.split("?")[0])
                    val relays = uri.getQueryParameters("relay")
                    NostrUri.Event(decoded, relays) to null
                }
                else -> null to null
            }
        }
        return null to null
    }
}
