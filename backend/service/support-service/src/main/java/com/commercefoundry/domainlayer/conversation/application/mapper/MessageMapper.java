package com.commercefoundry.domainlayer.conversation.application.mapper;

import com.commercefoundry.domainlayer.conversation.adapter.out.persistence.entity.MessageEntity;
import com.commercefoundry.domainlayer.conversation.domain.model.Message;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface MessageMapper {
    default MessageEntity toEntity(Message value) {
        return MessageEntity.builder().id(value.id()).conversationId(value.conversationId()).seq(value.seq()).authorType(value.authorType())
            .authorMemberId(value.authorMemberId()).authorGuestSessionId(value.authorGuestSessionId()).status(value.status())
            .clientMessageId(value.clientMessageId()).content(value.content()).build();
    }

    default Message toDomain(MessageEntity value) {
        return new Message(value.getId(), value.getConversationId(), value.getSeq(), value.getAuthorType(), value.getAuthorMemberId(),
            value.getAuthorGuestSessionId(), value.getStatus(), value.getClientMessageId(), value.getContent());
    }
}
