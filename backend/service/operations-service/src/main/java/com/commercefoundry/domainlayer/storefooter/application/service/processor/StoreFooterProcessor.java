package com.commercefoundry.domainlayer.storefooter.application.service.processor;

import com.commercefoundry.domainlayer.storefooter.application.command.StoreFooterCommands;
import com.commercefoundry.domainlayer.storefooter.application.exception.StoreFooterErrorCode;
import com.commercefoundry.domainlayer.storefooter.application.exception.StoreFooterException;
import com.commercefoundry.domainlayer.storefooter.application.info.StoreFooterInfos;
import com.commercefoundry.domainlayer.storefooter.application.port.out.StoreFooterRepositoryPort;
import com.commercefoundry.domainlayer.storefooter.application.port.out.query.StoreFooterQueryResults;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterRevision;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterRuleException;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterUrlPolicy;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterValidator;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StoreFooterProcessor {
    private final StoreFooterRepositoryPort port;
    private final StoreFooterUrlPolicy urls;
    private final Clock clock;
    private final SnowflakeIdGenerator idGenerator;

    public StoreFooterInfos.Detail detail() {
        return info(port.detail().orElseThrow(() -> new StoreFooterException(StoreFooterErrorCode.NOT_FOUND)));
    }

    public PageResponse<StoreFooterRevision> revisions(int page, int size) {
        if (page < 0 || size < 1 || size > 20) {
            throw new StoreFooterException(StoreFooterErrorCode.INVALID_REQUEST);
        }
        return PageResponse.of(port.revisions(PageRequest.of(page, size, Sort.by("revisionNumber").descending())));
    }

    public StoreFooterInfos.Published published(String locale) {
        if (!"ko".equals(locale) && !"en".equals(locale)) {
            throw new StoreFooterException(StoreFooterErrorCode.INVALID_REQUEST);
        }
        var content = port.published().orElseThrow(() -> new StoreFooterException(StoreFooterErrorCode.NOT_FOUND)).content();
        List<StoreFooterInfos.Published.Link> visible = content.links().stream().filter(link -> Boolean.TRUE.equals(link.visible()))
            .sorted(Comparator.comparingInt(link -> link.displayOrder()))
            .map(link -> new StoreFooterInfos.Published.Link(link.type(), link.labels().localized(locale), link.url())).toList();
        return new StoreFooterInfos.Published(content.copyrightText().localized(locale), visible);
    }

    public StoreFooterInfos.Detail save(long actorMemberId, StoreFooterCommands.SaveDraft command) {
        return write(() -> {
            version(command.expectedVersion(), false);
            StoreFooterValidator.validate(command.content(), urls);
            var linkIds = command.content().links().stream().map(link -> idGenerator.generateId()).toList();
            return port.saveDraft(command.expectedVersion(), command.content(), idGenerator.generateId(), linkIds,
                actorMemberId, clock.instant().truncatedTo(ChronoUnit.MICROS));
        });
    }

    public StoreFooterInfos.Detail publish(long actorMemberId, StoreFooterCommands.Publish command) {
        return write(() -> {
            version(command.expectedVersion(), true);
            if (command.revisionNumber() == null || command.revisionNumber() < 1) {
                throw new StoreFooterException(StoreFooterErrorCode.REVISION);
            }
            return port.publish(command.expectedVersion(), command.revisionNumber(), actorMemberId, clock.instant().truncatedTo(ChronoUnit.MICROS));
        });
    }

    private StoreFooterInfos.Detail write(Supplier<StoreFooterQueryResults.Detail> operation) {
        try {
            return info(operation.get());
        } catch (StoreFooterRuleException exception) {
            throw new StoreFooterException(StoreFooterErrorCode.valueOf(exception.reason().name()));
        }
    }

    private void version(Long version, boolean required) {
        if ((required && version == null) || (version != null && version < 0)) {
            throw new StoreFooterException(StoreFooterErrorCode.VERSION);
        }
    }

    private StoreFooterInfos.Detail info(StoreFooterQueryResults.Detail result) {
        return new StoreFooterInfos.Detail(result.footer(), result.revision());
    }
}
