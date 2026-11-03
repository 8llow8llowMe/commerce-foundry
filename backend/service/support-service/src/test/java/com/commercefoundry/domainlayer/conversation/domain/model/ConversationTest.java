package com.commercefoundry.domainlayer.conversation.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

class ConversationTest {
    @Test
    void exactlyOnePositiveOwnerAndPositiveIdAreRequired() {
        for (Long[] owners : new Long[][] {{null, null}, {1L, 2L}, {0L, null}, {-1L, null}, {null, 0L}, {null, -1L}}) {
            assertThatThrownBy(() -> Conversation.create(1, owners[0], owners[1], ConversationLocale.KO)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> Conversation.create(0, 1L, null, ConversationLocale.KO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Conversation(1, 1L, null, ConversationLocale.KO, ConversationChannel.WEB,
            ConversationStatus.AI_ACTIVE, -1, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void newConversationHasOnlyInitialStateAndNoMessageSequence() {
        for (var conversation : new Conversation[] {Conversation.create(1, 2L, null, ConversationLocale.KO),
            Conversation.create(2, null, 3L, ConversationLocale.EN)}) {
            assertThat(conversation.channel()).isEqualTo(ConversationChannel.WEB);
            assertThat(conversation.status()).isEqualTo(ConversationStatus.AI_ACTIVE);
            assertThat(conversation.lastSeq()).isZero();
            assertThat(conversation.version()).isNull();
        }
        assertThatThrownBy(() -> Conversation.create(1, 1L, null, null)).isInstanceOf(NullPointerException.class);
    }
}
