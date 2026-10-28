package com.commercefoundry.domainlayer.order.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
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
 * (후속 이슈). 값은 받은 원문(앞뒤 공백만 자름, 연락처는 공백 · 하이픈을 뺌) 그대로다 — 화면 · 메일 · CSV 는 텍스트로 이스케이프한다.
 * 칸 이름 · 길이는 #7 주소록(auth-service {@code shipping_address})과 같다.
 *
 * <p>check 제약은 도메인 {@code ShippingAddress} 의 규칙(국가 코드 형식 · 국내 우편번호 필수 · 해외 도시 필수 · 국가별 연락처 형식)과 같다 — 다시 읽어 조립할 때
 * 같은 규칙을 지나므로, 운영 SQL 로 들어간 틀린 행이 조회 · 재생에서 500 이 되지 않게 쓰는 순간 거절한다. 문자 형식은 정규식 대신
 * {@code ltrim(col, '허용 문자') = ''} 로 본다 ({@code coding-conventions.md} §9-4).
 *
 * <p>주문 아이디를 직접 넣고 {@code @Version} 이 없어 {@link Persistable} 로 "감사 시각이 없으면 새 행" 이라고 알린다.
 */
@Entity
@Table(
    name = "order_shipping_address",
    comment = "주문 배송지 스냅숏 (주문과 1:1, 개인정보)",
    check = {
        @CheckConstraint(
            name = "ck_order_shipping_address_country_code_format",
            constraint = "char_length(country_code) = 2 and ltrim(country_code, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ') = ''"
        ),
        @CheckConstraint(name = "ck_order_shipping_address_postal_code_required", constraint = "country_code <> 'KR' or postal_code is not null"),
        @CheckConstraint(
            name = "ck_order_shipping_address_city_required",
            constraint = "country_code = 'KR' or (city is not null and trim(city) <> '')"
        ),
        @CheckConstraint(
            name = "ck_order_shipping_address_recipient_phone_format",
            constraint = "(country_code = 'KR' and char_length(recipient_phone) between 9 and 11"
                + " and substring(recipient_phone from 1 for 1) = '0' and ltrim(recipient_phone, '0123456789') = '')"
                + " or (country_code <> 'KR' and ((char_length(recipient_phone) between 7 and 15 and ltrim(recipient_phone, '0123456789') = '')"
                + " or (char_length(recipient_phone) between 8 and 16 and substring(recipient_phone from 1 for 1) = '+'"
                + " and ltrim(substring(recipient_phone from 2), '0123456789') = '')))"
        )
    }
)
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

    @Column(name = "recipient_phone", nullable = false, length = 20, comment = "받는 사람 연락처 — 공백 · 하이픈을 뺀 값 (국내 숫자 9~11자리 · 해외 E.164, 개인정보)")
    private String recipientPhone;

    @Column(name = "country_code", nullable = false, length = 2, comment = "배송 국가 (ISO 3166-1 alpha-2 대문자)")
    private String countryCode;

    @Column(name = "postal_code", length = 20, comment = "우편번호. 국내는 필수, 해외는 없으면 null")
    private String postalCode;

    @Column(name = "address_line1", nullable = false, length = 200, comment = "기본 주소 (개인정보)")
    private String addressLine1;

    @Column(name = "address_line2", length = 200, comment = "상세 주소 (개인정보). 없으면 null")
    private String addressLine2;

    @Column(name = "city", length = 100, comment = "도시 — 해외 배송지는 필수, 국내는 없으면 null")
    private String city;

    @Column(name = "state_province", length = 100, comment = "주 · 도 (state / province). 없으면 null")
    private String stateProvince;

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
