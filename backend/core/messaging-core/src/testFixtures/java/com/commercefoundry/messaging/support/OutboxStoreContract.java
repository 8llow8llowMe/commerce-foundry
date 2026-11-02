package com.commercefoundry.messaging.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.messaging.outbox.OutboxRow;
import com.commercefoundry.messaging.outbox.OutboxStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OutboxStore} SPI 계약 — 서비스마다 둔 outbox 어댑터가 릴레이 · 정리가 기대하는 대로 움직이는지 본다 (README "서비스가 둘 테이블").
 * 서비스 테스트가 상속해 {@link #outboxStore()} 를 넘긴다 — JPA 어댑터는 {@code @DataJpaTest}(PostgreSQL)로, {@link InMemoryOutboxStore} 는 모듈 자체 테스트로 돈다.
 *
 * <p>{@code append} 는 호출자 트랜잭션에 합류한다 — 상속한 테스트가 테스트마다 트랜잭션을 열어 둔다(JPA 는 {@code @DataJpaTest} 기본). 트랜잭션 밖 append 거절 ·
 * 실패 코드 칸 길이 · 지표 쿼리처럼 저장 기술에 기대는 것은 상속한 쪽이 따로 본다.
 */
public abstract class OutboxStoreContract {

    protected static final Instant NOW = Instant.parse("2026-10-24T09:00:00Z");

    /** 테스트마다 빈 저장소. */
    protected abstract OutboxStore outboxStore();

    /** 저장소에 남은 전체 행 수(발행 여부와 상관없이) — SPI 로는 발행된 행이 보이지 않아 정리 결과를 이 값으로 본다. */
    protected abstract long totalRows();

    /** 행의 발행 시각(아직이면 null) — SPI 로는 보이지 않아 한 번만 표시되는지를 이 값으로 본다. */
    protected abstract Instant publishedAt(long id);

    @Test
    @DisplayName("보낼 행: 미발행이고 발행 가능 시각이 지난 행만 아이디 오름차순으로 limit 건까지 — 봉투 · 종류 · 버전 · 시도 수를 그대로 돌려준다")
    void findPublishable() {
        OutboxStore store = outboxStore();
        store.append(row("1", NOW.minusSeconds(30)));
        store.append(row("2", NOW));
        store.append(row("3", NOW.plusSeconds(60)));
        store.append(row("4", NOW.minusSeconds(60)));

        List<OutboxRow> publishable = store.findPublishable(NOW, 10);

        assertThat(publishable).extracting(OutboxRow::aggregateId).containsExactly("1", "2", "4");
        assertThat(publishable).extracting(OutboxRow::id).isSorted();
        assertThat(publishable.getFirst().envelopeJson()).isEqualTo("{\"eventId\":\"1\"}");
        assertThat(publishable.getFirst().aggregateType()).isEqualTo("contract");
        assertThat(publishable.getFirst().eventType()).isEqualTo("contract-happened");
        assertThat(publishable.getFirst().schemaVersion()).isEqualTo(1);
        assertThat(publishable.getFirst().availableAt()).isEqualTo(NOW.minusSeconds(30));
        assertThat(publishable.getFirst().publishAttempts()).isZero();
        assertThat(store.findPublishable(NOW, 2)).hasSize(2);
    }

    @Test
    @DisplayName("발행 표시는 한 번만 바뀐다 — 두 번째는 false 이고 발행 시각은 첫 값 그대로이며, 표시된 행은 다시 보낼 행이 아니다")
    void markPublishedOnce() {
        OutboxStore store = outboxStore();
        store.append(row("1", NOW));
        long id = store.findPublishable(NOW, 10).getFirst().id();

        assertThat(store.markPublished(id, NOW)).isTrue();
        assertThat(store.markPublished(id, NOW.plusSeconds(1))).isFalse();
        assertThat(publishedAt(id)).isEqualTo(NOW);
        assertThat(store.findPublishable(NOW.plusSeconds(10), 10)).isEmpty();
    }

    @Test
    @DisplayName("발행 실패는 시도 수를 올린다 — 행은 다음 주기에 다시 보낼 행이다")
    void markFailed() {
        OutboxStore store = outboxStore();
        store.append(row("1", NOW));
        long id = store.findPublishable(NOW, 10).getFirst().id();

        store.markFailed(id, "TimeoutException");
        store.markFailed(id, "TimeoutException");

        assertThat(store.findPublishable(NOW, 10)).extracting(OutboxRow::publishAttempts).containsExactly(2);
    }

    @Test
    @DisplayName("정리: 보존 기간 전에 발행된 행만 limit 건씩 지운다 — 미발행 · 최근 발행 행은 남는다")
    void deletePublishedBefore() {
        OutboxStore store = outboxStore();
        for (int index = 1; index <= 4; index++) {
            store.append(row(String.valueOf(index), NOW));
        }
        List<OutboxRow> rows = store.findPublishable(NOW, 10);
        store.markPublished(rows.get(0).id(), NOW.minusSeconds(3600));
        store.markPublished(rows.get(1).id(), NOW.minusSeconds(3600));
        store.markPublished(rows.get(2).id(), NOW);

        assertThat(store.deletePublishedBefore(NOW.minusSeconds(60), 1)).isEqualTo(1);
        assertThat(store.deletePublishedBefore(NOW.minusSeconds(60), 10)).isEqualTo(1);
        assertThat(store.deletePublishedBefore(NOW.minusSeconds(60), 10)).isZero();
        // 최근 발행 행(rows[2])과 미발행 행(rows[3])이 남는다
        assertThat(totalRows()).isEqualTo(2);
        assertThat(publishedAt(rows.get(2).id())).isEqualTo(NOW);
        assertThat(store.findPublishable(NOW, 10)).extracting(OutboxRow::id).containsExactly(rows.get(3).id());
    }

    /** 계약용 행 — 봉투 JSON 은 내용이 아니라 그대로 돌아오는지만 본다. */
    protected static OutboxRow row(String aggregateId, Instant availableAt) {
        return new OutboxRow(null, "contract", aggregateId, "contract-happened", 1, "{\"eventId\":\"" + aggregateId + "\"}", availableAt, 0);
    }
}
