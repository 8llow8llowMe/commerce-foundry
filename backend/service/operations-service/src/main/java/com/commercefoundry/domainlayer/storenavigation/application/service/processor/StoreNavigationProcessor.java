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

    public StoreNavigationInfos.Detail save(long actorMemberId, StoreNavigationCommands.SaveDraft command) {
        return write(() -> {
            version(command.expectedVersion());
            StoreNavigationValidator.validate(command.content(), urls);
            return port.saveDraft(command.expectedVersion(), command.content().ordered(), idGenerator.generateId(),
                actorMemberId, clock.instant().truncatedTo(ChronoUnit.MICROS));
        });
    }

    private StoreNavigationInfos.Detail write(Supplier<StoreNavigationQueryResults.Detail> operation) {
        try {
            return info(operation.get());
        } catch (StoreNavigationRuleException exception) {
            throw new StoreNavigationException(StoreNavigationErrorCode.valueOf(exception.reason().name()));
        }
    }

    private void version(Long version) {
        if (version != null && version < 0) {
            throw new StoreNavigationException(StoreNavigationErrorCode.VERSION);
        }
    }

    private StoreNavigationInfos.Detail info(StoreNavigationQueryResults.Detail result) {
        return new StoreNavigationInfos.Detail(result.navigation(), result.revision());
    }
}
