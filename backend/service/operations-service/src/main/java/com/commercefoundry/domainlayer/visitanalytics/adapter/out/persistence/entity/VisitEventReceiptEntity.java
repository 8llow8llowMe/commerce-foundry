package com.commercefoundry.domainlayer.visitanalytics.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** JDBC 수집 어댑터가 쓰는 중복 방지 영수증의 스키마를 정의한다. */
@Entity
@Immutable
@Table(name = "visit_event_receipt", comment = "방문 이벤트 중복 방지 영수증",
    indexes = @Index(name = "idx_visit_event_receipt_visit_day", columnList = "visit_day"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VisitEventReceiptEntity {

    @Id
    @Column(name = "event_id", nullable = false, comment = "클라이언트가 생성한 무작위 이벤트 UUID")
    private UUID eventId;

    @Column(name = "visit_day", nullable = false, comment = "한국 시간 기준 수집일")
    private LocalDate visitDay;
}
