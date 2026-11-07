package com.commercefoundry.domainlayer.sizechart.application.service.processor;

import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartDraftCreateCommand;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartException;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftSummaryInfo;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartGenerationRegistration;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartGenerationResult;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartGenerationStatus;
import com.commercefoundry.domainlayer.sizechart.application.port.out.ProductSizeChartRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartDraftRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartTemplateRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraft;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraftStatus;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartTemplateColumn;
import com.commercefoundry.global.config.SizeChartDraftProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Every mutation commits before remote I/O; a failed unique insert must exit this proxy before replay. */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class SizeChartDraftProcessor {
    private final SizeChartDraftRepositoryPort repository;
    private final SizeChartTemplateRepositoryPort templates;
    private final ProductSizeChartRepositoryPort charts;
    private final SnowflakeIdGenerator ids;
    private final Clock clock;
    private final SizeChartDraftProperties properties;
    private final SizeChartDraftValidationProcessor validation;

    public SizeChartDraft createOrReplay(SizeChartDraftCreateCommand command, String requestHash, Map<Long, Product> products) {
        Optional<SizeChartDraft> existing = findReplay(command, requestHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (!products.containsKey(command.productId())) {
            throw new SizeChartException(SizeChartErrorCode.PRODUCT_NOT_FOUND);
        }
        // Lock with the same ordering used by manual chart writes. Columns and revision form one snapshot.
        var template = templates.lockById(command.templateId())
            .orElseThrow(() -> new SizeChartException(SizeChartErrorCode.TEMPLATE_REFERENCE_NOT_FOUND));
        var columns = templates.findColumns(template.id()).stream()
            .sorted(Comparator.comparingInt(SizeChartTemplateColumn::displayOrder).thenComparingLong(SizeChartTemplateColumn::id)).toList();
        String templateJson = validation.template(template, columns);
        String sourceJson = validation.source(command, template, columns);
        var chart = charts.findByProductId(command.productId()).orElse(null);
        var captured = new SizeChartDraft.Input(command.productId(), template.id(), template.revision(), command.actorId(), command.idempotencyKey(),
            requestHash, "0".repeat(64), sourceJson, templateJson, chart == null ? null : chart.id(), chart == null ? null : chart.revision());
        var input = new SizeChartDraft.Input(captured.productId(), captured.templateId(), captured.templateRevision(), captured.requestedByMemberId(),
            captured.idempotencyKey(), requestHash, validation.inputHash(captured), sourceJson, templateJson, captured.baseChartId(), captured.baseChartRevision());
        return repository.insert(SizeChartDraft.request(ids.generateId(), input, clock.instant().plus(properties.getContentTtl()).truncatedTo(ChronoUnit.MICROS)));
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<SizeChartDraft> replay(SizeChartDraftCreateCommand command, String requestHash) {
        return findReplay(command, requestHash);
    }

    private Optional<SizeChartDraft> findReplay(SizeChartDraftCreateCommand command, String requestHash) {
        return repository.findByRequestedByMemberIdAndIdempotencyKey(command.actorId(), command.idempotencyKey()).map(draft -> {
            live(draft);
            if (!draft.input().requestHash().equals(requestHash)) {
                throw failure(SizeChartDraftTransportErrorCode.INPUT_CONFLICT);
            }
            return draft;
        });
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public SizeChartDraft get(long draftId) {
        SizeChartDraft draft = required(draftId);
        live(draft);
        return draft;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Page<SizeChartDraftSummaryInfo> list(Long productId, Long actorId, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (productId != null && productId <= 0) || (actorId != null && actorId <= 0)) {
            throw failure(SizeChartDraftTransportErrorCode.UNSAFE_INPUT);
        }
        return repository.findPage(productId, actorId, clock.instant(), page, size).map(SizeChartDraftSummaryInfo::from);
    }

    public SizeChartDraft bind(SizeChartDraft observed, SizeChartGenerationRegistration registration) {
        if (registration == null || registration.draftId() != observed.id() || registration.jobId() <= 0
            || !observed.input().inputHash().equals(registration.inputHash())) {
            throw failure(SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
        }
        SizeChartDraft current = required(observed.id());
        sameInput(observed, current);
        live(current);
        if (current.jobId() != null) {
            if (current.jobId() != registration.jobId()) {
                throw failure(SizeChartDraftTransportErrorCode.BINDING_CONFLICT);
            }
            return current; // Another registration/refresh already committed this binding.
        }
        if (current.version() != observed.version() || current.status() != SizeChartDraftStatus.REQUESTED) {
            throw failure(SizeChartDraftTransportErrorCode.BINDING_CONFLICT);
        }
        var now = clock.instant();
        return repository.update(current.bindJob(registration.jobId(), now), now);
    }

    public SizeChartDraft complete(SizeChartDraft observed, SizeChartGenerationResult result, SizeChartDraft.Candidate candidate) {
        verifyResult(observed, result);
        SizeChartDraft current = required(observed.id());
        sameInput(observed, current);
        live(current);
        if (!Objects.equals(current.jobId(), observed.jobId())) {
            throw failure(SizeChartDraftTransportErrorCode.BINDING_CONFLICT);
        }
        if (current.status() != SizeChartDraftStatus.REQUESTED) {
            return current; // A concurrent refresh/apply won; never overwrite its committed state.
        }
        if (current.version() != observed.version()) {
            throw failure(SizeChartDraftTransportErrorCode.BINDING_CONFLICT);
        }
        var now = clock.instant();
        SizeChartDraft changed = switch (result.status()) {
            case PENDING -> current;
            case SUCCEEDED -> current.ready(Objects.requireNonNull(candidate), now);
            case FAILED -> current.fail(now);
            case CANCELLED -> current.cancel(now);
        };
        return changed == current ? current : repository.update(changed, now);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void verifyResult(SizeChartDraft draft, SizeChartGenerationResult result) {
        if (result == null || result.status() == null || draft.jobId() == null || result.jobId() != draft.jobId()
            || result.draftId() != draft.id() || !draft.input().inputHash().equals(result.inputHash())
            || ((result.status() == SizeChartGenerationStatus.SUCCEEDED) != (result.resultJson() != null))) {
            throw failure(SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
        }
        if (result.status() == SizeChartGenerationStatus.FAILED) {
            if (result.failureCode() == null || !List.of("GENERATION_FAILED", "LEASE_EXPIRED", "RUN_TIMEOUT", "HANDLER_ERROR", "RESULT_TOO_LARGE")
                .contains(result.failureCode())) {
                throw failure(SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
            }
        } else if (result.failureCode() != null) {
            throw failure(SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
        }
    }

    private SizeChartDraft required(long id) {
        return repository.findById(id).orElseThrow(() -> failure(SizeChartDraftTransportErrorCode.NOT_FOUND));
    }

    private void sameInput(SizeChartDraft observed, SizeChartDraft current) {
        if (!current.input().equals(observed.input())) {
            throw failure(SizeChartDraftTransportErrorCode.BINDING_CONFLICT);
        }
    }

    private void live(SizeChartDraft draft) {
        if (draft.isExpired(clock.instant())) {
            throw failure(SizeChartDraftTransportErrorCode.EXPIRED);
        }
    }

    private SizeChartDraftTransportException failure(SizeChartDraftTransportErrorCode code) {
        return new SizeChartDraftTransportException(code);
    }
}
