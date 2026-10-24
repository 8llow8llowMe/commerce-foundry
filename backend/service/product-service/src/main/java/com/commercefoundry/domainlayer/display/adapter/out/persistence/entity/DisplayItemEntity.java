package com.commercefoundry.domainlayer.display.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.display.domain.model.DisplayItemType;
import com.commercefoundry.domainlayer.display.domain.model.DisplayMediaType;
import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 메인 전시 아이템 — 미디어(이미지 · 영상) 또는 상품 하나. JSON 한 칸이 아니라 행으로 둔다 — 유형별 칸 규칙을 check 제약으로 막고, 스냅숏 · {@code validate} 가
 * 모양을 대조하게 하려고다(저장소에 JSON 컬럼 선례가 없다, delivery 정책 이력과 같은 판단 — {@code api-design-guide.md} §6-2).
 *
 * <p>칸 규칙 — 미디어면 {@code media_type} · {@code pc_url} 이 있고 {@code product_id} 가 없다. 상품이면 {@code product_id} 만 있고 미디어 칸이 모두 비어 있다.
 * 영상은 PC 포스터가 있고, 이미지는 포스터가 없다. 요청 검증 · domain 이 같은 규칙을 먼저 본다.
 *
 * <p>섹션 아이템 조회는 {@code uk_display_item_section_id_display_order} 의 선두 칸({@code section_id})을 탄다 — {@code section_id} 단독 인덱스를 따로 두지 않는다.
 * 수정은 아이템을 한 문장으로 지운 뒤 새로 넣으므로(같은 트랜잭션) 순서 유니크 키와 부딪히지 않는다.
 */
@Entity
@Table(
    name = "display_item",
    comment = "메인 전시 아이템 (미디어 또는 상품)",
    uniqueConstraints = @UniqueConstraint(name = "uk_display_item_section_id_display_order", columnNames = {"section_id", "display_order"}),
    check = {
        @CheckConstraint(
            name = "ck_display_item_media_fields",
            constraint = "item_type <> 'MEDIA' or (media_type is not null and pc_url is not null and product_id is null)"
        ),
        @CheckConstraint(
            name = "ck_display_item_product_fields",
            constraint = "item_type <> 'PRODUCT' or (product_id is not null and media_type is null and pc_url is null and mobile_url is null"
                + " and pc_poster_url is null and mobile_poster_url is null and alt_text is null)"
        ),
        @CheckConstraint(
            name = "ck_display_item_video_poster",
            constraint = "media_type is null or (media_type = 'VIDEO' and pc_poster_url is not null)"
                + " or (media_type = 'IMAGE' and pc_poster_url is null and mobile_poster_url is null)"
        ),
        @CheckConstraint(name = "ck_display_item_display_order_non_negative", constraint = "display_order >= 0")
    }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DisplayItemEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "전시 아이템 아이디 (Snowflake). 섹션 수정마다 새로 발급한다")
    private Long id;

    @Column(name = "section_id", nullable = false, comment = "전시 섹션 아이디 (FK: display_section.id)")
    private Long sectionId;

    @Column(name = "display_order", nullable = false, comment = "섹션 안의 순서 (0 부터, 오름차순)")
    private int displayOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 20, comment = "아이템 유형 (MEDIA · PRODUCT)")
    private DisplayItemType itemType;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", length = 20, comment = "미디어 종류 (IMAGE · VIDEO). 미디어 아이템만")
    private DisplayMediaType mediaType;

    @Column(name = "pc_url", length = 500, comment = "PC 미디어 주소. 미디어 아이템만")
    private String pcUrl;

    @Column(name = "mobile_url", length = 500, comment = "모바일 미디어 주소. 없으면 PC 주소를 쓴다")
    private String mobileUrl;

    @Column(name = "pc_poster_url", length = 500, comment = "영상 포스터 (PC). 영상이면 필수")
    private String pcPosterUrl;

    @Column(name = "mobile_poster_url", length = 500, comment = "영상 포스터 (모바일). 없으면 PC 포스터를 쓴다")
    private String mobilePosterUrl;

    @Column(name = "alt_text", length = 200, comment = "미디어 대체 텍스트")
    private String altText;

    @Column(name = "product_id", comment = "상품 아이디 (FK: product.id). 상품 아이템만")
    private Long productId;

    @Column(name = "caption", length = 200, comment = "아이템 설명 문구")
    private String caption;

    /** 생성 시각은 최초 INSERT 때 감사 리스너가 채운다 — 비어 있으면 아직 저장하지 않은 행이다. */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
