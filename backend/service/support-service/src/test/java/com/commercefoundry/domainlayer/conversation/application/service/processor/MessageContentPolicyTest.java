package com.commercefoundry.domainlayer.conversation.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.commercefoundry.domainlayer.conversation.application.exception.ConversationErrorCode;
import com.commercefoundry.domainlayer.conversation.application.exception.ConversationException;
import org.junit.jupiter.api.Test;

class MessageContentPolicyTest {
    private final MessageContentPolicy policy = new MessageContentPolicy();

    @Test
    void canonicalMaskIsNormalizedStrippedAndDoesNotRetainCredentials() {
        assertThat(policy.canonicalize("  ０１０－１２３４－５６７８  ")).isEqualTo("[PHONE]");
        assertThat(policy.canonicalize("reply to sample@example.test")).isEqualTo("reply to [EMAIL]");
        assertThat(policy.canonicalize("010-1111-2222")).isEqualTo(policy.canonicalize("010-3333-4444"));
        assertThat(policy.canonicalize("hello")).isEqualTo("hello");
        assertThat(policy.canonicalize("hello\n\tworld")).isEqualTo("hello\n\tworld");
    }

    @Test
    void rawAndCanonicalLengthAndBlankAreBothValidated() {
        for (String raw : new String[] {null, "", " \n", "x".repeat(2001), "ﬃ".repeat(667), "\u3000", "a\u0000b"}) {
            assertThatThrownBy(() -> policy.canonicalize(raw)).isInstanceOfSatisfying(ConversationException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ConversationErrorCode.INVALID_MESSAGE_CONTENT));
        }
        assertThat(policy.canonicalize("x".repeat(2000))).hasSize(2000);
        assertThat(policy.canonicalize("😀".repeat(1000))).hasSize(2000);
    }

    @Test
    void onlyCanonicalLowercaseUuidIsAcceptedWithoutVersionRestriction() {
        policy.validateKey("12345678-1234-1234-1234-123456789abc");
        for (String key : new String[] {null, "", "12345678-1234-1234-1234-123456789ABC", "1-1-1-1-1", "12345678123412341234123456789abc"}) {
            assertThatThrownBy(() -> policy.validateKey(key)).isInstanceOfSatisfying(ConversationException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ConversationErrorCode.INVALID_MESSAGE_KEY));
        }
    }
}
