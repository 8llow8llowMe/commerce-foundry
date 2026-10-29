package com.commercefoundry.global.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * outbox 릴레이 · 발행 완료 행 정리의 단일 실행 락 (ShedLock JDBC 표준 모양, messaging-core README). 행은 ShedLock({@code JdbcLockProvider})이 직접 읽고 쓴다 — 앱 코드는 이 엔티티로
 * 읽거나 쓰지 않는다. 테이블을 DDL 스냅숏 · local/dev {@code ddl-auto} 에 싣기 위해서만 둔다.
 *
 * <p>감사 칸 · 버전을 두지 않는다 — ShedLock 이 쓰는 칸 밖의 NOT NULL 칸이 있으면 락 INSERT 가 실패한다. 미결제 만료 스윕은 이 락을 쓰지 않는다 ({@code OrderExpiryScheduler}).
 */
@Entity
@Table(name = "shedlock", comment = "단일 실행 락 (ShedLock — outbox 릴레이 · 정리)")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShedLockEntity {

    @Id
    @Column(name = "name", length = 64, comment = "락 이름")
    private String name;

    @Column(name = "lock_until", nullable = false, comment = "이 시각까지 락을 쥔다")
    private Instant lockUntil;

    @Column(name = "locked_at", nullable = false, comment = "락을 잡은 시각")
    private Instant lockedAt;

    @Column(name = "locked_by", nullable = false, length = 255, comment = "락을 잡은 인스턴스 (호스트 이름)")
    private String lockedBy;
}
