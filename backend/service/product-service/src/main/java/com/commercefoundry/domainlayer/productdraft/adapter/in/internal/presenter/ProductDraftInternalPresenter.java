package com.commercefoundry.domainlayer.productdraft.adapter.in.internal.presenter;

import com.commercefoundry.domainlayer.productdraft.adapter.in.internal.dto.response.ProductDraftGenerationInputResponse;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftFacts;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImage;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;

/** 생성 입력 응답 변환. 아이디는 문자열로 바꾸고, 이미지 원본(키 · ETag)은 형식 · 크기만 남긴다. 상태 · 원본 고정 판정은 Processor 가 끝냈다. */
@Component
public class ProductDraftInternalPresenter {

    public ProductDraftGenerationInputResponse toGenerationInput(ProductDraft draft) {
        ProductDraftFacts facts = draft.facts();
        List<ProductDraftImage> images = draft.images();
        List<ProductDraftGenerationInputResponse.Image> items = IntStream.range(0, images.size())
            .mapToObj(index -> toImage(index, images.get(index)))
            .toList();
        return new ProductDraftGenerationInputResponse(
            Long.toString(draft.id()), draft.status().name(), draft.expiresAt(),
            new ProductDraftGenerationInputResponse.Facts(facts.nameHint(), facts.materials(), facts.careNotes(), facts.measurementsNote(), facts.origin(),
                facts.certifications(), facts.tone(), facts.mustInclude(), facts.mustAvoid()),
            items.size(), items
        );
    }

    private static ProductDraftGenerationInputResponse.Image toImage(int index, ProductDraftImage image) {
        return new ProductDraftGenerationInputResponse.Image(index, image.imageType().name(), image.source().contentType(), image.source().length(),
            image.width(), image.height());
    }
}
