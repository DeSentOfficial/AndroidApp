package xyz.desent.data.mapper

import xyz.desent.data.local.database.entity.FollowEntity
import xyz.desent.domain.model.Follow

class FollowMapper {

    fun mapToDomain(entity: FollowEntity): Follow {
        return Follow(
            followerNpub = entity.followerNpub,
            followingNpub = entity.followingNpub,
            isFavorite = entity.isFavorite,
            createdAt = entity.createdAt,
            petname = entity.petname,
            isLocalOnly = entity.isLocalOnly
        )
    }

    fun mapToEntity(domain: Follow): FollowEntity {
        return FollowEntity(
            id = "${domain.followerNpub}-${domain.followingNpub}",
            followerNpub = domain.followerNpub,
            followingNpub = domain.followingNpub,
            isFavorite = domain.isFavorite,
            createdAt = domain.createdAt,
            petname = domain.petname,
            isLocalOnly = domain.isLocalOnly
        )
    }

    fun mapToEntityList(domainList: List<Follow>): List<FollowEntity> {
        return domainList.map { mapToEntity(it) }
    }

    fun mapToDomainList(entityList: List<FollowEntity>): List<Follow> {
        return entityList.map { mapToDomain(it) }
    }
}
