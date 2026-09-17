package xyz.desent.data.repository

import android.util.Log
import xyz.desent.data.alias.AliasClient
import xyz.desent.data.alias.model.SlotPurchaseDto
import xyz.desent.data.vanity.VanityClient
import xyz.desent.data.vanity.model.VanityRequestDto
import xyz.desent.domain.model.Alias
import xyz.desent.domain.model.AliasServiceConfig
import xyz.desent.domain.model.AliasSlotPurchase
import xyz.desent.domain.model.AliasSlotsStatus
import xyz.desent.domain.model.AliasTierInfo
import xyz.desent.domain.model.VanityConfig
import xyz.desent.domain.model.VanityRequest
import xyz.desent.domain.model.VanityRequestStatus
import xyz.desent.domain.repository.AliasRepository

class AliasRepositoryImpl(
    private val aliasClient: AliasClient,
    private val vanityClient: VanityClient
) : AliasRepository {

    override suspend fun getEmailDomain(): Result<String> {
        return getConfig().map { it.emailDomain }
    }

    override suspend fun getConfig(): Result<AliasServiceConfig> {
        return aliasClient.getConfig().map { it.toDomain() }
            .recoverCatching { e ->
                Log.w(TAG, "getConfig failed, falling back to default: ${e.message}")
                AliasServiceConfig(
                    emailDomain = defaultEmailDomain(),
                    vanity = VanityConfig(),
                    slotPriceSats = null
                )
            }
    }    override suspend fun listAliases(): Result<Pair<List<Alias>, AliasTierInfo>> {
        return aliasClient.listAliases().map { resp ->
            val aliases = resp.aliases.map { it.toDomain() }
            val tier = AliasTierInfo(
                tier = resp.tier,
                cap = resp.cap,
                used = resp.used,
                emailDomain = "",
                paidUntil = resp.paidUntil
            )
            aliases to tier
        }
    }

    override suspend fun getTierInfo(): Result<AliasTierInfo> {
        return aliasClient.getTierInfo().map { resp ->
            AliasTierInfo(
                tier = resp.tier,
                cap = resp.cap,
                used = resp.used,
                emailDomain = resp.emailDomain,
                freeCap = resp.freeCap,
                slotsOwned = resp.slotsOwned,
                storageUsed = resp.storageUsed,
                storageCap = resp.storageCap,
                paidUntil = resp.paidUntil,
                wotDefaultMaxHops = resp.wotDefaultMaxHops,
                wotEffectiveMaxHops = resp.wotEffectiveMaxHops,
                keyRotation = resp.keyRotation,
                keyRotationPurchased = resp.keyRotationPurchased,
                keyRotationPurchaseEnabled = resp.keyRotationPurchaseEnabled,
                keyRotationPriceSats = resp.keyRotationPriceSats,
                keyRotationPriceMode = resp.keyRotationPriceMode,
                keyRotationPriceUsd = resp.keyRotationPriceUsd,
                agents = resp.agents,
                dmFanout = resp.dmFanout,
                dmFanoutPurchased = resp.dmFanoutPurchased,
                fanoutPurchaseEnabled = resp.fanoutPurchaseEnabled,
                fanoutPriceSats = resp.fanoutPriceSats,
                fanoutPriceMode = resp.fanoutPriceMode,
                fanoutPriceUsd = resp.fanoutPriceUsd
            )
        }
    }

    override suspend fun createAlias(localPart: String, label: String?): Result<Alias> {
        return aliasClient.createAlias(localPart, label).map { it.toDomain() }
    }

    override suspend fun deleteAlias(id: Long): Result<Unit> {
        return aliasClient.deleteAlias(id).map { }
    }

    override suspend fun listVanityRequests(
        identity: nostr.id.Identity?
    ): Result<List<VanityRequest>> {
        return vanityClient.listRequests(identity).map { list -> list.map { it.toDomain() } }
    }

    override suspend fun createVanityRequest(
        localPart: String,
        domain: String?,
        kind: String,
        identity: nostr.id.Identity?
    ): Result<VanityRequest> {
        return vanityClient.createRequest(localPart, domain, kind, identity).map { it.toDomain() }
    }

    override suspend fun getSlotStatus(): Result<AliasSlotsStatus> {
        return aliasClient.getSlots().map { resp ->
            AliasSlotsStatus(
                tier = resp.tier,
                freeCap = resp.freeCap,
                slotsOwned = resp.slotsOwned,
                cap = resp.cap,
                priceSats = resp.priceSats,
                purchases = resp.purchases.map { it.toDomain() }
            )
        }
    }

    override suspend fun purchaseSlots(quantity: Int): Result<AliasSlotPurchase> {
        return aliasClient.purchaseSlots(quantity).map { it.toDomain() }
    }

    private fun defaultEmailDomain(): String =
        AliasClient.DEFAULT_BASE_URL
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')

    private fun xyz.desent.data.alias.model.ConfigResponse.toDomain() = AliasServiceConfig(
        emailDomain = emailDomain,
        vanity = VanityConfig(
            freeLength = vanityFreeLength,
            ladder = vanityLadder.entries
                .mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }
                .toMap(),
            ladderUsd = vanityLadderUsd.entries
                .mapNotNull { (k, v) -> k.toIntOrNull()?.let { len -> v?.let { len to it } } }
                .toMap()
        ),
        slotPriceSats = slotPriceSats,
        slotPriceUsd = slotPriceUsd
    )

    private fun xyz.desent.data.alias.model.AliasDto.toDomain(): Alias = Alias(
        id = id,
        email = aliasEmail,
        localPart = aliasLocal,
        label = label,
        isActive = isActive,
        createdAt = createdAt
    )

    private fun VanityRequestDto.toDomain(): VanityRequest = VanityRequest(
        id = id,
        localPart = localPart,
        domain = domain,
        kind = kind,
        quotedSatoshi = quotedSatoshi,
        status = VanityRequestStatus.fromWire(status),
        requestedAt = requestedAt,
        decidedAt = decidedAt,
        note = note
    )

    private fun SlotPurchaseDto.toDomain(): AliasSlotPurchase = AliasSlotPurchase(
        id = id,
        quantity = quantity,
        quotedSatoshi = quotedSatoshi,
        status = VanityRequestStatus.fromWire(status),
        requestedAt = requestedAt,
        decidedAt = decidedAt,
        note = note
    )

    companion object {
        private const val TAG = "AliasRepository"
    }
}
