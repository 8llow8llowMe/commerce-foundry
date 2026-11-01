package com.commercefoundry.domainlayer.productdraft.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.productdraft.adapter.in.admin.dto.item.ProductDraftFactsAdminItem;
import com.commercefoundry.domainlayer.productdraft.adapter.in.admin.dto.item.ProductDraftImageAdminItem;
import com.commercefoundry.domainlayer.productdraft.adapter.in.admin.dto.response.ProductDraftAdminResponse;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftFacts;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImage;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftRequiredInput;
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
        return ProductDraftAdminResponse.builder()
            .draftId(String.valueOf(draft.id()))
            .status(draft.statusAt(now).toMetadata())
            .facts(toFactsItem(draft.facts()))
            .images(draft.images().stream().map(this::toImageItem).toList())
            .missingRequiredInputs(draft.missingRequiredInputs().stream().map(ProductDraftRequiredInput::toMetadata).toList())
            .requestedByMemberId(String.valueOf(draft.requestedByMemberId()))
            .requestedAt(draft.createdAt())
            .expiresAt(draft.expiresAt())
            .rejectedAt(draft.rejectedAt())
            .build();
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
