package com.commercefoundry.domainlayer.conversation.adapter.in.web.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/** Ownership is checked before the existing processor validates content. */
public record ConversationMessageRequest(
    @Schema(description = "고객 메시지 본문, UTF-16 1~2000자; NUL 금지")
    String content
) {
    @Override
    public String toString() { return "ConversationMessageRequest[content=REDACTED]"; }
}
