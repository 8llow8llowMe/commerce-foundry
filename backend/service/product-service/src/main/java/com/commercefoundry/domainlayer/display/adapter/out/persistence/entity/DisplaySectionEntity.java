package com.commercefoundry.domainlayer.display.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionType;
import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * 메인 전시 섹션. 섹션 수에 상한(50)이 있어 고객 메인 · 어드민 목록 모두 전체를 읽고 메모리에서 노출 조건을 본다 — 노출 순서 · 기간 인덱스를 두지 않는다.
 *
 * <p>{@link Persistable} 로 새 행이면 {@code persist}(INSERT) 한다 — Snowflake 아이디를 직접 넣는 엔티티라 기본 {@code save} 는 merge 로 가서 SELECT 를 한 번 더 보낸다
 * ({@code coding-conventions.md} §9-1). 수정은 읽어 온 관리 엔티티를 {@link #change} · {@link #changeDisplayOrder} 로 고치고 dirty checking 으로 내보낸다.
 *
 * <p>{@code display_order} 에 유니크 키가 없다 — 순서를 맞바꾸는 중간 상태와, 섹션이 없을 때 동시 생성 둘이 같은 순서를 받는 경우를 막지 않는다(아이디로 정렬이 이어진다).
 * {@code body} 는 {@code text} 이고 길이 상한(2000자)은 check 제약으로 건다.
 */
@Entity
@Table(
    name = "display_section",
    comment = "메인 전시 섹션",
    check = {
        @CheckConstraint(name = "ck_display_section_period", constraint = "end_at is null or start_at is null or end_at > start_at"),
        @CheckConstraint(name = "ck_display_section_body_length", constraint = "body is null or char_length(body) <= 2000"),
        @CheckConstraint(name = "ck_display_section_display_order_non_negative", constraint = "display_order >= 0")
    }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DisplaySectionEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "전시 섹션 아이디 (Snowflake)")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "section_type", nullable = false, length = 20, comment = "섹션 유형 (HERO · PRODUCT_PICK · STORY · LOOK · DETAIL)")
    private DisplaySectionType sectionType;

    @Column(name = "title", length = 200, comment = "제목")
    private String title;

    @Column(name = "subtitle", length = 200, comment = "부제목")
    private String subtitle;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "body", comment = "본문 (개행 허용, 2000자 이하)")
    private String body;

    @Column(name = "link_url", length = 500, comment = "이동 주소 — 슬래시로 시작하는 상대 경로 또는 https")
    private String linkUrl;

    @Column(name = "link_label", length = 50, comment = "이동 버튼 문구")
    private String linkLabel;

    @Column(name = "display_order", nullable = false, comment = "메인 화면 안의 노출 순서 (오름차순, 같으면 아이디 오름차순)")
    private int displayOrder;

    @Column(name = "visible", nullable = false, comment = "고객 메인 노출 여부")
    private boolean visible;

    @Column(name = "start_at", comment = "노출 시작 시각 (포함). null 이면 제한 없음")
    private Instant startAt;

    @Column(name = "end_at", comment = "노출 종료 시각 (제외). null 이면 제한 없음")
    private Instant endAt;

    /** 어드민 수정(전체 교체) — 노출 순서를 뺀 모든 칸을 바꾼다. 판정은 호출한 쪽이 끝냈다. */
    public void change(
        DisplaySectionType sectionType, String title, String subtitle, String body,
        String linkUrl, String linkLabel, boolean visible, Instant startAt, Instant endAt
    ) {
        this.sectionType = sectionType;
        this.title = title;
        this.subtitle = subtitle;
        this.body = body;
        this.linkUrl = linkUrl;
        this.linkLabel = linkLabel;
        this.visible = visible;
        this.startAt = startAt;
        this.endAt = endAt;
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
