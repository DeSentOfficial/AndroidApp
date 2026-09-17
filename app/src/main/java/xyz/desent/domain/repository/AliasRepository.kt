package xyz.desent.domain.repository

import xyz.desent.domain.model.Alias
import xyz.desent.domain.model.AliasServiceConfig
import xyz.desent.domain.model.AliasSlotPurchase
import xyz.desent.domain.model.AliasSlotsStatus
import xyz.desent.domain.model.AliasTierInfo
import xyz.desent.domain.model.VanityRequest

interface AliasRepository {
    suspend fun getEmailDomain(): Result<String>
    suspend fun getConfig(): Result<AliasServiceConfig>
    suspend fun listAliases(): Result<Pair<List<Alias>, AliasTierInfo>>
    suspend fun getTierInfo(): Result<AliasTierInfo>
    suspend fun createAlias(localPart: String, label: String?): Result<Alias>
    suspend fun deleteAlias(id: Long): Result<Unit>

    /**
     * @param identity explicit NIP-98 signer for the signup flow, where the
     * fresh claiming key is memory-only (not the active account). Null — the
     * default — signs with the active account.
     */
    suspend fun listVanityRequests(identity: nostr.id.Identity? = null): Result<List<VanityRequest>>
    suspend fun createVanityRequest(
        localPart: String,
        domain: String?,
        kind: String,
        identity: nostr.id.Identity? = null
    ): Result<VanityRequest>
    suspend fun getSlotStatus(): Result<AliasSlotsStatus>
    suspend fun purchaseSlots(quantity: Int): Result<AliasSlotPurchase>
}
