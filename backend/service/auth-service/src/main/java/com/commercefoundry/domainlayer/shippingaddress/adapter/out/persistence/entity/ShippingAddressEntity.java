package com.commercefoundry.domainlayer.shippingaddress.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원 배송지 주소록. 회원과는 {@code member_id}(raw FK)로만 이어진다.
 *
 * <p><b>기본 배송지 단일성은 DB 가 강제한다.</b> 기본 여부를 boolean 이 아니라 {@code default_member_id} 로 둔다 — 기본 배송지일 때만
 * {@code member_id} 와 같은 값이고 아니면 null 이다. 여기에 유니크 키({@code uk_shipping_address_default_member_id})를 걸면 null 은 여럿
 * 허용되므로 회원당 기본은 최대 하나가 된다. PostgreSQL 부분 유니크 인덱스({@code unique (member_id) where is_default})가 더 직접적이지만
 * JPA 애노테이션으로 선언할 수 없어 DDL 스냅숏 · {@code update} 로 만들어지지 않는다 — 그래서 이 방식을 쓴다.
 * {@code ck_shipping_address_default_member_id} 가 다른 회원 아이디가 들어가는 것을 막는다.
 *
 * <p>PostgreSQL 은 이 유니크 키를 즉시(행 단위) 검사한다 — deferrable 이 아니다. 기본을 옮길 때는 기존 기본을 null 로 푸는 UPDATE 가 새 기본을 지정하는 쓰기보다
 * 먼저 나가야 한다 — 순서가 바뀌면 두 행이 잠깐 같은 값을 가져 위반이다 ({@code ShippingAddressRepositoryAdapter}).
 */
@Entity
@Table(
    name = "shipping_address",
    comment = "회원 배송지 주소록",
    indexes = @Index(name = "idx_shipping_address_member_id", columnList = "member_id"),
    uniqueConstraints = @UniqueConstraint(name = ShippingAddressEntity.UK_DEFAULT_MEMBER_ID, columnNames = "default_member_id"),
    check = {
        @CheckConstraint(
            name = "ck_shipping_address_country_code",
            constraint = "char_length(country_code) = 2 and country_code = upper(country_code)"
        ),
        @CheckConstraint(
            name = "ck_shipping_address_default_member_id",
            constraint = "default_member_id is null or default_member_id = member_id"
        )
    }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ShippingAddressEntity extends BaseEntity {

    /** 회원당 기본 배송지는 하나. 어댑터가 위반을 수정 충돌(AUTH_022)로 바꾼다. */
    public static final String UK_DEFAULT_MEMBER_ID = "uk_shipping_address_default_member_id";

    @Id
    @Column(name = "id", comment = "배송지 아이디 (Snowflake)")
    private Long id;

    @Column(name = "member_id", nullable = false, comment = "회원 아이디 (FK: member.id)")
    private Long memberId;

    @Column(name = "label", length = 30, comment = "배송지 별칭 (집 · 회사). 없으면 null")
    private String label;

    @Column(name = "recipient_name", nullable = false, length = 50, comment = "받는 사람 이름")
    private String recipientName;

    @Column(name = "recipient_phone", nullable = false, length = 20, comment = "받는 사람 연락처 — 하이픈 · 공백을 뺀 값 (국내 숫자 · 해외 E.164)")
    private String recipientPhone;

    @Column(name = "country_code", nullable = false, length = 2, comment = "국가 코드 (ISO 3166-1 alpha-2 대문자)")
    private String countryCode;

    @Column(name = "postal_code", length = 20, comment = "우편번호 — KR 은 숫자 5자리 필수, 해외는 없으면 null")
    private String postalCode;

    @Column(name = "address_line1", nullable = false, length = 200, comment = "주소 (국내 도로명 · 지번 주소, 해외 street address)")
    private String addressLine1;

    @Column(name = "address_line2", length = 200, comment = "상세 주소 (동 · 호수 등). 없으면 null")
    private String addressLine2;

    @Column(name = "city", length = 100, comment = "도시 — 해외 주소는 필수, 국내는 없으면 null")
    private String city;

    @Column(name = "state_province", length = 100, comment = "주 · 도 (state / province). 없으면 null")
    private String stateProvince;

    @Column(name = "default_member_id", comment = "기본 배송지면 member_id 와 같은 값, 아니면 null — 회원당 기본 하나를 유니크 키로 강제한다 (FK: member.id — member_id 와 같은 값의 복제 칸)")
    private Long defaultMemberId;

    @Version
    @Column(name = "version", nullable = false, comment = "낙관적 락 버전")
    private Long version;
}
