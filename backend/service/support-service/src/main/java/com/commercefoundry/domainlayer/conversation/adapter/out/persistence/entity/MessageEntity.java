package com.commercefoundry.domainlayer.conversation.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.conversation.domain.model.MessageAuthorType;
import com.commercefoundry.domainlayer.conversation.domain.model.MessageStatus;
import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

@Entity
@Immutable
@Table(name = "support_message", comment = "고객 상담 메시지", uniqueConstraints = {
    @UniqueConstraint(name = "uk_support_message_conversation_id_seq", columnNames = {"conversation_id", "seq"}),
    @UniqueConstraint(name = "uk_support_message_conversation_id_client_message_id", columnNames = {"conversation_id", "client_message_id"})
}, check = @CheckConstraint(name = "ck_support_message_state", constraint = "id > 0 and conversation_id > 0 and seq > 0 and length(trim(content)) > 0 and "
    + "((author_member_id is not null and author_member_id > 0 and author_guest_session_id is null) or "
    + "(author_member_id is null and author_guest_session_id is not null and author_guest_session_id > 0))"))
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MessageEntity extends BaseEntity implements Persistable<Long> {
    @Id
    @Column(comment = "메시지 아이디 (Snowflake)")
    private Long id;

    @Column(nullable = false, comment = "대화 아이디 (FK: support_conversation.id)")
    private long conversationId;

    @Column(nullable = false, comment = "대화별 단조 증가 순번")
    private long seq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, comment = "원 작성자 유형")
    private MessageAuthorType authorType;

    @Column(comment = "원 작성 회원 아이디 (외부 FK: auth-service member.id)")
    private Long authorMemberId;

    @Column(comment = "원 작성 비회원 세션 아이디 (FK: support_guest_session.id)")
    private Long authorGuestSessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, comment = "메시지 완료 상태")
    private MessageStatus status;

    @Column(nullable = false, length = 36, comment = "대화별 멱등 요청 키 (canonical lowercase UUID)")
    private String clientMessageId;

    @Column(nullable = false, length = 2000, comment = "NFKC 정규화 및 개인정보 마스킹된 본문")
    private String content;

    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
