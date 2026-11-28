package com.commercefoundry.domainlayer.productdraft.adapter.in.admin.presenter;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.productdraft.adapter.in.admin.dto.item.ProductDraftFactsAdminItem;
import com.commercefoundry.domainlayer.productdraft.adapter.in.admin.dto.item.ProductDraftImageAdminItem;
import com.commercefoundry.domainlayer.productdraft.adapter.in.admin.dto.item.ProductDraftResultAdminItem;
import com.commercefoundry.domainlayer.productdraft.adapter.in.admin.dto.response.ProductDraftAdminResponse;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftCandidate;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftFacts;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImage;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftRequiredInput;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftStatus;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 어드민 초안 응답. 식별자 {@code long → String}, 상태 · 누락 입력 → metadata 변환만 한다. 상태와 누락 입력은 domain 판정({@link ProductDraft#statusAt} ·
 * {@link ProductDraft#missingRequiredInputs})을 그대로 옮긴다.
 */
@Component
public class ProductDraftAdminPresenter {

    /** @param now 상태 판정 시각 — Facade 가 한 번 잡은 시각 */
    public ProductDraftAdminResponse toResponse(ProductDraft draft, Instant now) {
        ProductDraftStatus status = draft.statusAt(now);
        return ProductDraftAdminResponse.builder()
            .draftId(String.valueOf(draft.id()))
            .status(status.toMetadata())
            .facts(toFactsItem(draft.facts()))
            .images(draft.images().stream().map(this::toImageItem).toList())
            .missingRequiredInputs(draft.missingRequiredInputs().stream().map(ProductDraftRequiredInput::toMetadata).toList())
            .requestedByMemberId(String.valueOf(draft.requestedByMemberId()))
            .requestedAt(draft.createdAt())
            .expiresAt(draft.expiresAt())
            .rejectedAt(draft.rejectedAt())
            .result(status == ProductDraftStatus.READY && draft.candidate() != null ? toResult(draft.candidate()) : null)
            .failureNotice(status == ProductDraftStatus.FAILED ? failureNotice(draft, now) : null)
            .generationNo(draft.generation().generationNo())
            .regenerationAvailable(draft.regenerationAvailableAt(now))
            .build();
    }

    private ProductDraftResultAdminItem toResult(ProductDraftCandidate candidate) {
        var suggestion = candidate.suggestion();
        var item = new ProductDraftResultAdminItem.Suggestion(suggestion.name(), suggestion.description(), suggestion.searchTags(),
            suggestion.seoTitle(), suggestion.seoDescription(), suggestion.imageAlts().stream()
                .map(alt -> new ProductDraftResultAdminItem.ImageAlt(alt.index(), alt.altText())).toList());
        return new ProductDraftResultAdminItem(item, candidate.unconfirmedFacts().stream().map(this::toUnconfirmedFact).toList(),
            candidate.warnings().stream().map(this::toWarning).toList());
    }

    private CodeNameDescriptionMetadata toUnconfirmedFact(ProductDraftCandidate.UnconfirmedFact fact) {
        String name = switch (fact) {
            case MATERIALS -> "소재";
            case CARE_NOTES -> "세탁·관리 방법";
            case MEASUREMENTS -> "치수";
            case ORIGIN -> "원산지";
            case CERTIFICATIONS -> "인증";
        };
        return CodeNameDescriptionMetadata.of(fact.name(), name, name + " 정보를 확인해 주세요.");
    }

    private ProductDraftResultAdminItem.Warning toWarning(ProductDraftCandidate.Warning warning) {
        return switch (warning.code()) {
            case MUST_INCLUDE_MISSING -> new ProductDraftResultAdminItem.Warning(warning.code().name(), "필수 표현 누락",
                "상품명 또는 설명에 꼭 넣을 표현이 없습니다.", warning.index());
            case MUST_AVOID_USED -> new ProductDraftResultAdminItem.Warning(warning.code().name(), "제외 표현 사용",
                "제안에 쓰지 않을 표현이 있습니다.", warning.index());
        };
    }

    /** 실패 안내 — 새 초안이 필요한 실패는 새 초안을, 그 밖에는 남은 회차가 있으면 다시 생성을 안내한다. 내부 실패 코드는 싣지 않는다. */
    private String failureNotice(ProductDraft draft, Instant now) {
        if (draft.requiresNewDraft()) {
            return "초안의 입력이나 결과를 더 이상 사용할 수 없습니다. 새 초안을 만들어 주세요.";
        }
        String next = draft.regenerationAvailableAt(now) ? "다시 생성하거나 직접 입력해 주세요." : "새 초안을 만들거나 직접 입력해 주세요.";
        String code = draft.failureCode() == null ? "" : draft.failureCode();
        return switch (code) {
            case "PRODUCT_DRAFT_AI_BUDGET_EXCEEDED" -> "AI 사용 한도를 초과했습니다. 한도를 확인하거나 직접 입력해 주세요.";
            case ProductDraft.GENERATION_CANCELLED -> "AI 초안 생성이 취소되었습니다. " + next;
            default -> "AI 초안을 만들지 못했습니다. " + next;
        };
    }

    private ProductDraftFactsAdminItem toFactsItem(ProductDraftFacts facts) {
        return ProductDraftFactsAdminItem.builder()
            .nameHint(facts.nameHint())
            .categoryId(facts.categoryId() == null ? null : String.valueOf(facts.categoryId()))
            .materials(facts.materials())
            .careNotes(facts.careNotes())
            .measurementsNote(facts.measurementsNote())
            .origin(facts.origin())
            .certifications(facts.certifications())
            .tone(facts.tone())
            .mustInclude(facts.mustInclude())
            .mustAvoid(facts.mustAvoid())
            .build();
    }

    private ProductDraftImageAdminItem toImageItem(ProductDraftImage image) {
        return ProductDraftImageAdminItem.builder()
            .url(image.url())
            .imageType(image.imageType().name())
            .width(image.width())
            .height(image.height())
            .build();
    }
}
