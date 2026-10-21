package com.commercefoundry.domainlayer.order.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 주문 배송지 스냅숏 — 주문과 1:1 (PK = 주문 아이디). 개인정보(이름 · 연락처 · 주소)를 이 테이블 하나에 모아 보존 · 파기 · 암호화를 여기서 끝낸다
 * (후속 이슈). 값은 받은 원문(앞뒤 공백만 자름) 그대로다 — 화면 · 메일 · CSV 는 텍스트로 이스케이프한다.
 *
 * <p>주문 아이디를 직접 넣고 {@code @Version} 이 없어 {@link Persistable} 로 "감사 시각이 없으면 새 행" 이라고 알린다.
 */
@Entity
@Table(name = "order_shipping_address", comment = "주문 배송지 스냅숏 (주문과 1:1, 개인정보)")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class OrderShippingAddressEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "order_id", comment = "주문 아이디 (FK: orders.id)")
    private Long orderId;

    @Column(name = "recipient_name", nullable = false, length = 50, comment = "받는 사람 (개인정보)")
    private String recipientName;

    @Column(name = "recipient_phone", nullable = false, length = 20, comment = "받는 사람 연락처 (개인정보)")
    private String recipientPhone;

    @Column(name = "country_code", nullable = false, length = 2, comment = "배송 국가 (ISO 3166-1 alpha-2)")
    private String countryCode;

    @Column(name = "postal_code", length = 20, comment = "우편번호. 국내는 필수, 해외는 없으면 null")
    private String postalCode;

    @Column(name = "address_line1", nullable = false, length = 200, comment = "기본 주소 (개인정보)")
    private String addressLine1;

    @Column(name = "address_line2", length = 200, comment = "상세 주소 (개인정보). 없으면 null")
    private String addressLine2;

    @Column(name = "delivery_memo", length = 200, comment = "배송 요청 사항. 없으면 null")
    private String deliveryMemo;

    @Override
    public Long getId() {
        return orderId;
    }

    /** 감사 시각은 persist 때 채워진다 — 비어 있으면 아직 저장하지 않은 행이다. */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
