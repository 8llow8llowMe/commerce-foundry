package com.commercefoundry.domainlayer.conversation.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

class MessageTest {
    private static final String KEY = "12345678-1234-1234-1234-123456789abc";

    @Test
    void identifiersSequenceAndOriginalActorAreInvariant() {
        for (long[] values : new long[][] {{0, 2, 1}, {1, 0, 1}, {1, 2, 0}, {1, 2, -1}}) {
            assertThatThrownBy(() -> message(values[0], values[1], values[2], 3L, null)).isInstanceOf(IllegalArgumentException.class);
        }
        for (Long[] actor : new Long[][] {{null, null}, {1L, 2L}, {0L, null}, {null, -1L}}) {
            assertThatThrownBy(() -> message(1, 2, 1, actor[0], actor[1])).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(message(1, 2, 1, null, 3L).authorGuestSessionId()).isEqualTo(3);
        assertThat(message(1, 2, 1, 3L, null).toString()).doesNotContain(KEY, "body");
    }

    @Test
    void sequenceOverflowCannotWrap() {
        var conversation = new Conversation(1, 2L, null, ConversationLocale.KO, ConversationChannel.WEB, ConversationStatus.AI_ACTIVE, Long.MAX_VALUE, 1L);
        assertThatThrownBy(conversation::advanceSequence).isInstanceOf(ArithmeticException.class);
        assertThat(conversation.lastSeq()).isEqualTo(Long.MAX_VALUE);
    }

    private Message message(long id, long conversation, long seq, Long member, Long guest) {
        return new Message(id, conversation, seq, MessageAuthorType.CUSTOMER, member, guest, MessageStatus.COMPLETED, KEY, "body");
    }
}
