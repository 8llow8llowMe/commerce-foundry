package com.commercefoundry.domainlayer.storenavigation.application.service.processor;

import com.commercefoundry.domainlayer.storenavigation.application.command.StoreNavigationCommands;
import com.commercefoundry.domainlayer.storenavigation.application.exception.StoreNavigationErrorCode;
import com.commercefoundry.domainlayer.storenavigation.application.exception.StoreNavigationException;
import com.commercefoundry.domainlayer.storenavigation.application.info.StoreNavigationInfos;
import com.commercefoundry.domainlayer.storenavigation.application.port.out.StoreNavigationRepositoryPort;
import com.commercefoundry.domainlayer.storenavigation.application.port.out.query.StoreNavigationQueryResults;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationRuleException;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationUrlPolicy;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationValidator;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StoreNavigationProcessor {
    private final StoreNavigationRepositoryPort port;
    private final StoreNavigationUrlPolicy urls;
    private final Clock clock;
    private final SnowflakeIdGenerator idGenerator;

    public StoreNavigationInfos.Detail detail() {
        return info(port.detail().orElseThrow(() -> new StoreNavigationException(StoreNavigationErrorCode.NOT_FOUND)));
    }

    /** 공개 트리는 게시본에서만 만든다. 초안은 내리지 않으며 언어 fallback 은 없다. 미등록·미게시는 404 다. */
    public StoreNavigationInfos.Published published(String locale) {
        if (!"ko".equals(locale) && !"en".equals(locale)) {
            throw new StoreNavigationException(StoreNavigationErrorCode.INVALID_REQUEST);
        }
        var revision = port.published().orElseThrow(() -> new StoreNavigationException(StoreNavigationErrorCode.NOT_FOUND));
        return new StoreNavigationInfos.Published(revision.content().publicTree(locale));
    }

    public StoreNavigationInfos.Detail save(long actorMemberId, StoreNavigationCommands.SaveDraft command) {
        return write(() -> {
            version(command.expectedVersion(), false);
            StoreNavigationValidator.validate(command.content(), urls);
            return port.saveDraft(command.expectedVersion(), command.content().ordered(), idGenerator.generateId(),
                actorMemberId, clock.instant().truncatedTo(ChronoUnit.MICROS));
        });
    }

    public StoreNavigationInfos.Detail publish(long actorMemberId, StoreNavigationCommands.Publish command) {
        return write(() -> {
            version(command.expectedVersion(), true);
            if (command.revisionNumber() == null || command.revisionNumber() < 1) {
                throw new StoreNavigationException(StoreNavigationErrorCode.REVISION);
            }
            return port.publish(command.expectedVersion(), command.revisionNumber(), actorMemberId,
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        });
    }

    private StoreNavigationInfos.Detail write(Supplier<StoreNavigationQueryResults.Detail> operation) {
        try {
            return info(operation.get());
        } catch (StoreNavigationRuleException exception) {
            throw new StoreNavigationException(StoreNavigationErrorCode.valueOf(exception.reason().name()));
        }
    }

    private void version(Long version, boolean required) {
        if ((required && version == null) || (version != null && version < 0)) {
            throw new StoreNavigationException(StoreNavigationErrorCode.VERSION);
        }
    }

    private StoreNavigationInfos.Detail info(StoreNavigationQueryResults.Detail result) {
        return new StoreNavigationInfos.Detail(result.navigation(), result.revision());
    }
}
