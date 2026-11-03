package com.commercefoundry.domainlayer.order.application.port.in;

import com.commercefoundry.domainlayer.order.adapter.in.web.dto.response.OrderReturnResponse;
import com.commercefoundry.domainlayer.order.application.command.OrderReturnCommand;
import java.util.UUID;

public interface OrderReturnWebUseCase {
    Result request(long memberId, long orderId, UUID requestKey, OrderReturnCommand command);

    /** created는 HTTP 상태 선택에만 사용하고 응답 JSON에는 싣지 않는다. */
    record Result(boolean created, OrderReturnResponse claim) {
    }
}
