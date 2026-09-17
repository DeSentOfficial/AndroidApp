package xyz.desent.data.attachment

import android.util.Log
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import xyz.desent.crypto.BlossomAuth
import xyz.desent.crypto.NostrHttpAuth
import xyz.desent.crypto.SecureKeyManager

/**
 * Adds auth headers to DeSent requests so Coil can render protected
 * content transparently:
 *
 *  - `desent.xyz/blobs/...` — signed kind 22242 (NIP-42) Blossom header.
 *    Historical events still carry `email.desent.xyz/blobs/...` URLs, so
 *    both hosts are matched. The blob URL already carries the
 *    `encryption_key` query param (the server decrypts at read time); this
 *    interceptor only supplies the missing auth.
 *  - `desent.xyz/api/favicon/...` — NIP-98 kind 27235 header (the favicon
 *    cache endpoint; same dialect as the alias API — `u` tag = exact URL,
 *    GET carries no `payload` tag). See
 *    refs/FROM_email.desent.xyz/FAVICON_CACHE.md.
 *
 * Signing is normally suspend; OkHttp interceptors are synchronous. This runs
 * on Coil's network dispatcher (an IO thread), so a brief blocking sign is safe.
 * Unmatched requests, and requests whose sign fails or key is unavailable,
 * pass through untouched (Coil falls back to the initials avatar).
 */
class DesentBlobAuthInterceptor(
    private val secureKeyManagerProvider: () -> SecureKeyManager?
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        if (isFaviconCacheUrl(url)) {
            return chain.proceed(withFaviconAuth(request, url))
        }

        if (!isDesentBlobUrl(url)) return chain.proceed(request)

        val sha256 = extractSha(url) ?: run {
            Log.w(TAG, "Blob URL has no sha path; skipping auth: $url")
            return chain.proceed(request)
        }
        val secureKeyManager = secureKeyManagerProvider() ?: run {
            Log.w(TAG, "SecureKeyManager unavailable; skipping auth")
            return chain.proceed(request)
        }

        val header = runBlocking {
            BlossomAuth.buildHeader(secureKeyManager, "download", sha256).getOrNull()
        } ?: return chain.proceed(request)

        val authed = request.newBuilder()
            .header("Authorization", header)
            .build()
        return chain.proceed(authed)
    }

    /** Apex host only — the favicon-cache endpoint has no legacy-subdomain form. */
    private fun isFaviconCacheUrl(url: String): Boolean =
        url.startsWith("https://desent.xyz/api/favicon/")

    private fun withFaviconAuth(request: Request, url: String): Request {
        val secureKeyManager = secureKeyManagerProvider() ?: run {
            Log.w(TAG, "SecureKeyManager unavailable; skipping favicon auth")
            return request
        }
        val header = runBlocking {
            NostrHttpAuth(secureKeyManager).buildAuthHeader(url, request.method).getOrNull()
        } ?: return request
        return request.newBuilder()
            .header("Authorization", header)
            .build()
    }

    /** Matches the apex host plus legacy `email`/`mail` subdomains in historical
     *  event URLs. `chat.desent.xyz` serves images under `/api`, never `/blobs/`,
     *  so it cannot match. */
    private fun isDesentBlobUrl(url: String): Boolean =
        url.contains("desent.xyz/blobs/")

    /** Pulls the sha256 segment out of `…/blobs/<sha>?encryption_key=…`. */
    private fun extractSha(url: String): String? {
        val marker = "/blobs/"
        val start = url.indexOf(marker).takeIf { it >= 0 } ?: return null
        val tail = url.substring(start + marker.length)
        return tail.substringBefore('?').takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val TAG = "BlobAuthInterceptor"
    }
}
