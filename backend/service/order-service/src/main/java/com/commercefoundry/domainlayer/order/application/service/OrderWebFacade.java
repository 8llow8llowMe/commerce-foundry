package com.commercefoundry.domainlayer.order.application.service;

import com.commercefoundry.domainlayer.catalog.application.service.processor.CatalogSkuProcessor;
import com.commercefoundry.domainlayer.catalog.domain.model.CatalogSku;
import com.commercefoundry.domainlayer.order.adapter.in.web.presenter.OrderWebPresenter;
import com.commercefoundry.domainlayer.order.application.command.OrderCreateCommand;
import com.commercefoundry.domainlayer.order.application.command.OrderItemCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderSaveException;
import com.commercefoundry.domainlayer.order.application.model.OrderRequestClaim;
import com.commercefoundry.domainlayer.order.application.model.StockReservation;
import com.commercefoundry.domainlayer.order.application.port.in.OrderWebUseCase;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderQuoteProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderRequestProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderStockProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuote;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 고객 주문 생성. <b>트랜잭션을 걸지 않는다</b> — 원격 호출(product-service SKU 조회 · 재고 선점, delivery-service 배송비)이 섞여 있어, Facade 에 걸면
 * DB 커넥션을 잡은 채 원격 응답을 기다리고 원격이 성공한 뒤 롤백되면 "재고는 잡혔는데 주문이 없는" 상태가 생긴다. DB 구간은 Processor 의 메서드 단위로 좁힌다
 * ({@code architecture-guide.md} §3 Facade, {@code order-flow-guide.md} §2-4).
 *
 * <p>계산은 미리보기({@code OrderQuoteWebFacade})와 같은 둘(catalog {@link CatalogSkuProcessor} 한 번 → {@link OrderQuoteProcessor})을 같은 순서로 부른다.
 * 로그에는 회원 아이디 · 주문 아이디만 남긴다 — 요청 · command · 요청 지문은 남기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderWebFacade implements OrderWebUseCase {

    private final CatalogSkuProcessor catalogSkuProcessor;
    private final OrderQuoteProcessor orderQuoteProcessor;
    private final OrderRequestProcessor orderRequestProcessor;
    private final OrderStockProcessor orderStockProcessor;
    private final OrderProcessor orderProcessor;
    private final OrderWebPresenter orderWebPresenter;

    /**
     * 생성 순서 (트랜잭션 구간은 [ ] 안, 그 밖은 리포지토리 호출 하나씩 짧은 트랜잭션이거나 원격).
     *
     * <ol>
     *   <li>기록을 본다 — 다른 본문이면 409, 완료면 저장된 주문을 다시 읽어 200(원격 호출 없음), lease 안의 처리 중이면 409</li>
     *   <li>계산 — SKU 조회 → 항목 판정 → <b>배송비 견적</b> → 금액 → {@code expectedTotalAmount} 대조. 부작용이 없다 — 거절이면 아무것도 쓰지 않는다</li>
     *   <li>기록을 잡는다 — 새 기록을 넣거나 lease 가 지난 기록을 같은 주문 아이디로 이어받는다. 충돌이면 다시 읽어 1 의 규칙으로 답한다</li>
     *   <li>재고 선점 (주문 아이디가 업무 키, 마지막 원격 호출)</li>
     *   <li>[저장] 기록 완료(버전 비교) + 주문 · 배송지 · 항목 · 입력 · 상태 이력</li>
     * </ol>
     *
     * <ul>
     *   <li>4 · 5 의 처리한 실패는 기록의 lease 를 바로 끝낸다(기록 · 주문 아이디는 남는다) — 같은 키의 재시도가 곧바로 이어받아 같은 주문 아이디로 다시 선점하고,
     *       product 는 기존 선점을 200 으로 준다. 재시도가 없으면 선점 TTL 이 푼다</li>
     *   <li><b>보상 해제(release)는 하지 않는다</b> — 선점은 주문 아이디로 시도 사이에 공유되므로, 풀면 다른 시도가 커밋한 주문의 재고를 깰 수 있다</li>
     *   <li>5 에서 같은 주문 아이디가 이미 저장됐으면 그 주문을 다시 읽어 200 이다</li>
     * </ul>
     */
    @Override
    public Result create(long memberId, UUID idempotencyKey, OrderCreateCommand command) {
        String requestHash = command.fingerprint();
        OrderRequestClaim checked = orderRequestProcessor.check(memberId, idempotencyKey, requestHash);
        if (checked.isReplay()) {
            return replay(memberId, checked.orderId());
        }

        Set<Long> skuIds = new LinkedHashSet<>();
        command.items().stream().map(OrderItemCommand::skuId).forEach(skuIds::add);
        Map<Long, CatalogSku> skus = catalogSkuProcessor.findSkus(skuIds);
        OrderQuote quote = orderQuoteProcessor.quote(command.toQuoteCommand(), skus);
        orderProcessor.requireExpectedTotal(quote, command.expectedTotalAmount());

        OrderRequestClaim claim = orderRequestProcessor.acquire(memberId, idempotencyKey, requestHash, checked);
        if (claim.isReplay()) {
            return replay(memberId, claim.orderId());
        }
        Order order;
        try {
            StockReservation reservation = orderStockProcessor.reserve(claim.orderId(), quote);
            order = orderProcessor.create(memberId, claim.request(), command, quote, reservation);
        } catch (OrderSaveException exception) {
            // 같은 주문 아이디를 다른 시도가 먼저 저장했다 — 같은 주문을 돌려준다
            log.info("order already saved by another attempt. orderId={}", claim.orderId());
            return replay(memberId, claim.orderId());
        } catch (RuntimeException exception) {
            orderRequestProcessor.endLeaseQuietly(claim.request());
            throw exception;
        }
        return new Result(true, orderWebPresenter.toResponse(order));
    }

    private Result replay(long memberId, long orderId) {
        return new Result(false, orderWebPresenter.toResponse(orderProcessor.getOwned(memberId, orderId)));
    }
}
