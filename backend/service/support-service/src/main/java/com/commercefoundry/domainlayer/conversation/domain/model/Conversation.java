package com.commercefoundry.domainlayer.conversation.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record Conversation(long id, Long memberId, Long guestSessionId, ConversationLocale locale, ConversationChannel channel,
                           ConversationStatus status, long lastSeq, Long version, Long assigneeMemberId, HandoffReason handoffReason,
                           Instant handoffRequestedAt) {

    /** 허용 전이 표: 현재 상태 → 다음 상태 → 허용 행위자. 표 밖은 {@link ConversationTransitionException}. */
    private static final Map<ConversationStatus, Map<ConversationStatus, Set<ConversationActorType>>> TRANSITIONS = Map.of(
        ConversationStatus.AI_ACTIVE, Map.of(ConversationStatus.WAITING_AGENT,
            Set.of(ConversationActorType.CUSTOMER, ConversationActorType.GUEST, ConversationActorType.SYSTEM)),
        ConversationStatus.WAITING_AGENT, Map.of(ConversationStatus.HUMAN_ACTIVE, Set.of(ConversationActorType.ADMIN),
            ConversationStatus.CLOSED, Set.of(ConversationActorType.ADMIN)),
        ConversationStatus.HUMAN_ACTIVE, Map.of(ConversationStatus.RESOLVED, Set.of(ConversationActorType.ADMIN),
            ConversationStatus.CLOSED, Set.of(ConversationActorType.ADMIN)),
        ConversationStatus.RESOLVED, Map.of(ConversationStatus.CLOSED, Set.of(ConversationActorType.SYSTEM)),
        ConversationStatus.CLOSED, Map.of());

    public Conversation {
        if (id <= 0 || (memberId == null) == (guestSessionId == null) || memberId != null && memberId <= 0
            || guestSessionId != null && guestSessionId <= 0 || lastSeq < 0 || assigneeMemberId != null && assigneeMemberId <= 0
            || (handoffReason == null) != (handoffRequestedAt == null)) {
            throw new IllegalArgumentException("Invalid conversation state");
        }
        Objects.requireNonNull(locale);
        Objects.requireNonNull(channel);
        Objects.requireNonNull(status);
    }

    /** 이관 칸이 없던 기존 생성 · 매핑 경로용. */
    public Conversation(long id, Long memberId, Long guestSessionId, ConversationLocale locale, ConversationChannel channel,
                        ConversationStatus status, long lastSeq, Long version) {
        this(id, memberId, guestSessionId, locale, channel, status, lastSeq, version, null, null, null);
    }

    public static Conversation create(long id, Long memberId, Long guestSessionId, ConversationLocale locale) {
        return new Conversation(id, memberId, guestSessionId, locale, ConversationChannel.WEB, ConversationStatus.AI_ACTIVE, 0, null);
    }

    public static boolean isAllowed(ConversationStatus from, ConversationStatus to, ConversationActorType actorType) {
        return TRANSITIONS.getOrDefault(from, Map.of()).getOrDefault(to, Set.of()).contains(actorType);
    }

    public Conversation advanceSequence() {
        return new Conversation(id, memberId, guestSessionId, locale, channel, status, Math.addExact(lastSeq, 1), version, assigneeMemberId,
            handoffReason, handoffRequestedAt);
    }

    /**
     * AI_ACTIVE → WAITING_AGENT. 고객(회원) · 비회원은 CUSTOMER_REQUEST, 시스템은 AI_DECISION · AI_FAILURE 만 쓴다.
     * 비회원 허용 플래그 · 소유 확인은 Processor 몫이다.
     */
    public Transition requestHandoff(ConversationActorType actorType, HandoffReason reason, Instant now) {
        Objects.requireNonNull(actorType);
        Objects.requireNonNull(reason);
        Objects.requireNonNull(now);
        boolean customerActor = actorType == ConversationActorType.CUSTOMER || actorType == ConversationActorType.GUEST;
        if (actorType != ConversationActorType.ADMIN && (customerActor != (reason == HandoffReason.CUSTOMER_REQUEST)
            || actorType == ConversationActorType.CUSTOMER && memberId == null
            || actorType == ConversationActorType.GUEST && guestSessionId == null)) {
            throw new IllegalArgumentException("Handoff actor and reason do not match");
        }
        ensureAllowed(ConversationStatus.WAITING_AGENT, actorType);
        Long actorMemberId = actorType == ConversationActorType.CUSTOMER ? memberId : null;
        Conversation next = new Conversation(id, memberId, guestSessionId, locale, channel, ConversationStatus.WAITING_AGENT, lastSeq, version,
            null, reason, now);
        return new Transition(next, new ConversationEvent(id, ConversationEventKind.HANDOFF_REQUESTED, status, next.status(), actorType,
            actorMemberId, null, reason, now));
    }

    /** WAITING_AGENT → HUMAN_ACTIVE. 담당자는 수락한 어드민 본인이다. */
    public Transition assign(long adminMemberId, Instant now) {
        Objects.requireNonNull(now);
        ensureAllowed(ConversationStatus.HUMAN_ACTIVE, ConversationActorType.ADMIN);
        Conversation next = withStatus(ConversationStatus.HUMAN_ACTIVE, adminMemberId);
        return new Transition(next, new ConversationEvent(id, ConversationEventKind.ASSIGNED, status, next.status(), ConversationActorType.ADMIN,
            adminMemberId, adminMemberId, null, now));
    }

    /** HUMAN_ACTIVE → RESOLVED. */
    public Transition resolve(long adminMemberId, Instant now) {
        return changeStatus(ConversationStatus.RESOLVED, ConversationActorType.ADMIN, adminMemberId, now);
    }

    /** HUMAN_ACTIVE · WAITING_AGENT(스팸 · 중복) → CLOSED. */
    public Transition close(long adminMemberId, Instant now) {
        return changeStatus(ConversationStatus.CLOSED, ConversationActorType.ADMIN, adminMemberId, now);
    }

    /** RESOLVED → CLOSED. 시스템 전용(표에만 두고 호출 경로는 아직 없다). */
    public Transition systemClose(Instant now) {
        return changeStatus(ConversationStatus.CLOSED, ConversationActorType.SYSTEM, null, now);
    }

    private Transition changeStatus(ConversationStatus to, ConversationActorType actorType, Long actorMemberId, Instant now) {
        Objects.requireNonNull(now);
        ensureAllowed(to, actorType);
        Conversation next = withStatus(to, assigneeMemberId);
        return new Transition(next, new ConversationEvent(id, ConversationEventKind.STATUS_CHANGED, status, to, actorType, actorMemberId, null,
            null, now));
    }

    /** 같은 상태 재요청도 표 밖이라 거절한다. 멱등(200 무변경) 판정은 Processor 몫이다. */
    private void ensureAllowed(ConversationStatus to, ConversationActorType actorType) {
        if (!isAllowed(status, to, actorType)) {
            throw new ConversationTransitionException(status, to, actorType);
        }
    }

    private Conversation withStatus(ConversationStatus to, Long assignee) {
        return new Conversation(id, memberId, guestSessionId, locale, channel, to, lastSeq, version, assignee, handoffReason, handoffRequestedAt);
    }

    /** 전이 결과: 저장할 새 대화와 같은 트랜잭션에 기록할 이벤트. */
    public record Transition(Conversation conversation, ConversationEvent event) {
        public Transition {
            Objects.requireNonNull(conversation);
            Objects.requireNonNull(event);
        }
    }
}
