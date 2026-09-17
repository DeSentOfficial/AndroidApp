package xyz.desent.data.mapper

import xyz.desent.data.local.database.entity.RelayEntity
import xyz.desent.domain.model.ConnectionStatus
import xyz.desent.domain.model.Relay
import xyz.desent.domain.model.Nip11Metadata
import xyz.desent.domain.model.Limitations
import xyz.desent.domain.model.Fees
import xyz.desent.domain.model.Fee
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer
import android.util.Log

class RelayMapper {
    
    fun mapToDomain(entity: RelayEntity): Relay {
        return Relay(
            url = entity.url,
            isActive = entity.isActive,
            connectionStatus = ConnectionStatus.valueOf(entity.connectionStatus),
            failureCount = entity.failureCount,
            lastConnectedAt = entity.lastConnectedAt,
            isWrite = entity.isWrite,
            isPersistent = entity.isPersistent,
            nip11Metadata = mapToNip11Metadata(entity),
            nip11CachedAt = entity.nip11CachedAt
        )
    }

    fun mapToEntity(domain: Relay): RelayEntity {
        return RelayEntity(
            url = domain.url,
            isActive = domain.isActive,
            connectionStatus = domain.connectionStatus.name,
            failureCount = domain.failureCount,
            lastConnectedAt = domain.lastConnectedAt,
            isWrite = domain.isWrite,
            isPersistent = domain.isPersistent,
            nip11Name = domain.nip11Metadata?.name,
            nip11Description = domain.nip11Metadata?.description,
            nip11Pubkey = domain.nip11Metadata?.pubkey,
            nip11Contact = domain.nip11Metadata?.contact,
            nip11SupportedNips = domain.nip11Metadata?.supportedNips?.joinToString(","),
            nip11RelayCountries = domain.nip11Metadata?.relayCountries?.joinToString(","),
            nip11LanguageTags = domain.nip11Metadata?.languageTags?.joinToString(","),
            nip11PostingPolicy = domain.nip11Metadata?.postingPolicy,
            nip11LimitationsJson = domain.nip11Metadata?.limitations?.let {
                runCatching { json.encodeToString(Limitations.serializer(), it) }.getOrNull()
            },
            nip11FeesJson = domain.nip11Metadata?.fees?.let {
                runCatching { json.encodeToString(Fees.serializer(), it) }.getOrNull()
            },
            nip11Payments = domain.nip11Metadata?.payments,
            nip11Version = domain.nip11Metadata?.version,
            nip11Icon = domain.nip11Metadata?.icon,
            nip11Software = domain.nip11Metadata?.software,
            nip11CachedAt = domain.nip11CachedAt
                ?: domain.nip11Metadata?.let { System.currentTimeMillis() }
        )
    }
    
    fun mapToEntityList(domainList: List<Relay>): List<RelayEntity> {
        return domainList.map { mapToEntity(it) }
    }
    
    fun mapToDomainList(entityList: List<RelayEntity>): List<Relay> {
        return entityList.map { mapToDomain(it) }
    }
    
    fun mapToNip11Metadata(entity: RelayEntity): Nip11Metadata? {
        return try {
            if (entity.nip11CachedAt == null) {
                return null
            }
            
            val supportedNips = entity.nip11SupportedNips?.split(",")?.filter { it.isNotBlank() }?.map { it.toInt() }
            val relayCountries = entity.nip11RelayCountries?.split(",")?.filter { it.isNotBlank() }
            val languageTags = entity.nip11LanguageTags?.split(",")?.filter { it.isNotBlank() }
            val limitations = entity.nip11LimitationsJson?.let {
                runCatching { json.decodeFromString(Limitations.serializer(), it) }.getOrNull()
            }
            val fees = entity.nip11FeesJson?.let {
                runCatching { json.decodeFromString(Fees.serializer(), it) }.getOrNull()
            }
            
            Nip11Metadata(
                name = entity.nip11Name,
                description = entity.nip11Description,
                pubkey = entity.nip11Pubkey,
                contact = entity.nip11Contact,
                supportedNips = supportedNips,
                version = entity.nip11Version,
                icon = entity.nip11Icon,
                software = entity.nip11Software,
                relayCountries = relayCountries,
                languageTags = languageTags,
                postingPolicy = entity.nip11PostingPolicy,
                limitations = limitations,
                fees = fees,
                payments = entity.nip11Payments
            )
        } catch (e: Exception) {
            Log.e("RelayMapper", "Error mapping RelayEntity to Nip11Metadata for ${entity.url}", e)
            null
        }
    }
    
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}