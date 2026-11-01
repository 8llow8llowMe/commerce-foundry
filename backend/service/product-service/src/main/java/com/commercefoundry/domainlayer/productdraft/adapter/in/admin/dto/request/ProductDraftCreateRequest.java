package com.commercefoundry.domainlayer.productdraft.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.productdraft.application.command.ProductDraftCreateCommand;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftValidationMessage;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftFacts;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Builder;

/**
 * 어드민 AI 상품 초안 생성 요청 — 운영자가 아는 사실과 근거 사진. 모든 칸이 선택이다(비어 있는 필수 입력은 응답 {@code missingRequiredInputs} 로 알린다).
 * 문구는 앞뒤 공백을 빼고 저장하며 빈 문구는 없는 값(null)으로 본다(목록의 빈 원소는 400). <b>여기 적은 값만 사실이다</b> — 소재 · 세탁 · 치수 · 원산지 · 인증을 AI 가 이미지로 정하지 않는다.
 * 고객 정보(이름 · 연락처 · 주소)는 적지 않는다. 입력은 AI 프롬프트의 데이터 칸으로만 쓰인다 — 명령문을 적어도 지시로 따르지 않는다.
 */
@Builder
@Schema(description = "어드민 AI 상품 초안 생성 요청")
public record ProductDraftCreateRequest(

    @Schema(description = "상품명 후보 (100자 이하)", example = "린넨 생활한복 저고리", nullable = true)
    @Size(max = 100, message = ProductDraftValidationMessage.NAME_HINT_SIZE_MAX)
    @Pattern(regexp = ProductDraftValidationMessage.SINGLE_LINE, message = ProductDraftValidationMessage.NAME_HINT_INVALID_CHARACTER)
    String nameHint,

    @Schema(description = "카테고리 아이디 — 문자열로 보내도 된다. 존재는 적용 때 상품 등록 검증이 본다", example = "12", nullable = true)
    @Positive(message = ProductDraftValidationMessage.CATEGORY_ID_POSITIVE)
    Long categoryId,

    @Schema(description = "소재 (최대 10개, 원소 1~100자)", example = "[\"린넨 100%\"]", nullable = true)
    @Size(max = 10, message = ProductDraftValidationMessage.MATERIALS_SIZE_MAX)
    List<@NotBlank(message = ProductDraftValidationMessage.MATERIAL_INVALID)
        @Size(max = 100, message = ProductDraftValidationMessage.MATERIAL_INVALID)
        @Pattern(regexp = ProductDraftValidationMessage.SINGLE_LINE, message = ProductDraftValidationMessage.MATERIAL_INVALID) String> materials,

    @Schema(description = "세탁 · 관리 방법 (최대 10개, 원소 1~100자)", example = "[\"찬물 단독 손세탁\"]", nullable = true)
    @Size(max = 10, message = ProductDraftValidationMessage.CARE_NOTES_SIZE_MAX)
    List<@NotBlank(message = ProductDraftValidationMessage.CARE_NOTE_INVALID)
        @Size(max = 100, message = ProductDraftValidationMessage.CARE_NOTE_INVALID)
        @Pattern(regexp = ProductDraftValidationMessage.SINGLE_LINE, message = ProductDraftValidationMessage.CARE_NOTE_INVALID) String> careNotes,

    @Schema(description = "치수 메모 (1000자 이하, 개행 허용)", example = "총장 62cm / 가슴단면 54cm", nullable = true)
    @Size(max = 1000, message = ProductDraftValidationMessage.MEASUREMENTS_NOTE_SIZE_MAX)
    @Pattern(regexp = ProductDraftValidationMessage.MULTI_LINE, message = ProductDraftValidationMessage.MEASUREMENTS_NOTE_INVALID_CHARACTER)
    String measurementsNote,

    @Schema(description = "원산지 (100자 이하)", example = "대한민국", nullable = true)
    @Size(max = 100, message = ProductDraftValidationMessage.ORIGIN_SIZE_MAX)
    @Pattern(regexp = ProductDraftValidationMessage.SINGLE_LINE, message = ProductDraftValidationMessage.ORIGIN_INVALID_CHARACTER)
    String origin,

    @Schema(description = "인증 (최대 10개, 원소 1~100자)", nullable = true)
    @Size(max = 10, message = ProductDraftValidationMessage.CERTIFICATIONS_SIZE_MAX)
    List<@NotBlank(message = ProductDraftValidationMessage.CERTIFICATION_INVALID)
        @Size(max = 100, message = ProductDraftValidationMessage.CERTIFICATION_INVALID)
        @Pattern(regexp = ProductDraftValidationMessage.SINGLE_LINE, message = ProductDraftValidationMessage.CERTIFICATION_INVALID) String> certifications,

    @Schema(description = "문구 분위기 (200자 이하)", example = "차분하고 단정하게", nullable = true)
    @Size(max = 200, message = ProductDraftValidationMessage.TONE_SIZE_MAX)
    @Pattern(regexp = ProductDraftValidationMessage.SINGLE_LINE, message = ProductDraftValidationMessage.TONE_INVALID_CHARACTER)
    String tone,

    @Schema(description = "꼭 넣을 표현 (최대 10개, 원소 1~100자)", nullable = true)
    @Size(max = 10, message = ProductDraftValidationMessage.MUST_INCLUDE_SIZE_MAX)
    List<@NotBlank(message = ProductDraftValidationMessage.MUST_INCLUDE_INVALID)
        @Size(max = 100, message = ProductDraftValidationMessage.MUST_INCLUDE_INVALID)
        @Pattern(regexp = ProductDraftValidationMessage.SINGLE_LINE, message = ProductDraftValidationMessage.MUST_INCLUDE_INVALID) String> mustInclude,

    @Schema(description = "쓰지 않을 표현 (최대 10개, 원소 1~100자)", nullable = true)
    @Size(max = 10, message = ProductDraftValidationMessage.MUST_AVOID_SIZE_MAX)
    List<@NotBlank(message = ProductDraftValidationMessage.MUST_AVOID_INVALID)
        @Size(max = 100, message = ProductDraftValidationMessage.MUST_AVOID_INVALID)
        @Pattern(regexp = ProductDraftValidationMessage.SINGLE_LINE, message = ProductDraftValidationMessage.MUST_AVOID_INVALID) String> mustAvoid,

    @Schema(description = "근거 이미지 (최대 8장, 배열 순서 = 화면 순서). 대표(THUMBNAIL)는 한 장까지", nullable = true)
    @Size(max = 8, message = ProductDraftValidationMessage.IMAGES_SIZE_MAX)
    List<@NotNull(message = ProductDraftValidationMessage.IMAGE_REQUIRED) @Valid ProductDraftImageRequest> images
) {

    public ProductDraftCreateCommand toCommand(long requestedByMemberId) {
        ProductDraftFacts facts = new ProductDraftFacts(
            normalize(nameHint), categoryId, normalize(materials), normalize(careNotes), normalize(measurementsNote), normalize(origin),
            normalize(certifications), normalize(tone), normalize(mustInclude), normalize(mustAvoid)
        );
        return ProductDraftCreateCommand.builder()
            .requestedByMemberId(requestedByMemberId)
            .facts(facts)
            .images(images == null ? List.of() : images.stream().map(ProductDraftImageRequest::toDomain).toList())
            .build();
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static List<String> normalize(List<String> values) {
        return values == null ? List.of() : values.stream().map(String::strip).toList();
    }
}
