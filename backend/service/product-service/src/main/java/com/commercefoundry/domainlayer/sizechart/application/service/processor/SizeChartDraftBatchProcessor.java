package com.commercefoundry.domainlayer.sizechart.application.service.processor;

import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartDraftCreateCommand;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 배치 전체의 구조만 검사한다. 단건 입력·템플릿 검사는 개별 트랜잭션에서 수행한다. */
@Component
public class SizeChartDraftBatchProcessor {
    public void validate(List<SizeChartDraftCreateCommand> commands) {
        if (commands == null || commands.isEmpty() || commands.size() > 20) {
            throw invalid();
        }
        Set<Long> products = new HashSet<>();
        Set<UUID> keys = new HashSet<>();
        Long actor = null;
        for (var command : commands) {
            if (command == null || command.productId() <= 0 || command.actorId() <= 0 || command.idempotencyKey() == null
                || !products.add(command.productId()) || !keys.add(command.idempotencyKey())
                || actor != null && actor.longValue() != command.actorId()) {
                throw invalid();
            }
            actor = command.actorId();
        }
    }

    private SizeChartDraftTransportException invalid() {
        return new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.INVALID_BATCH);
    }
}
