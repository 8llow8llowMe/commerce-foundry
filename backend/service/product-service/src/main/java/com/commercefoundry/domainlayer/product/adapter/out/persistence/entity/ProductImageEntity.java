package com.commercefoundry.domainlayer.product.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.product.domain.model.ImageType;
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

/**
 * 대표 이미지(THUMBNAIL)는 순서가 항상 0 이라(check 제약) 유니크 키가 "상품당 1개" 를 보장한다.
 */
@Entity
@Table(
    name = "product_image",
    comment = "상품 이미지",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_product_image_product_id_image_type_display_order", columnNames = {"product_id", "image_type", "display_order"}
    ),
    check = @CheckConstraint(name = "ck_product_image_thumbnail_display_order", constraint = "image_type <> 'THUMBNAIL' or display_order = 0")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ProductImageEntity extends BaseEntity {

    @Id
    @Column(name = "id", comment = "상품 이미지 아이디 (Snowflake)")
    private Long id;

    @Column(name = "product_id", nullable = false, comment = "상품 아이디 (FK: product.id)")
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "image_type", nullable = false, length = 20, comment = "이미지 유형 — THUMBNAIL(목록 대표, 상품당 1개) / GALLERY(상세 갤러리)")
    private ImageType imageType;

    @Column(name = "url", nullable = false, length = 500, comment = "이미지 URL")
    private String url;

    @Column(name = "width", comment = "원본 가로 px. 모르면 null")
    private Integer width;

    @Column(name = "height", comment = "원본 세로 px. 모르면 null")
    private Integer height;

    @Column(name = "alt_text", length = 200, comment = "대체 텍스트")
    private String altText;

    @Column(name = "display_order", nullable = false, comment = "같은 유형 안의 순서. THUMBNAIL 은 항상 0")
    private int displayOrder;
}
