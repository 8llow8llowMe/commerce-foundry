package com.commercefoundry.domainlayer.category.adapter.out.persistence.entity;

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
 * 고객 화면은 전체 카테고리를 한 번에 읽어 트리로 만든다({@code CategoryTree}). 어드민 쓰기도 전체를 읽어 깊이 · 순환을 판정하므로 상위별 조회가 없어
 * {@code parent_id} 인덱스를 두지 않는다.
 *
 * <p>{@link Persistable} 로 새 행이면 {@code merge} 가 아니라 {@code persist}(INSERT) 한다 — Snowflake 아이디를 직접 넣는 엔티티라 기본 {@code save} 는
 * merge 로 가서 SELECT 를 한 번 더 보내고, 아이디가 겹치면 기존 행을 조용히 덮어쓴다 ({@code coding-conventions.md} §9-1).
 * 수정은 읽어 온 관리 엔티티를 {@link #change} 로 고치고 dirty checking 으로 내보낸다 — merge 하지 않는다.
 *
 * <p>같은 상위 안의 {@code display_order} · {@code name} 에 유니크 키가 없다 — 노출 순서를 맞바꾸거나 같은 이름을 다시 써도 제약 위반이 없다.
 */
@Entity
@Table(name = "category", comment = "상품 카테고리 (계층)")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CategoryEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "카테고리 아이디 (Snowflake)")
    private Long id;

    @Column(name = "parent_id", comment = "상위 카테고리 아이디 (FK: category.id). 최상위면 null")
    private Long parentId;

    @Column(name = "name", nullable = false, length = 50, comment = "카테고리명")
    private String name;

    @Column(name = "display_order", nullable = false, comment = "같은 상위 안의 노출 순서 (오름차순)")
    private int displayOrder;

    @Column(name = "visible", nullable = false, comment = "스토어프론트 노출 여부")
    private boolean visible;

    /** 어드민 수정 — 상위(이동) · 이름 · 노출 순서 · 노출 여부를 한 번에 바꾼다. 깊이 · 순환 판정은 호출한 쪽이 끝냈다. */
    public void change(Long parentId, String name, int displayOrder, boolean visible) {
        this.parentId = parentId;
        this.name = name;
        this.displayOrder = displayOrder;
        this.visible = visible;
    }

    /** 생성 시각은 최초 INSERT 때 감사 리스너가 채운다 — 비어 있으면 아직 저장하지 않은 행이다. */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
