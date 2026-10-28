package com.commercefoundry.domainlayer.look.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 룩 구성 상품 하나.
 *
 * <ul>
 *   <li>룩의 아이템 조회는 {@code uk_look_item_look_id_display_order} 의 선두 칸({@code look_id})을 탄다 — {@code look_id} 단독 인덱스를 따로 두지 않는다.</li>
 *   <li>{@code uk_look_item_look_id_product_id} — 한 룩에 같은 상품이 두 번 들지 않는다(Processor 가 먼저 {@code LOOK_003} 으로 거절한다).</li>
 *   <li>{@code idx_look_item_product_id_look_id} — 상품 상세의 "이 상품이 든 룩"(#62 PR-B)이 상품 아이디로 찾고 룩 아이디까지 인덱스에서 읽는다.</li>
 * </ul>
 * 수정은 아이템을 한 문장으로 지운 뒤 새로 넣으므로(같은 트랜잭션) 두 유니크 키와 부딪히지 않는다.
 */
@Entity
@Table(
    name = "look_item",
    comment = "룩 구성 상품",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_look_item_look_id_display_order", columnNames = {"look_id", "display_order"}),
        @UniqueConstraint(name = "uk_look_item_look_id_product_id", columnNames = {"look_id", "product_id"})
    },
    indexes = @Index(name = "idx_look_item_product_id_look_id", columnList = "product_id, look_id"),
    check = @CheckConstraint(name = "ck_look_item_display_order_non_negative", constraint = "display_order >= 0")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LookItemEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "룩 아이템 아이디 (Snowflake). 룩 수정마다 새로 발급한다")
    private Long id;

    @Column(name = "look_id", nullable = false, comment = "룩 아이디 (FK: look.id)")
    private Long lookId;

    @Column(name = "product_id", nullable = false, comment = "상품 아이디 (FK: product.id)")
    private Long productId;

    @Column(name = "display_order", nullable = false, comment = "룩 안의 순서 (0 부터, 오름차순)")
    private int displayOrder;

    /** 생성 시각은 최초 INSERT 때 감사 리스너가 채운다 — 비어 있으면 아직 저장하지 않은 행이다. */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
