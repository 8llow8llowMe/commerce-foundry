package com.commercefoundry.domainlayer.sizechart.adapter.out.persistence;

import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.entity.SizeChartDraftEntity;
import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.repository.SizeChartDraftRepository;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartDraftRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraft;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraftStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** JSON structure and size are storage checks; sanitization and semantic chart validation belong to the caller. */
@Component
@RequiredArgsConstructor
public class SizeChartDraftRepositoryAdapter implements SizeChartDraftRepositoryPort {

    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private final SizeChartDraftRepository repository;

    @Override
    public Optional<SizeChartDraft> findById(long draftId) {
        return repository.findById(draftId).map(SizeChartDraftEntity::toDomain);
    }

    @Override
    public Optional<SizeChartDraft> findByRequestedByMemberIdAndIdempotencyKey(long actorId, UUID idempotencyKey) {
        return repository.findByRequestedByMemberIdAndIdempotencyKey(actorId, idempotencyKey).map(SizeChartDraftEntity::toDomain);
    }

    @Override
    public Page<SizeChartDraft> findPage(Long productId, Long actorId, Instant now, int page, int size) {
        return repository.findDraftPage(productId, actorId, now, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")))
            .map(SizeChartDraftEntity::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int deleteExpiredBatch(Instant cutoff, int limit) {
        if (cutoff == null || limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("Invalid draft cleanup bounds");
        }
        var ids = repository.findExpiredIds(cutoff, PageRequest.of(0, limit));
        return ids.isEmpty() ? 0 : repository.deleteExpiredIds(ids, cutoff);
    }

    /** Persistable forces INSERT for caller-issued Snowflake IDs. Flush surfaces uniqueness conflicts here. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public SizeChartDraft insert(SizeChartDraft draft) {
        if (draft.status() != SizeChartDraftStatus.REQUESTED || draft.version() != 0 || draft.jobId() != null) {
            throw new IllegalArgumentException("Only a new requested draft can be inserted");
        }
        validateJson(draft);
        SizeChartDraft.Input input = draft.input();
        SizeChartDraftEntity entity = SizeChartDraftEntity.builder()
            .id(draft.id())
            .productId(input.productId())
            .templateId(input.templateId())
            .templateRevision(input.templateRevision())
            .requestedByMemberId(input.requestedByMemberId())
            .idempotencyKey(input.idempotencyKey())
            .requestHash(input.requestHash())
            .inputHash(input.inputHash())
            .sourceJson(input.sourceJson())
            .templateJson(input.templateJson())
            .baseChartId(input.baseChartId())
            .baseChartRevision(input.baseChartRevision())
            .status(draft.status())
            .expiresAt(draft.expiresAt())
            .build();
        return repository.saveAndFlush(entity).toDomain();
    }

    /** The caller owns the transaction shared with chart replacement; no detached merge or bulk state update. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public SizeChartDraft update(SizeChartDraft draft, Instant now) {
        SizeChartDraftEntity entity = repository.findById(draft.id())
            .orElseThrow(() -> new IllegalStateException("Draft not found"));
        if (entity.getVersion() != draft.version()) {
            throw new ObjectOptimisticLockingFailureException(SizeChartDraftEntity.class, draft.id());
        }
        validateJson(draft);
        entity.change(draft, now);
        repository.flush();
        return entity.toDomain();
    }

    private static void validateJson(SizeChartDraft draft) {
        validateDocument(draft.input().sourceJson());
        validateDocument(draft.input().templateJson());
        if (draft.candidate() != null) {
            validateDocument(draft.candidate().json());
        }
        if (draft.applyReceipt() != null) {
            validateDocument(draft.applyReceipt().receiptJson());
        }
    }

    /** Do not retain parser exceptions: their messages and causes may contain the original input. */
    private static void validateDocument(String json) {
        try {
            var tree = JSON.readTree(json);
            if (tree == null || (!tree.isObject() && !tree.isArray())) {
                throw new IllegalArgumentException("Draft JSON must be an object or array");
            }
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Invalid draft JSON");
        }
    }
}
