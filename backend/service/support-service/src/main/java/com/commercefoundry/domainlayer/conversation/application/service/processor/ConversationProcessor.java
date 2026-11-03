package com.commercefoundry.domainlayer.conversation.application.service.processor;

import com.commercefoundry.domainlayer.conversation.application.exception.ConversationErrorCode;
import com.commercefoundry.domainlayer.conversation.application.exception.ConversationException;
import com.commercefoundry.domainlayer.conversation.application.info.ConversationInfo;
import com.commercefoundry.domainlayer.conversation.application.port.out.ConversationRepositoryPort;
import com.commercefoundry.domainlayer.conversation.domain.model.Conversation;
import com.commercefoundry.domainlayer.conversation.domain.model.ConversationLocale;
import com.commercefoundry.domainlayer.guestsession.domain.model.GuestSessionIdentity;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ConversationProcessor {
    private final ConversationRepositoryPort repository;
    private final SnowflakeIdGenerator ids;

    public ConversationInfo createForMember(long memberId, ConversationLocale locale) {
        validateMember(memberId);
        return create(memberId, null, locale);
    }

    public ConversationInfo createForGuest(GuestSessionIdentity guest, ConversationLocale locale) {
        return create(null, guest.id(), locale);
    }

    private ConversationInfo create(Long memberId, Long guestSessionId, ConversationLocale locale) {
        if (locale == null) {
            throw new ConversationException(ConversationErrorCode.INVALID_LOCALE);
        }
        return ConversationInfo.from(repository.saveAndFlush(Conversation.create(ids.generateId(), memberId, guestSessionId, locale)));
    }

    public ConversationInfo getForMember(long id, long memberId) {
        validateMember(memberId);
        return ConversationInfo.from(repository.findByIdAndMemberId(id, memberId).orElseThrow(ConversationProcessor::missing));
    }

    public ConversationInfo getForGuest(long id, GuestSessionIdentity guest) {
        return ConversationInfo.from(repository.findByIdAndGuestSessionId(id, guest.id()).orElseThrow(ConversationProcessor::missing));
    }

    private static void validateMember(long memberId) {
        if (memberId <= 0) {
            throw new ConversationException(ConversationErrorCode.INVALID_OWNER);
        }
    }

    private static ConversationException missing() {
        return new ConversationException(ConversationErrorCode.NOT_FOUND);
    }
}
