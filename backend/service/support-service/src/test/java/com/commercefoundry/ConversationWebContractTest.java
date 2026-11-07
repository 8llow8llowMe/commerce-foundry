package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.conversation.adapter.in.web.dto.response.ConversationWebResponses;
import com.commercefoundry.domainlayer.conversation.adapter.in.web.presenter.ConversationWebPresenter;
import com.commercefoundry.domainlayer.conversation.application.info.ConversationInfo;
import com.commercefoundry.domainlayer.conversation.application.info.MessageInfo;
import com.commercefoundry.domainlayer.conversation.domain.model.ConversationChannel;
import com.commercefoundry.domainlayer.conversation.domain.model.ConversationLocale;
import com.commercefoundry.domainlayer.conversation.domain.model.ConversationStatus;
import com.commercefoundry.domainlayer.conversation.domain.model.MessageAuthorType;
import com.commercefoundry.domainlayer.conversation.domain.model.MessageStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ConversationWebContractTest {
    @Test
    void swaggerDeclaresEveryStableControlCodeAsAString() throws Exception {
        assertCodes(ConversationWebResponses.Detail.class, "locale", ConversationLocale.values());
        assertCodes(ConversationWebResponses.Detail.class, "channel", ConversationChannel.values());
        assertCodes(ConversationWebResponses.Detail.class, "status", ConversationStatus.values());
        assertCodes(ConversationWebResponses.Message.class, "authorType", MessageAuthorType.values());
        assertCodes(ConversationWebResponses.Message.class, "status", MessageStatus.values());
    }

    @Test
    void presenterRetainsStringIdentifiersAndControlCodesWithoutMetadata() {
        var presenter = new ConversationWebPresenter();
        for (ConversationStatus state : ConversationStatus.values()) {
            var detail = presenter.detail(new ConversationInfo(123, ConversationLocale.KO, ConversationChannel.WEB, state, 1));
            assertThat(detail.id()).isEqualTo("123");
            assertThat(detail.locale()).isEqualTo("KO");
            assertThat(detail.channel()).isEqualTo("WEB");
            assertThat(detail.status()).isEqualTo(state.name());
        }
        var message = presenter.message(new MessageInfo(456, 123, 1, MessageAuthorType.CUSTOMER, MessageStatus.COMPLETED, "masked"));
        assertThat(message.id()).isEqualTo("456");
        assertThat(message.conversationId()).isEqualTo("123");
        assertThat(message.authorType()).isEqualTo("CUSTOMER");
        assertThat(message.status()).isEqualTo("COMPLETED");
    }

    private static void assertCodes(Class<?> response, String field, Enum<?>[] values) throws Exception {
        var accessor = response.getDeclaredMethod(field);
        assertThat(accessor.getReturnType()).isEqualTo(String.class);
        Schema schema = accessor.getAnnotation(Schema.class);
        assertThat(schema).isNotNull();
        assertThat(schema.allowableValues()).containsExactly(Arrays.stream(values).map(Enum::name).toArray(String[]::new));
    }
}
