package com.commercefoundry.domainlayer.order.application.service;

import com.commercefoundry.domainlayer.order.adapter.in.web.presenter.OrderReturnWebPresenter;
import com.commercefoundry.domainlayer.order.application.command.OrderReturnCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.in.OrderReturnWebUseCase;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderReturnProcessor;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PessimisticLockException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionSystemException;

/** Processor 프록시의 커밋 실패까지 번역하려고 Facade에는 트랜잭션을 걸지 않는다. 실패한 트랜잭션 안에서 재시도하지 않는다. */
@Service
@RequiredArgsConstructor
public class OrderReturnWebFacade implements OrderReturnWebUseCase {
    private final OrderReturnProcessor orderReturnProcessor;
    private final OrderReturnWebPresenter orderReturnWebPresenter;

    @Override
    public Result request(long memberId, long orderId, UUID requestKey, OrderReturnCommand command) {
        try {
            var result = orderReturnProcessor.request(memberId, orderId, requestKey, command);
            return new Result(result.created(), orderReturnWebPresenter.toResponse(result.claim()));
        } catch (ConcurrencyFailureException | OptimisticLockException | PessimisticLockException | LockTimeoutException exception) {
            throw new OrderException(OrderErrorCode.ORDER_CONCURRENTLY_CHANGED);
        } catch (DataAccessException | PersistenceException | TransactionSystemException exception) {
            // 원문 원인 메시지에는 행 값이 포함될 수 있어 응답·새 예외에 넣지 않는다.
            throw new OrderException(OrderErrorCode.ORDER_SAVE_FAILED);
        }
    }
}
