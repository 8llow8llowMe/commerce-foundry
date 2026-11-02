package com.commercefoundry.messaging.support;

import com.commercefoundry.messaging.outbox.OutboxStore;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;

/**
 * 가짜 outbox 저장소가 실제 어댑터와 같은 계약을 지키는지 — 서비스 단위 테스트가 이 가짜로 발행을 단언하므로 계약이 어긋나면 안 된다.
 * Docker 없이 돌아 계약 자체가 로컬에서도 검증된다(서비스 JPA 어댑터 계약 테스트는 PostgreSQL 이 없으면 건너뛴다).
 */
class InMemoryOutboxStoreContractTest extends OutboxStoreContract {

    private InMemoryOutboxStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryOutboxStore();
    }

    @Override
    protected OutboxStore outboxStore() {
        return store;
    }

    @Override
    protected long totalRows() {
        return store.rows().size();
    }

    @Override
    protected Instant publishedAt(long id) {
        return store.publishedAt(id);
    }
}
