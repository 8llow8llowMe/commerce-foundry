package com.commercefoundry.domainlayer.storefooter.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterContent;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "store_footer_link", comment = "푸터 리비전별 불변 링크",
    uniqueConstraints = @UniqueConstraint(name = "uk_store_footer_link_revision_id_display_order", columnNames = {"revision_id", "display_order"}),
    check = {@CheckConstraint(name = "ck_store_footer_link_type", constraint = "type in ('INSTAGRAM', 'KAKAO_CHANNEL', 'CUSTOM')"),
        @CheckConstraint(name = "ck_store_footer_link_order", constraint = "display_order between 0 and 999")})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreFooterLinkEntity {
    @Id
    @Column(comment = "링크 행 아이디 (Snowflake)")
    private Long id;

    @Getter
    @Column(nullable = false, updatable = false, comment = "리비전 아이디 (FK: store_footer_revision.id)")
    private Long revisionId;

    @Column(nullable = false, updatable = false, length = 20, comment = "링크 유형 제어 코드")
    private String type;

    @Column(nullable = false, updatable = false, length = 80, comment = "국문 표시명")
    private String labelKo;

    @Column(nullable = false, updatable = false, length = 80, comment = "영문 표시명")
    private String labelEn;

    @Column(nullable = false, updatable = false, length = 2048, comment = "검증한 외부 HTTPS 주소")
    private String url;

    @Column(nullable = false, updatable = false, comment = "표시 순서")
    private int displayOrder;

    @Column(nullable = false, updatable = false, comment = "공개 노출 여부")
    private boolean visible;

    public static StoreFooterLinkEntity create(long id, long revisionId, StoreFooterContent.Link link) {
        var entity = new StoreFooterLinkEntity();
        entity.id = id;
        entity.revisionId = revisionId;
        entity.type = link.type();
        entity.labelKo = link.labels().ko();
        entity.labelEn = link.labels().en();
        entity.url = link.url();
        entity.displayOrder = link.displayOrder();
        entity.visible = link.visible();
        return entity;
    }

    public StoreFooterContent.Link toDomain() {
        return new StoreFooterContent.Link(type, new StoreFooterContent.Text(labelKo, labelEn), url, displayOrder, visible);
    }
}
