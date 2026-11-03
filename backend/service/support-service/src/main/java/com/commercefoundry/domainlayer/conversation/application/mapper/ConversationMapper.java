package com.commercefoundry.domainlayer.conversation.application.mapper;

import com.commercefoundry.domainlayer.conversation.adapter.out.persistence.entity.ConversationEntity;
import com.commercefoundry.domainlayer.conversation.domain.model.Conversation;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ConversationMapper {
    default ConversationEntity toEntity(Conversation value) {
        return ConversationEntity.builder().id(value.id()).memberId(value.memberId()).guestSessionId(value.guestSessionId()).locale(value.locale())
            .channel(value.channel()).status(value.status()).lastSeq(value.lastSeq()).version(value.version()).build();
    }

    default Conversation toDomain(ConversationEntity value) {
        return new Conversation(value.getId(), value.getMemberId(), value.getGuestSessionId(), value.getLocale(), value.getChannel(), value.getStatus(),
            value.getLastSeq(), value.getVersion());
    }
}
