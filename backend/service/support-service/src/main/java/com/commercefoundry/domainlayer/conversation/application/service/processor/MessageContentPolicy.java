package com.commercefoundry.domainlayer.conversation.application.service.processor;

import com.commercefoundry.ai.masking.PiiMasker;
import com.commercefoundry.domainlayer.conversation.application.exception.ConversationErrorCode;
import com.commercefoundry.domainlayer.conversation.application.exception.ConversationException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MessageContentPolicy {
    private final PiiMasker masker = new PiiMasker(Map.of());

    public String canonicalize(String raw) {
        validate(raw);
        String canonical = masker.mask(raw, List.of()).strip();
        validate(canonical);
        return canonical;
    }

    public void validateKey(String key) {
        if (key == null || !key.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new ConversationException(ConversationErrorCode.INVALID_MESSAGE_KEY);
        }
    }

    private static void validate(String value) {
        if (value == null || value.isBlank() || value.length() > 2000 || value.indexOf('\u0000') >= 0) {
            throw new ConversationException(ConversationErrorCode.INVALID_MESSAGE_CONTENT);
        }
    }
}
