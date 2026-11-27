package com.commercefoundry.domainlayer.conversation.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
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
        assertThat(Conversation.create(3, 2L, null, ConversationLocale.KO).assigneeMemberId()).isNull();
        assertThat(Conversation.create(3, 2L, null, ConversationLocale.KO).handoffReason()).isNull();
    }

    private static final Instant NOW = Instant.parse("2026-11-27T01:00:00Z");
    private static final long ADMIN = 77L;

    private enum Action {
        HANDOFF_CUSTOMER, HANDOFF_GUEST, HANDOFF_SYSTEM, HANDOFF_ADMIN, ASSIGN, RESOLVE, CLOSE, SYSTEM_CLOSE
    }

    private static Conversation inStatus(ConversationStatus status, boolean guest) {
        return new Conversation(10, guest ? null : 5L, guest ? 6L : null, ConversationLocale.KO, ConversationChannel.WEB, status, 3, 4L);
    }

    private static Conversation.Transition apply(Conversation conversation, Action action) {
        return switch (action) {
            case HANDOFF_CUSTOMER -> conversation.requestHandoff(ConversationActorType.CUSTOMER, HandoffReason.CUSTOMER_REQUEST, NOW);
            case HANDOFF_GUEST -> conversation.requestHandoff(ConversationActorType.GUEST, HandoffReason.CUSTOMER_REQUEST, NOW);
            case HANDOFF_SYSTEM -> conversation.requestHandoff(ConversationActorType.SYSTEM, HandoffReason.AI_FAILURE, NOW);
            case HANDOFF_ADMIN -> conversation.requestHandoff(ConversationActorType.ADMIN, HandoffReason.AI_DECISION, NOW);
            case ASSIGN -> conversation.assign(ADMIN, NOW);
            case RESOLVE -> conversation.resolve(ADMIN, NOW);
            case CLOSE -> conversation.close(ADMIN, NOW);
            case SYSTEM_CLOSE -> conversation.systemClose(NOW);
        };
    }

    private static ConversationStatus expectedTarget(ConversationStatus from, Action action) {
        return switch (from) {
            case AI_ACTIVE -> switch (action) {
                case HANDOFF_CUSTOMER, HANDOFF_GUEST, HANDOFF_SYSTEM -> ConversationStatus.WAITING_AGENT;
                default -> null;
            };
            case WAITING_AGENT -> switch (action) {
                case ASSIGN -> ConversationStatus.HUMAN_ACTIVE;
                case CLOSE -> ConversationStatus.CLOSED;
                default -> null;
            };
            case HUMAN_ACTIVE -> switch (action) {
                case RESOLVE -> ConversationStatus.RESOLVED;
                case CLOSE -> ConversationStatus.CLOSED;
                default -> null;
            };
            case RESOLVED -> action == Action.SYSTEM_CLOSE ? ConversationStatus.CLOSED : null;
            case CLOSED -> null;
        };
    }

    @Test
    void everyStatusAndActionFollowsTheTransitionTable() {
        // 같은 상태 재요청(예: WAITING_AGENT 에서 다시 이관)도 도메인은 거절한다. 멱등(200 무변경) 판정은 Processor 몫이다.
        for (ConversationStatus from : ConversationStatus.values()) {
            for (Action action : Action.values()) {
                Conversation conversation = inStatus(from, action == Action.HANDOFF_GUEST);
                ConversationStatus target = expectedTarget(from, action);
                if (target == null) {
                    assertThatThrownBy(() -> apply(conversation, action)).as("%s by %s", from, action)
                        .isInstanceOf(ConversationTransitionException.class)
                        .satisfies(e -> assertThat(((ConversationTransitionException) e).from()).isEqualTo(from));
                    continue;
                }
                Conversation.Transition transition = apply(conversation, action);
                assertThat(transition.conversation().status()).as("%s by %s", from, action).isEqualTo(target);
                assertThat(transition.conversation().version()).isEqualTo(4L);
                assertThat(transition.conversation().lastSeq()).isEqualTo(3L);
                assertThat(transition.event().conversationId()).isEqualTo(10L);
                assertThat(transition.event().fromStatus()).isEqualTo(from);
                assertThat(transition.event().toStatus()).isEqualTo(target);
                assertThat(transition.event().occurredAt()).isEqualTo(NOW);
            }
        }
    }

    @Test
    void handoffRecordsReasonTimeAndActor() {
        Conversation.Transition member = inStatus(ConversationStatus.AI_ACTIVE, false)
            .requestHandoff(ConversationActorType.CUSTOMER, HandoffReason.CUSTOMER_REQUEST, NOW);
        assertThat(member.conversation().handoffReason()).isEqualTo(HandoffReason.CUSTOMER_REQUEST);
        assertThat(member.conversation().handoffRequestedAt()).isEqualTo(NOW);
        assertThat(member.conversation().assigneeMemberId()).isNull();
        assertThat(member.event().kind()).isEqualTo(ConversationEventKind.HANDOFF_REQUESTED);
        assertThat(member.event().actorType()).isEqualTo(ConversationActorType.CUSTOMER);
        assertThat(member.event().actorMemberId()).isEqualTo(5L);
        assertThat(member.event().reason()).isEqualTo(HandoffReason.CUSTOMER_REQUEST);
        assertThat(member.event().assigneeMemberId()).isNull();

        Conversation.Transition guest = inStatus(ConversationStatus.AI_ACTIVE, true)
            .requestHandoff(ConversationActorType.GUEST, HandoffReason.CUSTOMER_REQUEST, NOW);
        assertThat(guest.event().actorType()).isEqualTo(ConversationActorType.GUEST);
        assertThat(guest.event().actorMemberId()).isNull();

        Conversation.Transition system = inStatus(ConversationStatus.AI_ACTIVE, false)
            .requestHandoff(ConversationActorType.SYSTEM, HandoffReason.AI_DECISION, NOW);
        assertThat(system.event().actorType()).isEqualTo(ConversationActorType.SYSTEM);
        assertThat(system.event().actorMemberId()).isNull();
        assertThat(system.conversation().handoffReason()).isEqualTo(HandoffReason.AI_DECISION);
    }

    @Test
    void handoffRejectsMismatchedActorAndReason() {
        Conversation member = inStatus(ConversationStatus.AI_ACTIVE, false);
        Conversation guest = inStatus(ConversationStatus.AI_ACTIVE, true);
        assertThatThrownBy(() -> member.requestHandoff(ConversationActorType.CUSTOMER, HandoffReason.AI_FAILURE, NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> member.requestHandoff(ConversationActorType.SYSTEM, HandoffReason.CUSTOMER_REQUEST, NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> member.requestHandoff(ConversationActorType.GUEST, HandoffReason.CUSTOMER_REQUEST, NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guest.requestHandoff(ConversationActorType.CUSTOMER, HandoffReason.CUSTOMER_REQUEST, NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assignSetsAcceptingAdminAndLaterTransitionsKeepHandoffFields() {
        Conversation waiting = inStatus(ConversationStatus.AI_ACTIVE, false)
            .requestHandoff(ConversationActorType.CUSTOMER, HandoffReason.CUSTOMER_REQUEST, NOW).conversation();
        Conversation.Transition assigned = waiting.assign(ADMIN, NOW.plusSeconds(60));
        assertThat(assigned.conversation().assigneeMemberId()).isEqualTo(ADMIN);
        assertThat(assigned.conversation().handoffRequestedAt()).isEqualTo(NOW);
        assertThat(assigned.event().kind()).isEqualTo(ConversationEventKind.ASSIGNED);
        assertThat(assigned.event().actorType()).isEqualTo(ConversationActorType.ADMIN);
        assertThat(assigned.event().actorMemberId()).isEqualTo(ADMIN);
        assertThat(assigned.event().assigneeMemberId()).isEqualTo(ADMIN);
        assertThat(assigned.event().reason()).isNull();

        Conversation.Transition resolved = assigned.conversation().resolve(88L, NOW.plusSeconds(120));
        assertThat(resolved.conversation().assigneeMemberId()).isEqualTo(ADMIN);
        assertThat(resolved.conversation().handoffReason()).isEqualTo(HandoffReason.CUSTOMER_REQUEST);
        assertThat(resolved.event().kind()).isEqualTo(ConversationEventKind.STATUS_CHANGED);
        assertThat(resolved.event().actorMemberId()).isEqualTo(88L);
        assertThat(resolved.event().assigneeMemberId()).isNull();

        Conversation.Transition systemClosed = resolved.conversation().systemClose(NOW.plusSeconds(180));
        assertThat(systemClosed.conversation().status()).isEqualTo(ConversationStatus.CLOSED);
        assertThat(systemClosed.event().actorType()).isEqualTo(ConversationActorType.SYSTEM);
        assertThat(systemClosed.event().actorMemberId()).isNull();

        assertThat(assigned.conversation().advanceSequence().assigneeMemberId()).isEqualTo(ADMIN);
    }

    @Test
    void handoffFieldsMustComeTogetherAndAssigneeMustBePositive() {
        assertThatThrownBy(() -> new Conversation(1, 1L, null, ConversationLocale.KO, ConversationChannel.WEB, ConversationStatus.WAITING_AGENT,
            0, null, null, HandoffReason.CUSTOMER_REQUEST, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Conversation(1, 1L, null, ConversationLocale.KO, ConversationChannel.WEB, ConversationStatus.HUMAN_ACTIVE,
            0, null, 0L, null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
