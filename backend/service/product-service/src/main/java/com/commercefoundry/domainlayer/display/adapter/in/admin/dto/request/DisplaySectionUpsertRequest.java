package com.commercefoundry.domainlayer.display.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.validation.DisplayText;
import com.commercefoundry.domainlayer.display.application.command.DisplaySectionUpsertCommand;
import com.commercefoundry.domainlayer.display.application.exception.DisplayValidationMessage;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySection;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

/**
 * 어드민 전시 섹션 생성 · 수정 요청. 수정은 전체 교체라 생성과 본문이 같다 — 아이템은 받은 순서가 섹션 안의 순서이고 교체마다 아이템 아이디가 새로 발급된다.
 * 문구 · 주소는 앞뒤 공백을 빼고 저장하며 빈 값은 없는 값(null)으로 본다. <b>문구(제목 · 부제 · 본문 · 링크 문구)는 평문이다</b> — 서버가 HTML 을 정제하지 않으므로
 * 화면은 텍스트 노드로만 렌더한다.
 *
 * <p>유형별 아이템 수 · 유형 · 상품 중복 · 기간 · 링크 주소 · 미디어 주소 · 상품 존재는 섹션 규칙이라 요청 검증이 아니라 Processor 가 본다(PRODUCT_047~055).
 * 노출 시각은 저장 정밀도(마이크로초)로 잘린 뒤 판정된다 — 나노초만 다른 시작 · 종료는 같은 시각이라 400(PRODUCT_051)이다.
 *
 * <p>교차 검증은 {@code @AssertTrue} 메서드다. 오류의 {@code field} 는 메서드 이름에서 온다({@code periodInRange}).
 */
@Builder
@Schema(description = "어드민 전시 섹션 생성 · 수정 요청 (수정은 전체 교체)")
public record DisplaySectionUpsertRequest(

    @Schema(
        description = "섹션 유형 — HERO(미디어 1~10) · PRODUCT_PICK(상품 1~20) · STORY(미디어 0~10) · LOOK(미디어 1~10 + 상품 0~20) · DETAIL(미디어 1~10). "
            + "받지 않는 아이템 유형이 있으면 400(PRODUCT_047), 수가 범위 밖이면 400(PRODUCT_048)이다",
        example = "PRODUCT_PICK"
    )
    @NotNull(message = DisplayValidationMessage.SECTION_TYPE_REQUIRED)
    DisplaySectionType sectionType,

    @Schema(
        description = "제목 (200자 이하, 제어 문자 불가). 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더, 개행은 white-space 로)",
        example = "가을 생활한복", nullable = true
    )
    @Size(max = 200, message = DisplayValidationMessage.TITLE_SIZE_MAX)
    @DisplayText(message = DisplayValidationMessage.TEXT_INVALID_CHARACTER)
    String title,

    @Schema(
        description = "부제목 (200자 이하, 제어 문자 불가). 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더, 개행은 white-space 로)",
        example = "일상에 입는 한복", nullable = true
    )
    @Size(max = 200, message = DisplayValidationMessage.SUBTITLE_SIZE_MAX)
    @DisplayText(message = DisplayValidationMessage.TEXT_INVALID_CHARACTER)
    String subtitle,

    @Schema(
        description = "본문 (2000자 이하, 개행 · 탭 외 제어 문자 불가). 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더, 개행은 white-space 로)",
        nullable = true
    )
    @Size(max = 2000, message = DisplayValidationMessage.BODY_SIZE_MAX)
    @DisplayText(message = DisplayValidationMessage.TEXT_INVALID_CHARACTER, multiline = true)
    String body,

    @Schema(
        description = "이동 주소 (500자 이하) — '/' 로 시작하는 스토어 안 상대 경로 또는 https 주소. '//host' · 다른 스킴은 400(PRODUCT_052)이다",
        example = "/products?categoryId=11", nullable = true
    )
    @Size(max = 500, message = DisplayValidationMessage.LINK_URL_SIZE_MAX)
    String linkUrl,

    @Schema(
        description = "이동 버튼 문구 (50자 이하). linkUrl 없이 보내면 400(PRODUCT_053)이다. 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더, 개행은 white-space 로)",
        example = "더 보기", nullable = true
    )
    @Size(max = 50, message = DisplayValidationMessage.LINK_LABEL_SIZE_MAX)
    @DisplayText(message = DisplayValidationMessage.TEXT_INVALID_CHARACTER)
    String linkLabel,

    @Schema(description = "고객 메인 노출 여부. false 면 기간과 무관하게 숨긴다", example = "true")
    @NotNull(message = DisplayValidationMessage.VISIBLE_REQUIRED)
    Boolean visible,

    @Schema(
        description = "노출 시작 시각(포함, UTC ISO-8601). 없으면 제한 없음. 마이크로초로 잘라 저장하고, 0001~9999년 밖이면 400(PRODUCT_165)이다",
        example = "2026-11-01T00:00:00Z", nullable = true
    )
    Instant startAt,

    @Schema(
        description = "노출 종료 시각(제외, UTC ISO-8601). 없으면 제한 없음. 마이크로초로 자른 뒤 startAt 보다 뒤여야 한다 — 아니면 400(PRODUCT_051), "
            + "0001~9999년 밖이면 400(PRODUCT_165)이다",
        example = "2026-12-01T00:00:00Z", nullable = true
    )
    Instant endAt,

    @Schema(description = "아이템 전체 (받은 순서가 섹션 안의 순서, 최대 30개). 없으면 빈 배열을 보낸다")
    @NotNull(message = DisplayValidationMessage.ITEMS_REQUIRED)
    @Size(max = 30, message = DisplayValidationMessage.ITEMS_SIZE_MAX)
    List<@NotNull(message = DisplayValidationMessage.ITEM_REQUIRED) @Valid DisplayItemUpsertRequest> items
) {

    /** 저장 가능한 시각 범위 — PostgreSQL 은 더 넓게 받지만 4자리 연도 밖은 화면 · 직렬화 형식이 깨진다. */
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = DisplayValidationMessage.PERIOD_OUT_OF_RANGE)
    public boolean isPeriodInRange() {
        return DisplaySection.isStorablePeriodAt(startAt) && DisplaySection.isStorablePeriodAt(endAt);
    }

    public DisplaySectionUpsertCommand toCommand() {
        return DisplaySectionUpsertCommand.builder()
            .sectionType(sectionType)
            .title(TextValues.normalize(title))
            .subtitle(TextValues.normalize(subtitle))
            .body(TextValues.normalize(body))
            .linkUrl(TextValues.normalize(linkUrl))
            .linkLabel(TextValues.normalize(linkLabel))
            .visible(visible)
            .startAt(startAt)
            .endAt(endAt)
            .items(items.stream().map(DisplayItemUpsertRequest::toCommand).toList())
            .build();
    }
}
