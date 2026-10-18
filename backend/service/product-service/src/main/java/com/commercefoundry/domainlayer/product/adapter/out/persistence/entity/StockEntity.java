package com.commercefoundry.domainlayer.product.adapter.out.persistence.entity;

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
 * SKU 재고. SKU 와 1:1 이라 PK 가 {@code sku_id} 다. <b>행이 없는 SKU 는 품절이다</b> — 재고를 등록하지 않은 SKU 가 무제한으로 팔리지 않게 닫힌 기본값을 둔다.
 *
 * <p>{@code @Version} 을 두지 않는다. 수량 · 관리 여부를 바꾸는 쓰기는 모두 조건부 UPDATE 한 문장(차감은 {@code available_quantity >= :quantity},
 * 어드민 조정은 기대 수량 CAS)이라 엔티티를 읽고 고쳐 쓰는 경로가 없다 ({@code coding-conventions.md} §9-5).
 * bulk UPDATE 는 감사 리스너를 거치지 않으므로 {@code updated_at} 을 쿼리에서 직접 세팅한다.
 *
 * <p>음수 수량은 check 제약으로 DB 에서도 막는다 — 조건부 차감이 깨져도 마이너스 재고가 저장되지 않게.
 *
 * <p>{@link Persistable} 로 새 행이면 {@code merge} 가 아니라 {@code persist}(INSERT) 하게 한다. PK 를 직접 넣는 엔티티라 기본 {@code save} 는
 * merge 로 가는데, 두 어드민이 동시에 첫 재고를 등록하면 늦은 쪽의 merge 가 먼저 커밋된 행을 조건 없이 덮어쓴다. persist 면 PK 충돌로 실패한다.
 */
@Entity
@Table(
    name = "stock",
    comment = "SKU 재고 (SKU 와 1:1). 행이 없는 SKU 는 품절",
    check = @CheckConstraint(name = "ck_stock_available_quantity_non_negative", constraint = "available_quantity >= 0")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class StockEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "sku_id", comment = "SKU 아이디 (PK · FK: sku.id)")
    private Long skuId;

    @Column(name = "stock_managed", nullable = false, comment = "재고 관리 여부. false 면 수량과 무관하게 품절이 아니다 (무제한)")
    private boolean stockManaged;

    @Column(name = "available_quantity", nullable = false, comment = "판매 가능 수량 (선점분 제외, 0 이상)")
    private long availableQuantity;

    @Override
    public Long getId() {
        return skuId;
    }

    /** 생성 시각은 최초 INSERT 때 감사 리스너가 채운다 — 비어 있으면 아직 저장하지 않은 행이다. */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
