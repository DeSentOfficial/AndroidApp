package xyz.desent.data.wearsync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Internal GZIP + tolerant-JSON machinery shared by the wear sync codecs.
 * Payloads ride DataItems (100KB data-layer limit); plaintext prose
 * compresses ~4-5x, so gzipping keeps them comfortably under it.
 */
internal object WearSyncGzip {
    private const val GZIP_MAGIC_HIGH = 0x1f
    private const val GZIP_MAGIC_LOW = 0x8b

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    inline fun <reified T> encodeGzipped(value: T): ByteArray =
        gzip(json.encodeToString(value).encodeToByteArray())

    /**
     * Decode a (possibly gzipped) payload; accepts raw JSON as a fallback for
     * debuggability. Returns null on any parse failure.
     */
    inline fun <reified T> decodeGzipped(bytes: ByteArray): T? {
        val raw = bytes.takeIf { it.isGzipped() }?.let(::gunzip) ?: bytes
        return try {
            json.decodeFromString<T>(raw.decodeToString())
        } catch (e: Exception) {
            null
        }
    }

    private fun ByteArray.isGzipped(): Boolean =
        size >= 2 && this[0] == GZIP_MAGIC_HIGH.toByte() && this[1] == GZIP_MAGIC_LOW.toByte()

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
}
