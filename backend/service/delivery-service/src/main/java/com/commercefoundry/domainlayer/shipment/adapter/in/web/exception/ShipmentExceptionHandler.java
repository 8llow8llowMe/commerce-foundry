package com.commercefoundry.domainlayer.shipment.adapter.in.web.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.shipment.application.exception.ShipmentErrorCode;
import com.commercefoundry.domainlayer.shipment.application.exception.ShipmentException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 송장 도메인 오류({@link ShipmentException})만 {@code Response.fail} 봉투로 바꾼다. 지금 송장 등록은 업무 실패를 건별 결과로 내지만,
 * 단건 API(PR-B2 배송 상태 변경 등)가 던지는 예외를 위해 둔다.
 *
 * <p><b>요청 검증 오류는 여기서 잡지 않는다.</b> delivery-service 의 검증 예외는 {@code DeliveryExceptionHandler} 하나가 서비스 공용으로 처리한다 — 검증 메시지가
 * 코드({@code DELIVERY_140:...})를 싣고 오므로 컨텍스트를 몰라도 되고, 두 advice 가 같은 예외 타입을 잡지 않는다 (선례: product {@code DisplayExceptionHandler}).
 * 잡는 타입이 겹치지 않지만 서비스에 advice 가 둘이라 좁은 이쪽에 {@code @Order} 를 달아 순서를 확정한다.
 * security-core 의 {@code SecurityExceptionAdvice}(가장 높은 우선순위)보다는 뒤다.
 */
@Order(Ordered.LOWEST_PRECEDENCE - 1)
@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer")
public class ShipmentExceptionHandler {

    @ExceptionHandler(ShipmentException.class)
    public ResponseEntity<Response<Void>> handleShipmentException(ShipmentException exception) {
        ShipmentErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus()).body(Response.fail(errorCode.getCode(), exception.getMessage()));
    }
}
