package com.commercefoundry.domainlayer.look.adapter.out.persistence.entity;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * 룩(Shop the Look). 룩 수에 상한(100)이 있어 어드민은 전체를 읽고, 고객 목록도 작은 표를 순서대로 읽는다 — 노출 순서 · 노출 여부 인덱스를 두지 않는다.
 *
 * <p>{@link Persistable} 로 새 행이면 {@code persist}(INSERT) 한다 — Snowflake 아이디를 직접 넣는 엔티티라 기본 {@code save} 는 merge 로 가서 SELECT 를 한 번 더 보낸다
 * ({@code coding-conventions.md} §9-1). 수정은 읽어 온 관리 엔티티를 {@link #change} · {@link #changeDisplayOrder} 로 고치고 dirty checking 으로 내보낸다.
 * 버전 칸이 없다 — 어드민 쓰기는 전체 행 잠금으로 직렬화하고 마지막 쓰기가 이긴다(메인 전시와 같다).
 *
 * <p>{@code display_order} 에 유니크 키가 없다 — 순서를 맞바꾸는 중간 상태와, 룩이 없을 때 동시 생성 둘이 같은 순서를 받는 경우를 막지 않는다(아이디로 정렬이 이어진다).
 * {@code description} 은 {@code text} 이고 길이 상한(1000자)은 check 제약으로 건다.
 */
@Entity
@Table(
    name = "look",
    comment = "룩 (Shop the Look)",
    check = {
        @CheckConstraint(name = "ck_look_description_length", constraint = "description is null or char_length(description) <= 1000"),
        @CheckConstraint(name = "ck_look_display_order_non_negative", constraint = "display_order >= 0")
    }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LookEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "룩 아이디 (Snowflake)")
    private Long id;

    @Column(name = "title", nullable = false, length = 100, comment = "제목 (평문)")
    private String title;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "description", comment = "설명 (평문, 개행 허용, 1000자 이하)")
    private String description;

    @Column(name = "image_url", nullable = false, length = 500, comment = "대표 이미지 주소 — 스토어 미디어 저장소의 공개 주소 아래")
    private String imageUrl;

    @Column(name = "image_width", comment = "대표 이미지 원본 가로 px. 모르면 null")
    private Integer imageWidth;

    @Column(name = "image_height", comment = "대표 이미지 원본 세로 px. 모르면 null")
    private Integer imageHeight;

    @Column(name = "image_alt_text", length = 200, comment = "대표 이미지 대체 텍스트")
    private String imageAltText;

    @Column(name = "visible", nullable = false, comment = "고객 노출 여부")
    private boolean visible;

    @Column(name = "display_order", nullable = false, comment = "룩 목록 안의 노출 순서 (오름차순, 같으면 아이디 오름차순)")
    private int displayOrder;

    /** 어드민 수정(전체 교체) — 노출 순서를 뺀 모든 칸을 바꾼다. 판정은 호출한 쪽이 끝냈다. */
    public void change(
        String title, String description, String imageUrl, Integer imageWidth, Integer imageHeight, String imageAltText, boolean visible
    ) {
        this.title = title;
        this.description = description;
        this.imageUrl = imageUrl;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.imageAltText = imageAltText;
        this.visible = visible;
    }

    public void changeDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }

    /** 생성 시각은 최초 INSERT 때 감사 리스너가 채운다 — 비어 있으면 아직 저장하지 않은 행이다. */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
