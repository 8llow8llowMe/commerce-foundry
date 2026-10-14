package com.commercefoundry.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 생성·수정 시각 감사 컬럼. 서비스의 {@code *FeaturesConfig} 가 {@code @EnableJpaAuditing} 을 켜야 채워진다.
 *
 * <p>시각은 {@link Instant}({@code timestamptz})로 둔다. 해외 주문이 같은 테이블에 들어오므로
 * 서버 시간대에 묶인 {@code LocalDateTime} 을 쓰지 않는다. 화면 시간대 변환은 Presenter 몫이다.
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false, comment = "생성 시각")
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false, comment = "수정 시각")
    private Instant updatedAt;
}
