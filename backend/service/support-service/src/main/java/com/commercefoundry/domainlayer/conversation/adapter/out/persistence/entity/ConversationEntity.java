package com.commercefoundry.domainlayer.conversation.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.conversation.domain.model.ConversationChannel;
import com.commercefoundry.domainlayer.conversation.domain.model.ConversationLocale;
import com.commercefoundry.domainlayer.conversation.domain.model.ConversationStatus;
import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "support_conversation", comment = "고객 상담 대화",
    check = @CheckConstraint(name = "ck_support_conversation_state", constraint = "id > 0 and last_seq >= 0 and "
        + "((member_id is not null and member_id > 0 and guest_session_id is null) or "
        + "(member_id is null and guest_session_id is not null and guest_session_id > 0))"))
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ConversationEntity extends BaseEntity {
    @Id
    @Column(comment = "대화 아이디 (Snowflake)")
    private Long id;

    @Column(comment = "회원 아이디 (외부 FK: auth-service member.id)")
    private Long memberId;

    @Column(comment = "비회원 세션 아이디 (FK: support_guest_session.id)")
    private Long guestSessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2, comment = "상담 언어")
    private ConversationLocale locale;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, comment = "상담 채널")
    private ConversationChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, comment = "상담 상태")
    private ConversationStatus status;

    @Column(nullable = false, comment = "마지막 메시지 순번, 신규 대화는 0")
    private long lastSeq;

    @Version
    @Column(nullable = false, comment = "낙관적 락 버전")
    private Long version;
}
