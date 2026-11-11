package com.commercefoundry.domainlayer.sizechart.application.service;

import com.commercefoundry.domainlayer.adminaudit.application.service.processor.ProductAdminAuditProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductImageLookupProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductLookupProcessor;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftAcceptedResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartImageInspectionResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartImageSourceResponse;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftImage;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartImageSourceProcessor;
import com.commercefoundry.global.config.SizeChartDraftProperties;
import java.util.Map;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftApplyResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftSummaryResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.presenter.SizeChartDraftAdminPresenter;
import com.commercefoundry.domainlayer.sizechart.application.command.ProductSizeChartUpsertCommand;
import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartDraftApplyCommand;
import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartDraftCreateCommand;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartException;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftApplyInfo;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftInfo;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftSummaryInfo;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftUnavailableException;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartGenerationStatus;
import com.commercefoundry.domainlayer.sizechart.application.port.in.SizeChartDraftUseCase;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftApplyProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftRemoteProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftValidationProcessor;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraft;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraftStatus;
import com.commercefoundry.persistence.dto.PageResponse;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
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

    @Override
    public SizeChartDraftAcceptedResponse createAdmin(SizeChartDraftCreateCommand command) {
        return presenter.accepted(create(command));
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
                var found = products.findAllIncludingHidden(List.of(command.productId()));
                // IMAGE: 저장 전, 트랜잭션 밖에서 지금 바이트를 다시 읽어 승인 해시와 대조한다. 바뀌었으면 초안을 만들지 않는다.
                List<SizeChartDraftImage> approved = image ? imageSources.approve(command.productId(), product(found, command.productId()),
                    productImages.findStillImagesIncludingHidden(command.productId()), command.images()) : List.of();
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
