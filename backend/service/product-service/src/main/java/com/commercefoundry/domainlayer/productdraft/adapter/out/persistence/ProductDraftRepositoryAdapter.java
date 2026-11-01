package com.commercefoundry.domainlayer.productdraft.adapter.out.persistence;

import com.commercefoundry.domainlayer.productdraft.adapter.out.persistence.entity.ProductDraftEntity;
import com.commercefoundry.domainlayer.productdraft.adapter.out.persistence.repository.ProductDraftRepository;
import com.commercefoundry.domainlayer.productdraft.application.port.out.ProductDraftRepositoryPort;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftFacts;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImage;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImageType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 초안 저장. 운영자 입력은 {@code input_json} 한 칸에 이 클래스의 저장 모양({@link InputDocument})으로 둔다 — domain 이름을 바꿔도 저장된 JSON 이 깨지지 않게
 * 저장 모양을 따로 둔다. 매퍼는 서비스 Jackson 설정에 흔들리지 않게 자체 {@link JsonMapper}(Jackson 기본 — 모르는 필드 무시)를 쓴다.
 */
@Component
@RequiredArgsConstructor
public class ProductDraftRepositoryAdapter implements ProductDraftRepositoryPort {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ProductDraftRepository productDraftRepository;

    @Override
    public Optional<ProductDraft> findById(long draftId) {
        return productDraftRepository.findById(draftId).map(ProductDraftRepositoryAdapter::toDomain);
    }

    @Override
    public ProductDraft insert(ProductDraft draft) {
        ProductDraftEntity entity = ProductDraftEntity.builder()
            .id(draft.id())
            .status(draft.status())
            .requestedByMemberId(draft.requestedByMemberId())
            .inputJson(writeInput(draft))
            .rejectedByMemberId(draft.rejectedByMemberId())
            .rejectedAt(draft.rejectedAt())
            .expiresAt(draft.expiresAt())
            .build();
        return toDomain(productDraftRepository.save(entity));
    }

    @Override
    public void updateDecision(ProductDraft draft) {
        ProductDraftEntity entity = productDraftRepository.findById(draft.id())
            .orElseThrow(() -> new IllegalStateException("바꿀 초안이 없습니다. draftId=" + draft.id()));
        entity.decide(draft.status(), draft.rejectedByMemberId(), draft.rejectedAt());
        productDraftRepository.flush();
    }

    private static ProductDraft toDomain(ProductDraftEntity entity) {
        InputDocument input = readInput(entity);
        return new ProductDraft(
            entity.getId(), entity.getStatus(), entity.getRequestedByMemberId(), input.facts().toDomain(),
            input.images().stream().map(ImageDocument::toDomain).toList(),
            entity.getRejectedByMemberId(), entity.getRejectedAt(), entity.getExpiresAt(), toDbPrecision(entity.getCreatedAt())
        );
    }

    /** 방금 INSERT 한 행의 생성 시각은 감사 리스너가 채운 나노초 값이다 — 조회 응답(DB timestamp(6))과 같게 마이크로초로 맞춘다. */
    private static Instant toDbPrecision(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }

    private static String writeInput(ProductDraft draft) {
        return JSON.writeValueAsString(new InputDocument(
            FactsDocument.of(draft.facts()), draft.images().stream().map(ImageDocument::of).toList()
        ));
    }

    /** 읽지 못하거나 사실 칸이 없으면 저장 데이터 오류다 — 원문을 메시지에 싣지 않는다. */
    private static InputDocument readInput(ProductDraftEntity entity) {
        InputDocument input;
        try {
            input = JSON.readValue(entity.getInputJson(), InputDocument.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("초안 입력을 읽을 수 없습니다. draftId=" + entity.getId() + ", cause=" + exception.getClass().getSimpleName());
        }
        if (input == null || input.facts() == null) {
            throw new IllegalStateException("초안 입력에 사실 칸이 없습니다. draftId=" + entity.getId());
        }
        return input;
    }

    /** {@code input_json} 의 저장 모양. 칸은 더하기만 한다 — 이름을 바꾸면 저장된 초안을 읽지 못한다. */
    record InputDocument(FactsDocument facts, List<ImageDocument> images) {

        InputDocument {
            images = images == null ? List.of() : images;
        }
    }

    record FactsDocument(
        String nameHint, Long categoryId, List<String> materials, List<String> careNotes, String measurementsNote, String origin,
        List<String> certifications, String tone, List<String> mustInclude, List<String> mustAvoid
    ) {

        static FactsDocument of(ProductDraftFacts facts) {
            return new FactsDocument(facts.nameHint(), facts.categoryId(), facts.materials(), facts.careNotes(), facts.measurementsNote(), facts.origin(),
                facts.certifications(), facts.tone(), facts.mustInclude(), facts.mustAvoid());
        }

        ProductDraftFacts toDomain() {
            return new ProductDraftFacts(nameHint, categoryId, materials, careNotes, measurementsNote, origin, certifications, tone, mustInclude, mustAvoid);
        }
    }

    record ImageDocument(String url, ProductDraftImageType imageType, Integer width, Integer height) {

        static ImageDocument of(ProductDraftImage image) {
            return new ImageDocument(image.url(), image.imageType(), image.width(), image.height());
        }

        ProductDraftImage toDomain() {
            return new ProductDraftImage(url, imageType, width, height);
        }
    }
}
