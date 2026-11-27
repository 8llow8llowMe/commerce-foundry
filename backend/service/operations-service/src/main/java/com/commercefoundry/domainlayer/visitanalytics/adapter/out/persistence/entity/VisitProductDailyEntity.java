package com.commercefoundry.domainlayer.visitanalytics.adapter.out.persistence.entity;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** JDBC 수집 어댑터에서 원자적으로 갱신하는 일별 집계의 스키마를 정의한다. */
@Entity
@IdClass(VisitProductDailyEntity.Key.class)
@Table(name = "visit_product_daily", comment = "상품별 일간 조회수",
    indexes = @Index(name = "idx_visit_product_daily_visit_day", columnList = "visit_day"),
    check = @CheckConstraint(name = "ck_visit_product_daily_counts", constraint = "product_id > 0 and view_count >= 0"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VisitProductDailyEntity {

    @Id
    @Column(name = "visit_day", nullable = false, comment = "한국 시간 기준 수집일")
    private LocalDate visitDay;

    @Id
    @Column(name = "product_id", nullable = false, comment = "상품 식별자 (외부 참조: product-service product.id)")
    private long productId;

    @Column(name = "view_count", nullable = false, comment = "동의 후 집계한 조회수")
    private long viewCount;

    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private LocalDate visitDay;
        private long productId;
    }
}
