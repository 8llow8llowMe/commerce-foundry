package com.commercefoundry.domainlayer.guestsession.application.mapper;

import com.commercefoundry.domainlayer.guestsession.adapter.out.persistence.entity.GuestSessionEntity;
import com.commercefoundry.domainlayer.guestsession.domain.model.GuestSession;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface GuestSessionMapper {
    default GuestSessionEntity toEntity(GuestSession session) {
        return GuestSessionEntity.builder().id(session.id()).tokenHash(session.tokenHash()).expiresAt(session.expiresAt())
            .revokedAt(session.revokedAt()).version(session.version()).build();
    }

    default GuestSession toDomain(GuestSessionEntity entity) {
        return new GuestSession(entity.getId(), entity.getTokenHash(), entity.getExpiresAt(), entity.getRevokedAt(), entity.getVersion());
    }
}
