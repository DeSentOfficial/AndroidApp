package xyz.desent.domain.usecase

import xyz.desent.domain.model.Alias
import xyz.desent.domain.model.AliasServiceConfig
import xyz.desent.domain.model.AliasSlotPurchase
import xyz.desent.domain.model.AliasSlotsStatus
import xyz.desent.domain.model.AliasTierInfo
import xyz.desent.domain.model.VanityRequest
import xyz.desent.domain.repository.AliasRepository

class AliasUseCase(
    private val aliasRepository: AliasRepository
) {
    suspend fun getEmailDomain(): Result<String> =
        aliasRepository.getEmailDomain()

    suspend fun getConfig(): Result<AliasServiceConfig> =
        aliasRepository.getConfig()

    suspend fun listAliases(): Result<Pair<List<Alias>, AliasTierInfo>> =
        aliasRepository.listAliases()

    suspend fun getTierInfo(): Result<AliasTierInfo> =
        aliasRepository.getTierInfo()

    suspend fun createAlias(localPart: String, label: String?): Result<Alias> =
        aliasRepository.createAlias(localPart, label)

    suspend fun deleteAlias(id: Long): Result<Unit> =
        aliasRepository.deleteAlias(id)

    suspend fun listVanityRequests(identity: nostr.id.Identity? = null): Result<List<VanityRequest>> =
        aliasRepository.listVanityRequests(identity)

    suspend fun createVanityRequest(
        localPart: String,
        domain: String?,
        kind: String,
        identity: nostr.id.Identity? = null
    ): Result<VanityRequest> =
        aliasRepository.createVanityRequest(localPart, domain, kind, identity)

    suspend fun getSlotStatus(): Result<AliasSlotsStatus> =
        aliasRepository.getSlotStatus()

    suspend fun purchaseSlots(quantity: Int): Result<AliasSlotPurchase> =
        aliasRepository.purchaseSlots(quantity)
}
