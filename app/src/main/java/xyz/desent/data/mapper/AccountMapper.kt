package xyz.desent.data.mapper

import xyz.desent.data.local.database.entity.AccountEntity
import xyz.desent.domain.model.Account
import xyz.desent.domain.model.User

class AccountMapper {

    fun mapToDomain(entity: AccountEntity): Account {
        return Account(
            npub = entity.npub,
            displayName = entity.displayName,
            picture = entity.picture,
            nip05 = entity.nip05,
            requiresBiometrics = entity.requiresBiometrics,
            lastActiveAt = entity.lastActiveAt,
            addedAt = entity.addedAt,
            custodialUsername = entity.custodialUsername,
            primaryAddress = entity.primaryAddress
        )
    }

    fun mapToDomainList(entities: List<AccountEntity>): List<Account> {
        return entities.map { mapToDomain(it) }
    }

    fun mapToEntity(domain: Account): AccountEntity {
        return AccountEntity(
            npub = domain.npub,
            displayName = domain.displayName,
            picture = domain.picture,
            nip05 = domain.nip05,
            requiresBiometrics = domain.requiresBiometrics,
            lastActiveAt = domain.lastActiveAt,
            addedAt = domain.addedAt,
            custodialUsername = domain.custodialUsername,
            primaryAddress = domain.primaryAddress
        )
    }

    /**
     * Build/refresh an [AccountEntity] from the latest kind-0 [User] profile data.
     * Keeps [AccountEntity.lastActiveAt] and [AccountEntity.addedAt] from the
     * existing row when present,
     * since those are device-local concerns and the profile refresh shouldn't
     * disturb them.
     */
    fun mergeWithProfile(npub: String, user: User, existing: AccountEntity?): AccountEntity {
        return AccountEntity(
            npub = npub,
            displayName = user.displayName ?: user.name,
            picture = user.picture,
            nip05 = user.nip05,
            requiresBiometrics = existing?.requiresBiometrics ?: false,
            lastActiveAt = existing?.lastActiveAt ?: 0L,
            addedAt = existing?.addedAt ?: System.currentTimeMillis(),
            custodialUsername = existing?.custodialUsername,
            primaryAddress = existing?.primaryAddress
        )
    }
}
