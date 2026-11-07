package com.commercefoundry.domainlayer.conversation.adapter.in.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public final class ConversationWebResponses {
    private ConversationWebResponses() { }

    public record Detail(
        @Schema(description = "대화 아이디")
        String id,

        @Schema(description = "대화 언어 선택 제어 코드; 표시 문구 아님", allowableValues = {"KO", "EN"})
        String locale,

        @Schema(description = "대화 채널 제어 코드; 표시 문구 아님", allowableValues = {"WEB"})
        String channel,

        @Schema(description = "입력·종료 동작 분기용 제어 코드; 표시 문구 아님",
            allowableValues = {"AI_ACTIVE", "WAITING_AGENT", "HUMAN_ACTIVE", "RESOLVED", "CLOSED"})
        String status,

        @Schema(description = "마지막 순번")
        long lastSeq
    ) { }

    public record Message(
        @Schema(description = "메시지 아이디")
        String id,

        @Schema(description = "대화 아이디")
        String conversationId,

        @Schema(description = "메시지 순번")
        long seq,

        @Schema(description = "고객 메시지 배치 제어 코드; 표시 문구 아님", allowableValues = {"CUSTOMER"})
        String authorType,

        @Schema(description = "메시지 완료 렌더링 제어 코드; 표시 문구 아님", allowableValues = {"COMPLETED"})
        String status,

        @Schema(description = "저장된 마스킹 본문")
        String content
    ) {
        @Override
        public String toString() {
            return "Message[id=" + id + ", conversationId=" + conversationId + ", seq=" + seq + "]";
        }
    }

    public record MessagePage(
        @Schema(description = "순번 오름차순 메시지")
        List<Message> messages,

        @Schema(description = "다음 페이지 존재 여부")
        boolean hasNext,

        @Schema(description = "다음 요청 커서")
        long nextCursor,

        @Schema(description = "관측한 마지막 순번")
        long lastSeq
    ) {
        public MessagePage { messages = List.copyOf(messages); }

        @Override
        public String toString() {
            return "MessagePage[count=" + messages.size() + ", nextCursor=" + nextCursor + "]";
        }
    }
}
