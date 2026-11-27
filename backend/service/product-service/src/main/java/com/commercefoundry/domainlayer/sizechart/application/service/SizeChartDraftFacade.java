package com.commercefoundry.domainlayer.sizechart.application.service;

import com.commercefoundry.common.exception.RedactedThrowable;
import com.commercefoundry.domainlayer.adminaudit.application.service.processor.ProductAdminAuditProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductImageLookupProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductLookupProcessor;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.ProductImage;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftAcceptedResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftApplyResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftBatchResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftSummaryResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartImageInspectionResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartImageSourceResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartTextSourceResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.presenter.SizeChartDraftAdminPresenter;
import com.commercefoundry.domainlayer.sizechart.application.command.ProductSizeChartUpsertCommand;
import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartDraftApplyCommand;
import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartDraftCreateCommand;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartException;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftApplyInfo;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftBatchItemInfo;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftInfo;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftSummaryInfo;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftImage;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftUnavailableException;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartGenerationStatus;
import com.commercefoundry.domainlayer.sizechart.application.port.in.SizeChartDraftUseCase;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftApplyProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftBatchProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftRemoteProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftValidationProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartImageSourceProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartTextSourceProcessor;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraft;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraftStatus;
import com.commercefoundry.global.config.SizeChartDraftProperties;
import com.commercefoundry.persistence.dto.PageResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
// Suspend any caller transaction: operations must see the committed input, and network I/O must hold no DB transaction.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class SizeChartDraftFacade implements SizeChartDraftUseCase {
    private final SizeChartDraftApplyProcessor applyProcessor;
    private final SizeChartDraftProcessor processor;
    private final SizeChartDraftValidationProcessor validation;
    private final SizeChartDraftRemoteProcessor remote;
    private final ProductLookupProcessor products;
    private final Clock clock;
    private final ProductAdminAuditProcessor audit;
    private final SizeChartDraftAdminPresenter presenter;
    private final SizeChartImageSourceProcessor imageSources;
    private final ProductImageLookupProcessor productImages;
    private final SizeChartDraftProperties properties;
    private final SizeChartTextSourceProcessor textSources;
    private final SizeChartDraftBatchProcessor batches;

    @Override
    public SizeChartDraftAcceptedResponse createAdmin(SizeChartDraftCreateCommand command) {
        return presenter.accepted(create(command));
    }

    @Override
    public List<SizeChartTextSourceResponse> textSourcesAdmin(long productId) {
        return presenter.textSources(textSources.sources(product(productId)));
    }

    /**
     * 외부 I/O(operations 작업 등록)가 항목마다 있어 배치 전체에 트랜잭션을 걸지 않는다. 각 항목은 단건과 같은 경로로 Processor 가 커밋한 뒤 원격 등록하고,
     * 기존 멱등 영수증(운영자 · UUID · 정규 입력)을 그대로 재사용한다. 그래서 실패 항목만 같은 키로 다시 보내면 이미 만든 초안을 재생한다.
     *
     * <p>감사: 항목마다 단건과 같은 {@code SIZE_CHART_DRAFT_CREATE}(product:&lt;id&gt;) 줄을 남기고, 끝에 배치 요청 자체의
     * {@code SIZE_CHART_DRAFT_BATCH_CREATE} 줄을 하나 남긴다. 배치 줄은 항목 일부가 실패해도 결과를 돌려줬으면 SUCCESS 다.
     */
    @Override
    public SizeChartDraftBatchResponse createBatchAdmin(
        List<SizeChartDraftCreateCommand> commands) {
        Long actorId = commands == null || commands.isEmpty() || commands.getFirst() == null ? null : commands.getFirst().actorId();
        return presenter.batch(audited(() -> createBatch(commands), succeeded -> audit.sizeChartDraftBatchCreated(actorId, succeeded)));
    }

    private List<SizeChartDraftBatchItemInfo> createBatch(List<SizeChartDraftCreateCommand> commands) {
        batches.validate(commands); // 0개 · 상한 초과 · 중복 상품 · 중복 키는 조회와 쓰기 전에 거절한다.
        Map<Long, Product> found;
        Map<Long, List<ProductImage>> images;
        try {
            found = products.findAllIncludingHidden(commands.stream().map(SizeChartDraftCreateCommand::productId).toList());
            var imageProductIds = properties.isImageEnabled() ? commands.stream().filter(c -> "IMAGE".equals(c.mode()))
                .map(SizeChartDraftCreateCommand::productId).toList() : List.<Long>of();
            images = imageProductIds.isEmpty() ? Map.of() : productImages.findStillImagesIncludingHidden(imageProductIds).stream()
                .collect(Collectors.groupingBy(ProductImage::productId));
        } catch (RuntimeException exception) {
            // 사전 벌크 조회 실패에는 어떤 항목도 등록하지 않았다. 안전한 no-store 오류 봉투로 돌려준다. 로그는 타입 · 프레임만 남긴다.
            log.warn("size chart draft batch lookup failed. items={}", commands.size(), RedactedThrowable.of(exception));
            throw new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.BATCH_ITEM_UNAVAILABLE);
        }
        List<SizeChartDraftBatchItemInfo> results = new ArrayList<>();
        for (var command : commands) {
            try {
                var info = audited(() -> createDraft(command, found, images.getOrDefault(command.productId(), List.of())),
                    succeeded -> audit.sizeChartDraftCreated(command.actorId(), command.productId(), succeeded));
                results.add(batches.accepted(command, info));
            } catch (RuntimeException exception) {
                // 항목 실패는 그 항목에만 격리한다. 오류 코드 · 재시도 판정은 Processor 가 단건 API 와 같은 체계로 한다.
                results.add(batches.failed(command, exception));
            }
        }
        return results;
    }

    @Override
    public List<SizeChartImageSourceResponse> imageSourcesAdmin(long productId) {
        requireImageEnabled();
        Product product = product(productId);
        return presenter.imageSources(imageSources.sources(productId, product, productImages.findStillImagesIncludingHidden(productId)));
    }

    /** 스토리지 읽기(외부 I/O)가 있어 트랜잭션을 걸지 않는다. 상품 · 이미지 조회는 각 Processor 의 짧은 읽기다. */
    @Override
    public SizeChartImageInspectionResponse inspectImageAdmin(long productId, String sourceId) {
        requireImageEnabled();
        Product product = product(productId);
        return presenter.inspection(imageSources.inspect(productId, product, productImages.findStillImagesIncludingHidden(productId), sourceId));
    }

    private void requireImageEnabled() {
        if (!properties.isImageEnabled()) {
            throw new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.IMAGE_INPUT_DISABLED);
        }
    }

    private Product product(long productId) {
        return product(products.findAllIncludingHidden(List.of(productId)), productId);
    }

    private static Product product(Map<Long, Product> found, long productId) {
        Product product = found.get(productId);
        if (product == null) {
            throw new SizeChartException(SizeChartErrorCode.PRODUCT_NOT_FOUND);
        }
        return product;
    }

    @Override
    public SizeChartDraftResponse getAdmin(long draftId) {
        return presenter.detail(get(draftId));
    }

    @Override
    public PageResponse<SizeChartDraftSummaryResponse> listAdmin(Long productId, Long requestedByMemberId, int page, int size) {
        return presenter.page(list(productId, requestedByMemberId, page, size));
    }

    @Override
    public SizeChartDraftResponse refreshAdmin(long draftId, long actorId) {
        return presenter.detail(refresh(draftId, actorId));
    }

    @Override
    public SizeChartDraftApplyResponse applyAdmin(long draftId, long expectedDraftVersion, ProductSizeChartUpsertCommand correctedChart,
        Set<String> acknowledgedWarningIds, long actorId) {
        return presenter.applied(apply(draftId, expectedDraftVersion, correctedChart, acknowledgedWarningIds, actorId));
    }

    @Override
    public SizeChartDraftInfo create(SizeChartDraftCreateCommand command) {
        return audited(() -> createDraft(command), succeeded -> {
            audit.sizeChartDraftCreated(command.actorId(), command.productId(), succeeded);
        });
    }

    private SizeChartDraftInfo createDraft(SizeChartDraftCreateCommand command) {
        return createDraft(command, null, null);
    }

    private SizeChartDraftInfo createDraft(SizeChartDraftCreateCommand command, Map<Long, Product> preparedProducts,
        List<ProductImage> preparedImages) {
        boolean image = command != null && "IMAGE".equals(command.mode());
        if (image) {
            requireImageEnabled();
        }
        String requestHash = validation.normalizedRequestHash(command);
        SizeChartDraft draft;
        try {
            var replay = processor.replay(command, requestHash);
            if (replay.isPresent()) {
                draft = replay.get();
            } else {
                var found = preparedProducts == null ? products.findAllIncludingHidden(List.of(command.productId())) : preparedProducts;
                if (command.textSelection() != null) {
                    // 일괄의 벌크 스냅숏은 앞 항목의 원격 등록 동안 낡을 수 있다. 본문 대조는 단건처럼 이 상품을 지금 다시 읽어 한다.
                    var current = preparedProducts == null ? found : products.findAllIncludingHidden(List.of(command.productId()));
                    textSources.verify(product(current, command.productId()), command);
                }
                // IMAGE: 저장 전, 트랜잭션 밖에서 지금 바이트를 다시 읽어 승인 해시와 대조한다. 바뀌었으면 초안을 만들지 않는다.
                List<SizeChartDraftImage> approved = image ? imageSources.approve(command.productId(), product(found, command.productId()),
                    preparedImages == null ? productImages.findStillImagesIncludingHidden(command.productId()) : preparedImages, command.images()) : List.of();
                draft = processor.createOrReplay(command, requestHash, found, approved);
            }
        } catch (DataIntegrityViolationException exception) {
            // The insert transaction has rolled back. Re-read the winning actor+key in a fresh transaction.
            draft = processor.replay(command, requestHash)
                .orElseThrow(() -> new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.BINDING_CONFLICT));
        }
        if (draft.status() == SizeChartDraftStatus.REQUESTED && draft.jobId() == null && !draft.isExpired(clock.instant())) {
            draft = register(draft);
        }
        if (draft.jobId() == null || draft.jobId() <= 0) {
            throw new SizeChartDraftUnavailableException(draft.id(), SizeChartDraftTransportErrorCode.REGISTRATION_UNAVAILABLE);
        }
        return SizeChartDraftInfo.from(draft);
    }

    @Override
    public SizeChartDraftInfo get(long draftId) {
        return SizeChartDraftInfo.from(processor.get(draftId));
    }

    @Override
    public Page<SizeChartDraftSummaryInfo> list(Long productId, Long requestedByMemberId, int page, int size) {
        return processor.list(productId, requestedByMemberId, page, size);
    }

    @Override
    public SizeChartDraftInfo refresh(long draftId, long actorId) {
        return audited(() -> refreshDraft(draftId), succeeded -> {
            audit.sizeChartDraftRefreshed(actorId, draftId, succeeded);
        });
    }

    private SizeChartDraftInfo refreshDraft(long draftId) {
        SizeChartDraft draft = processor.get(draftId);
        if (draft.isExpired(clock.instant())) {
            throw new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.EXPIRED);
        }
        if (draft.status() != SizeChartDraftStatus.REQUESTED) {
            return SizeChartDraftInfo.from(draft);
        }
        if (draft.jobId() == null) {
            draft = register(draft);
        }
        if (draft.status() != SizeChartDraftStatus.REQUESTED) {
            return SizeChartDraftInfo.from(draft);
        }
        var result = remote.result(draft);
        processor.verifyResult(draft, result);
        SizeChartDraft.Candidate candidate = result.status() == SizeChartGenerationStatus.SUCCEEDED
            ? validation.candidate(result.resultJson(), draft.input().templateJson()) : null;
        SizeChartDraft completed;
        try {
            completed = processor.complete(draft, result, candidate);
        } catch (OptimisticLockingFailureException exception) {
            completed = processor.complete(draft, result, candidate); // Fresh TX observes the winning refresh/binding.
        }
        return SizeChartDraftInfo.from(completed);
    }

    @Override
    public SizeChartDraftApplyInfo apply(
        long draftId, long expectedDraftVersion,
        ProductSizeChartUpsertCommand correctedChart,
        Set<String> acknowledgedWarningIds, long actorId) {
        return audited(() -> applyDraft(draftId, expectedDraftVersion, correctedChart, acknowledgedWarningIds, actorId), succeeded -> {
            audit.sizeChartDraftApplied(actorId, draftId, succeeded);
        });
    }

    private SizeChartDraftApplyInfo applyDraft(
        long draftId, long expectedDraftVersion,
        ProductSizeChartUpsertCommand correctedChart,
        Set<String> acknowledgedWarningIds, long actorId) {
        var command = new SizeChartDraftApplyCommand(
            draftId, expectedDraftVersion, correctedChart, acknowledgedWarningIds, actorId);
        // Refresh performs remote I/O before entering the atomic product apply transaction.
        SizeChartDraft draft = processor.get(draftId);
        if (draft.status() == SizeChartDraftStatus.REQUESTED) {
            refreshDraft(draftId);
        }
        var replay = applyProcessor.replayApplied(command);
        if (replay.isPresent()) {
            return replay.get();
        }
        var found = products.findAllIncludingHidden(List.of(draft.input().productId()));
        try {
            return applyProcessor.apply(command, found);
        } catch (OptimisticLockingFailureException exception) {
            return applyProcessor.replayApplied(command)
                .orElseThrow(() -> new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.BINDING_CONFLICT));
        } catch (SizeChartDraftTransportException exception) {
            if (exception.getErrorCode() == SizeChartDraftTransportErrorCode.CHART_CHANGED) {
                return applyProcessor.replayApplied(command).orElseThrow(() -> exception);
            }
            throw exception;
        } catch (PessimisticLockingFailureException | QueryTimeoutException
            | TransactionTimedOutException exception) {
            throw new SizeChartException(
                SizeChartErrorCode.SIZE_CHART_BUSY, exception);
        }
    }

    private SizeChartDraft register(SizeChartDraft draft) {
        var registration = remote.register(draft);
        try {
            try {
                return processor.bind(draft, registration);
            } catch (OptimisticLockingFailureException exception) {
                return processor.bind(draft, registration);
            }
        } catch (DataAccessException exception) {
            // Registration may have committed remotely even though the local binding could not be saved.
            throw new SizeChartDraftUnavailableException(draft.id(), SizeChartDraftTransportErrorCode.REGISTRATION_UNAVAILABLE);
        }
    }

    /**
     * Processor 트랜잭션 프록시가 커밋 또는 롤백을 마친 뒤 감사한다. 실패한 커밋도 FAILURE다.
     * apply 내부 결과 동기화는 별도 운영자 refresh 행위로 기록하지 않는다.
     */
    private <T> T audited(Supplier<T> operation, Consumer<Boolean> completion) {
        boolean succeeded = false;
        try {
            T result = operation.get();
            succeeded = true;
            return result;
        } finally {
            completion.accept(succeeded);
        }
    }

}
