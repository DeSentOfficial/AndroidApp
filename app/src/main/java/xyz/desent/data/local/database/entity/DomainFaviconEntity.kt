package xyz.desent.data.local.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Favicon-availability cache per sender domain (see FaviconResolver): a
 * recorded miss suppresses URL generation for [RECHECK window] so known
 * icon-less domains aren't re-probed on every list render or app open.
 */
@Entity(tableName = "domain_favicons")
data class DomainFaviconEntity(
    /** Lowercased sender domain (`example.com`). */
    @PrimaryKey val domain: String,
    /** Whether `https://<domain>/favicon.ico` resolved to an image. */
    val available: Boolean,
    val checkedAt: Long
)
