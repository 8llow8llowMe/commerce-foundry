package com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.client;

import com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.client.feign.ProductDraftGenerationInputClient;
import com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.client.support.ProductDraftGenerationInputInternalResponseSupport;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.ProductDraftGenerationInputPort;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.query.ProductDraftGenerationImageQueryResult;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.query.ProductDraftGenerationInputQueryResult;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductDraftGenerationInputClientAdapter implements ProductDraftGenerationInputPort {

    private final ProductDraftGenerationInputClient client;
    private final ProductDraftGenerationInputInternalResponseSupport support;

    @Override
    public ProductDraftGenerationInputQueryResult getGenerationInput(long draftId) {
        return support.toQueryResult(support.call(() -> client.getGenerationInput(draftId)));
    }

    /**
     * 순번마다 한 번 부른다 — product 계약이 봉투 없는 바이트 한 장씩이라 벌크 엔드포인트가 없다(최대 8장, 검증 Processor 가 장수 · 합계를 먼저 막는다).
     * 한 장이라도 실패하면 거기서 멈춘다.
     */
    @Override
    public List<ProductDraftGenerationImageQueryResult> getGenerationImages(long draftId, List<ProductDraftGenerationInputQueryResult.Image> images) {
        List<ProductDraftGenerationImageQueryResult> result = new ArrayList<>(images.size());
        for (ProductDraftGenerationInputQueryResult.Image image : images) {
            byte[] bytes = support.call(() -> client.getGenerationImage(draftId, image.index()));
            result.add(new ProductDraftGenerationImageQueryResult(image.index(), image.contentType(), bytes));
        }
        return List.copyOf(result);
    }
}
