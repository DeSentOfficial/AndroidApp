package xyz.desent.data.mapper

import xyz.desent.data.local.database.entity.UserEntity
import xyz.desent.domain.model.User

class UserMapper {
    
    fun mapToDomain(entity: UserEntity): User {
        return User(
            npub = entity.npub,
            name = entity.name,
            displayName = entity.displayName,
            about = entity.about,
            picture = entity.picture,
            banner = entity.banner,
            website = entity.website,
            lud06 = entity.lud06,
            lud16 = entity.lud16,
            nip05 = entity.nip05,
            nip05Verified = entity.nip05Verified,
            createdAt = entity.createdAt,
            lastUpdated = entity.lastUpdated,
            relayListJson = entity.relayListJson
        )
    }

    fun mapToEntity(domain: User): UserEntity {
        return UserEntity(
            npub = domain.npub,
            name = domain.name,
            displayName = domain.displayName,
            about = domain.about,
            picture = domain.picture,
            banner = domain.banner,
            website = domain.website,
            lud06 = domain.lud06,
            lud16 = domain.lud16,
            nip05 = domain.nip05,
            nip05Verified = domain.nip05Verified,
            createdAt = domain.createdAt,
            lastUpdated = domain.lastUpdated,
            relayListJson = domain.relayListJson
        )
    }
    
    fun mapToEntityList(domainList: List<User>): List<UserEntity> {
        return domainList.map { mapToEntity(it) }
    }
    
    fun mapToDomainList(entityList: List<UserEntity>): List<User> {
        return entityList.map { mapToDomain(it) }
    }
}