package com.commercefoundry.domainlayer.payment.adapter.out.client.pg.toss;

import com.commercefoundry.domainlayer.payment.adapter.out.client.pg.toss.dto.TossErrorResponse;
import com.commercefoundry.domainlayer.payment.adapter.out.client.pg.toss.dto.TossPaymentResponse;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgApprovalQueryResult;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgApprovalQueryResult.NotSentReason;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PgInquiryQueryResult;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentApproval;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentMethod;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import feign.FeignException;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 토스페이먼츠 호출의 서킷 · 결과 분류 ({@code payment-guide.md} §4, {@code coding-conventions.md} §10). Feign 예외와 토스 응답 모양을 이 밖으로 내보내지 않는다.
 *
 * <table>
 *   <caption>승인 요청 결과 분류 ({@link #approve})</caption>
 *   <tr><th>토스 응답</th><th>결과</th></tr>
 *   <tr><td>2xx · status DONE · 필수 칸(paymentKey · orderId(요청과 같음) · totalAmount · currency · approvedAt) 있음</td><td>Approved — 금액 · 결제키 대조는 도메인</td></tr>
 *   <tr><td>2xx 인데 status ≠ DONE · 필수 칸 누락 · 해석 불가(approvedAt 형식 포함) · 다른 주문번호 · 모르는 통화</td><td>Unknown(CONTRACT)</td></tr>
 *   <tr><td>409 · ALREADY_PROCESSED_PAYMENT · ALREADY_PROCESSING_REQUEST · DUPLICATED_ORDER_ID</td><td>Unknown(ALREADY_PROCESSED) — 앞선 요청이 승인됐을 수 있다</td></tr>
 *   <tr><td>401 · INVALID_API_KEY · INCORRECT_BASIC_AUTH_FORMAT</td><td>NotSent(CONFIG_ERROR) — 인증 단계에서 막혔다, error 로그</td></tr>
 *   <tr><td>PROVIDER_ERROR · CARD_PROCESSING_ERROR</td><td>Unknown(SERVER_ERROR) — 결제기관 쪽 처리 중 오류라 처리됐는지 모른다</td></tr>
 *   <tr><td>{@link #REJECTION_CODES}(토스 승인 오류표의 처리 전 거절 — 카드 거절 · 한도 초과 · 잘못된 요청 · 인증 만료 등)</td><td>Rejected(code, message)</td></tr>
 *   <tr><td>429 · 5xx</td><td>Unknown(SERVER_ERROR)</td></tr>
 *   <tr><td>오류표에 없는 4xx 코드 · 코드를 읽지 못한 4xx · 3xx</td><td>Unknown(CONTRACT)</td></tr>
 *   <tr><td>서킷 오픈</td><td>NotSent(CIRCUIT_OPEN)</td></tr>
 *   <tr><td>연결 실패 — 원인 체인에 {@link ConnectException} · {@link UnknownHostException} · {@link NoRouteToHostException}</td><td>NotSent(CONNECT_FAILED)</td></tr>
 *   <tr><td>read timeout · 그 밖의 I/O 오류(보내는 중 끊김 등) · 연결 단계인지 가를 수 없는 오류(연결 타임아웃 포함)</td><td>Unknown(TIMEOUT)</td></tr>
 * </table>
 *
 * <table>
 *   <caption>조회 결과 분류 ({@link #inquire}) — 주문번호 조회 · 결제키 조회 공통</caption>
 *   <tr><th>토스 응답</th><th>결과</th></tr>
 *   <tr><td>DONE (필수 칸 있음)</td><td>Approved</td></tr>
 *   <tr><td>ABORTED · EXPIRED</td><td>Failed(paymentKey, failure.code — 없으면 status)</td></tr>
 *   <tr><td>READY · IN_PROGRESS</td><td>Pending(paymentKey)</td></tr>
 *   <tr><td>404 NOT_FOUND_PAYMENT</td><td>NotFound — 미승인의 증거가 아니다</td></tr>
 *   <tr><td>CANCELED · PARTIAL_CANCELED · WAITING_FOR_DEPOSIT</td><td>Unknown(MANUAL_REVIEW)</td></tr>
 *   <tr><td>5xx · 429 · 403 FORBIDDEN_CONSECUTIVE_REQUEST · 서킷 · 연결 실패</td><td>Unknown(SERVER_ERROR)</td></tr>
 *   <tr><td>타임아웃 · I/O 오류</td><td>Unknown(TIMEOUT)</td></tr>
 *   <tr><td>다른 주문번호 · 다른 결제키(결제키 조회) · 결제키 없음 · 모르는 상태 · 해석 불가 · 그 밖의 4xx</td><td>Unknown(CONTRACT)</td></tr>
 * </table>
 *
 * <ul>
 *   <li>보냈는지 조금이라도 모르면 Unknown 이다 — NotSent 는 승인이 일어나지 않은 것이 확실할 때만이다. 연결 타임아웃은 JDK 클라이언트가 read timeout 과 같은 예외
 *       ({@code SocketTimeoutException})로 내서 가르지 않고 Unknown 으로 둔다(보수적).</li>
 *   <li>Rejected 는 토스 승인 오류표에 있는 "처리 전 거절" 코드만이다. 표에 없는 코드는 뜻을 모르니 거절로 확정하지 않는다(Unknown(CONTRACT)) — 거절로 확정해
 *       활성 슬롯을 놓았는데 실은 승인됐으면 이중 결제가 된다. 토스가 코드를 더하면 {@link #REJECTION_CODES} 를 고친다.</li>
 *   <li>서킷 인스턴스는 {@value #CIRCUIT_BREAKER}. 4xx({@code FeignClientException})는 서킷 안에서 그대로 던져 실패로 세지 않는다
 *       ({@link TossCircuitBreakerIgnorePredicate}) — 카드 거절이 몰려도 서킷이 열리지 않는다. 429 만은 실패로 센다. 응답 본문 해석은 서킷 밖에서 한다.</li>
 *   <li>응답 값은 저장 칸 길이에 맞게 코드 포인트 기준으로 자른다(오류 코드 · PG 상태 · 간편결제 제공자) — 길어서 DONE 기록이 실패하면 돈이 빠진 뒤 기록을 잃는다.
 *       거절 메시지 길이는 도메인이 자른다.</li>
 *   <li>로그에는 PG 주문번호 · HTTP status · 오류 코드 · 예외 타입만 남긴다. 시크릿 키 · Authorization · 결제키 · 오류 메시지 · 응답 본문은 남기지 않는다
 *       ({@code coding-conventions.md} §8). 오류 코드는 코드 모양일 때만 그대로 남기고 아니면 {@value #UNRECOGNIZED_CODE} 다. Feign 예외는 throwable 로 로그하지 않는다 —
 *       메시지에 응답 본문이, {@code request()} 에 인증 헤더가 들어 있다.</li>
 * </ul>
 */
@Slf4j
@Component
public class TossPaymentsResponseSupport {

    static final String CIRCUIT_BREAKER = "toss";
    static final String UNRECOGNIZED_CODE = "<unrecognized>";
    static final String STATUS_DONE = "DONE";

    private static final Set<String> ALREADY_PROCESSED_CODES = Set.of("ALREADY_PROCESSED_PAYMENT", "ALREADY_PROCESSING_REQUEST", "DUPLICATED_ORDER_ID");
    private static final Set<String> CONFIG_ERROR_CODES = Set.of("UNAUTHORIZED_KEY", "INVALID_API_KEY", "INCORRECT_BASIC_AUTH_FORMAT");
    /** 결제기관 처리 중 오류 — 거절로 확정하지 않는다. */
    private static final Set<String> PROVIDER_FAILURE_CODES = Set.of("PROVIDER_ERROR", "CARD_PROCESSING_ERROR");

    /**
     * 토스 결제 승인 오류표(2026-10-21 확인)의 "처리 전 거절" 코드 — 이 코드만 Rejected(FAILED) 로 확정한다.
     * 이미 처리됨 · 인증 · 결제기관 오류 · 5xx 코드는 위 집합이 따로 가른다.
     */
    static final Set<String> REJECTION_CODES = Set.of(
        // 400
        "EXCEED_MAX_CARD_INSTALLMENT_PLAN", "INVALID_REQUEST", "NOT_ALLOWED_POINT_USE", "INVALID_REJECT_CARD", "BELOW_MINIMUM_AMOUNT", "INVALID_CARD_EXPIRATION",
        "INVALID_STOPPED_CARD", "EXCEED_MAX_DAILY_PAYMENT_COUNT", "NOT_SUPPORTED_INSTALLMENT_PLAN_CARD_OR_MERCHANT", "INVALID_CARD_INSTALLMENT_PLAN",
        "NOT_SUPPORTED_MONTHLY_INSTALLMENT_PLAN", "EXCEED_MAX_PAYMENT_AMOUNT", "NOT_FOUND_TERMINAL_ID", "INVALID_AUTHORIZE_AUTH", "INVALID_CARD_LOST_OR_STOLEN",
        "RESTRICTED_TRANSFER_ACCOUNT", "INVALID_CARD_NUMBER", "INVALID_UNREGISTERED_SUBMALL", "NOT_REGISTERED_BUSINESS", "EXCEED_MAX_ONE_DAY_WITHDRAW_AMOUNT",
        "EXCEED_MAX_ONE_TIME_WITHDRAW_AMOUNT", "EXCEED_MAX_AMOUNT", "INVALID_ACCOUNT_INFO_RE_REGISTER", "NOT_AVAILABLE_PAYMENT", "UNAPPROVED_ORDER_ID",
        "EXCEED_MAX_MONTHLY_PAYMENT_AMOUNT",
        // 403
        "REJECT_ACCOUNT_PAYMENT", "REJECT_CARD_PAYMENT", "REJECT_CARD_COMPANY", "FORBIDDEN_REQUEST", "REJECT_TOSSPAY_INVALID_ACCOUNT", "EXCEED_MAX_AUTH_COUNT",
        "EXCEED_MAX_ONE_DAY_AMOUNT", "NOT_AVAILABLE_BANK", "INVALID_PASSWORD", "FDS_ERROR",
        // 404
        "NOT_FOUND_PAYMENT", "NOT_FOUND_PAYMENT_SESSION");

    private static final String NOT_FOUND_PAYMENT = "NOT_FOUND_PAYMENT";
    /** 조회의 연속 요청 제한 — 잠시 뒤 다시 물으면 된다. */
    private static final String FORBIDDEN_CONSECUTIVE_REQUEST = "FORBIDDEN_CONSECUTIVE_REQUEST";
    private static final Set<String> PENDING_STATUSES = Set.of("READY", "IN_PROGRESS");
    private static final Set<String> TERMINATED_STATUSES = Set.of("ABORTED", "EXPIRED");
    /** 승인 뒤 취소됨 · 가상계좌 입금 대기 — 승인 · 실패 어느 쪽으로도 이 흐름에서 확정하지 않는다. */
    private static final Set<String> MANUAL_REVIEW_STATUSES = Set.of("CANCELED", "PARTIAL_CANCELED", "WAITING_FOR_DEPOSIT");

    private static final Pattern CODE_SHAPE = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final int MAX_LOGGED_CODE_LENGTH = 60;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_CONFLICT = 409;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_NOT_FOUND = 404;
    /** 원인 체인을 따라가는 상한 — 순환 체인에서 멈춘다. */
    private static final int MAX_CAUSE_DEPTH = 16;

    private final CircuitBreaker circuitBreaker;
    private final JsonMapper jsonMapper;

    /** {@code jsonMapper} 는 서비스 컨텍스트의 빈(Boot 의 Jackson 3 자동설정)이다. */
    public TossPaymentsResponseSupport(CircuitBreakerRegistry circuitBreakerRegistry, JsonMapper jsonMapper) {
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER);
        this.jsonMapper = jsonMapper;
    }

    /**
     * 승인 요청을 서킷으로 감싸 부르고 결과를 가른다. 예외를 던지지 않는다.
     *
     * @param pgOrderId 요청한 PG 주문번호 — 응답의 주문번호와 대조하고 로그에 남긴다
     * @param remoteCall 승인 요청 (응답 본문 바이트)
     */
    public PgApprovalQueryResult approve(String pgOrderId, Supplier<byte[]> remoteCall) {
        return switch (exchange(remoteCall)) {
            case Exchange.Success success -> approvalOf(pgOrderId, success.body());
            case Exchange.ClientError clientError -> approvalOfClientError(pgOrderId, clientError);
            case Exchange.ServerError serverError -> {
                log.warn("toss confirm server error. pgOrderId={}, status={}", pgOrderId, serverError.status());
                yield new PgApprovalQueryResult.Unknown(PaymentUnknownReason.SERVER_ERROR);
            }
            case Exchange.CircuitOpen ignored -> {
                log.warn("toss confirm not sent: circuit open. pgOrderId={}, circuitBreaker={}", pgOrderId, CIRCUIT_BREAKER);
                yield new PgApprovalQueryResult.NotSent(NotSentReason.CIRCUIT_OPEN);
            }
            case Exchange.ConnectFailed connectFailed -> {
                log.warn("toss confirm not sent: connect failed. pgOrderId={}, type={}", pgOrderId, connectFailed.type());
                yield new PgApprovalQueryResult.NotSent(NotSentReason.CONNECT_FAILED);
            }
            case Exchange.Indeterminate indeterminate -> {
                log.warn("toss confirm result unknown. pgOrderId={}, status={}, type={}", pgOrderId, indeterminate.status(), indeterminate.type());
                yield new PgApprovalQueryResult.Unknown(indeterminate.reason());
            }
        };
    }

    /**
     * 조회를 서킷으로 감싸 부르고 결과를 가른다. 예외를 던지지 않는다.
     *
     * @param pgOrderId 결제 시도의 PG 주문번호 — 응답의 주문번호와 대조하고 로그에 남긴다
     * @param expectedPaymentKey 결제키 조회면 요청한 결제키 — 응답의 결제키와 대조한다. 주문번호 조회면 null
     * @param remoteCall 조회 요청 (응답 본문 바이트)
     */
    public PgInquiryQueryResult inquire(String pgOrderId, String expectedPaymentKey, Supplier<byte[]> remoteCall) {
        return switch (exchange(remoteCall)) {
            case Exchange.Success success -> inquiryOf(pgOrderId, expectedPaymentKey, success.body());
            case Exchange.ClientError clientError -> inquiryOfClientError(pgOrderId, clientError);
            case Exchange.ServerError serverError -> {
                log.warn("toss inquiry server error. pgOrderId={}, status={}", pgOrderId, serverError.status());
                yield new PgInquiryQueryResult.Unknown(PaymentUnknownReason.SERVER_ERROR);
            }
            case Exchange.CircuitOpen ignored -> {
                log.warn("toss inquiry not sent: circuit open. pgOrderId={}, circuitBreaker={}", pgOrderId, CIRCUIT_BREAKER);
                yield new PgInquiryQueryResult.Unknown(PaymentUnknownReason.SERVER_ERROR);
            }
            case Exchange.ConnectFailed connectFailed -> {
                log.warn("toss inquiry not sent: connect failed. pgOrderId={}, type={}", pgOrderId, connectFailed.type());
                yield new PgInquiryQueryResult.Unknown(PaymentUnknownReason.SERVER_ERROR);
            }
            case Exchange.Indeterminate indeterminate -> {
                log.warn("toss inquiry failed. pgOrderId={}, status={}, type={}", pgOrderId, indeterminate.status(), indeterminate.type());
                yield new PgInquiryQueryResult.Unknown(indeterminate.reason());
            }
        };
    }

    /** 토스 결제수단 표시명 → {@link PaymentMethod}. 모르는 값 · null 은 {@link PaymentMethod#OTHER} 다 — 승인 기록을 결제수단 때문에 잃지 않는다. */
    static PaymentMethod toPaymentMethod(String tossMethod) {
        if (tossMethod == null) {
            return PaymentMethod.OTHER;
        }
        return switch (tossMethod.strip()) {
            case "카드" -> PaymentMethod.CARD;
            case "간편결제" -> PaymentMethod.EASY_PAY;
            case "계좌이체" -> PaymentMethod.TRANSFER;
            case "가상계좌" -> PaymentMethod.VIRTUAL_ACCOUNT;
            case "휴대폰" -> PaymentMethod.MOBILE_PHONE;
            // 문화상품권 · 도서문화상품권 · 게임문화상품권
            case String giftCertificate when giftCertificate.endsWith("상품권") -> PaymentMethod.GIFT_CERTIFICATE;
            default -> PaymentMethod.OTHER;
        };
    }

    /** 코드 포인트 기준으로 {@code maxLength} 자까지 자른다. 비었거나 공백뿐이면 null — 빈 문자열을 값으로 저장하지 않는다. */
    static String normalize(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.codePointCount(0, stripped.length()) <= maxLength) {
            return stripped;
        }
        return stripped.substring(0, stripped.offsetByCodePoints(0, maxLength));
    }

    /** 원격이 보낸 오류 코드를 로그에 넣을 값으로 바꾼다. 코드 모양({@code [A-Z][A-Z0-9_]*}, 60자 이하)일 때만 그대로다 — CR/LF · 긴 값을 로그에 넣지 않는다. */
    static String loggableCode(String code) {
        if (code == null) {
            return null;
        }
        return code.length() <= MAX_LOGGED_CODE_LENGTH && CODE_SHAPE.matcher(code).matches() ? code : UNRECOGNIZED_CODE;
    }

    private Exchange exchange(Supplier<byte[]> remoteCall) {
        try {
            return new Exchange.Success(circuitBreaker.executeSupplier(remoteCall));
        } catch (CallNotPermittedException exception) {
            return new Exchange.CircuitOpen();
        } catch (RetryableException exception) {
            // I/O 오류(status -1)이거나 Retry-After 가 붙은 오류 응답(status 있음)이다. 재시도는 꺼져 있어 그대로 올라왔다
            if (exception.status() > 0) {
                return statusExchange(exception);
            }
            if (connectFailed(exception)) {
                return new Exchange.ConnectFailed(rootType(exception));
            }
            return new Exchange.Indeterminate(PaymentUnknownReason.TIMEOUT, exception.status(), rootType(exception));
        } catch (FeignException exception) {
            return statusExchange(exception);
        } catch (RuntimeException exception) {
            // 인코딩 · 디코딩 등 예상 밖 오류 — 보냈는지 가를 수 없다
            return new Exchange.Indeterminate(PaymentUnknownReason.CONTRACT, -1, exception.getClass().getSimpleName());
        }
    }

    private Exchange statusExchange(FeignException exception) {
        int status = exception.status();
        if (status >= 500 || status == HTTP_TOO_MANY_REQUESTS) {
            return new Exchange.ServerError(status);
        }
        if (status >= 400) {
            return new Exchange.ClientError(status, errorOf(exception));
        }
        // 3xx 등 — 토스 API 는 리다이렉트하지 않는다
        return new Exchange.Indeterminate(PaymentUnknownReason.CONTRACT, status, exception.getClass().getSimpleName());
    }

    private PgApprovalQueryResult approvalOf(String pgOrderId, byte[] body) {
        try {
            TossPaymentResponse response = jsonMapper.readValue(body, TossPaymentResponse.class);
            if (response == null || !STATUS_DONE.equals(response.status())) {
                log.error("toss confirm returned non-DONE payment. pgOrderId={}, pgStatus={}",
                    pgOrderId, response == null ? null : loggableCode(response.status()));
                return new PgApprovalQueryResult.Unknown(PaymentUnknownReason.CONTRACT);
            }
            PaymentApproval approval = toApproval(pgOrderId, response);
            log.info("toss confirm approved. pgOrderId={}, method={}", pgOrderId, approval.method());
            return new PgApprovalQueryResult.Approved(approval);
        } catch (RuntimeException exception) {
            // 해석 실패 전부(JSON · 필수 칸 누락 · approvedAt 형식 · 통화 · 금액 · 주문번호) — 승인 여부를 응답으로 판단할 수 없다. 예외를 밖으로 내지 않는다
            log.error("toss confirm response violates contract. pgOrderId={}, type={}", pgOrderId, exception.getClass().getSimpleName());
            return new PgApprovalQueryResult.Unknown(PaymentUnknownReason.CONTRACT);
        }
    }

    private PgApprovalQueryResult approvalOfClientError(String pgOrderId, Exchange.ClientError clientError) {
        int status = clientError.status();
        String code = clientError.code();
        if (status == HTTP_CONFLICT || (code != null && ALREADY_PROCESSED_CODES.contains(code))) {
            // 앞선 같은 멱등 키 요청이 처리 중이거나 이미 처리됐다 — 승인됐을 수 있다
            log.warn("toss confirm already processed. pgOrderId={}, status={}, code={}", pgOrderId, status, loggableCode(code));
            return new PgApprovalQueryResult.Unknown(PaymentUnknownReason.ALREADY_PROCESSED);
        }
        if (status == HTTP_UNAUTHORIZED || (code != null && CONFIG_ERROR_CODES.contains(code))) {
            log.error("toss rejected credentials. check TOSS_SECRET_KEY. pgOrderId={}, status={}, code={}", pgOrderId, status, loggableCode(code));
            return new PgApprovalQueryResult.NotSent(NotSentReason.CONFIG_ERROR);
        }
        if (code != null && PROVIDER_FAILURE_CODES.contains(code)) {
            log.warn("toss confirm provider error. pgOrderId={}, status={}, code={}", pgOrderId, status, code);
            return new PgApprovalQueryResult.Unknown(PaymentUnknownReason.SERVER_ERROR);
        }
        if (code == null || !REJECTION_CODES.contains(code)) {
            log.error("toss confirm client error is not recognized. pgOrderId={}, status={}, code={}", pgOrderId, status, loggableCode(code));
            return new PgApprovalQueryResult.Unknown(PaymentUnknownReason.CONTRACT);
        }
        log.info("toss confirm rejected. pgOrderId={}, status={}, code={}", pgOrderId, status, code);
        return new PgApprovalQueryResult.Rejected(normalize(code, Payment.FAILURE_CODE_MAX_LENGTH), clientError.message());
    }

    private PgInquiryQueryResult inquiryOf(String pgOrderId, String expectedPaymentKey, byte[] body) {
        try {
            TossPaymentResponse response = jsonMapper.readValue(body, TossPaymentResponse.class);
            if (response == null || !pgOrderId.equals(response.orderId())) {
                log.error("toss inquiry returned another order. pgOrderId={}", pgOrderId);
                return new PgInquiryQueryResult.Unknown(PaymentUnknownReason.CONTRACT);
            }
            String paymentKey = response.paymentKey();
            if (paymentKey == null || paymentKey.isBlank() || (expectedPaymentKey != null && !expectedPaymentKey.equals(paymentKey))) {
                log.error("toss inquiry returned missing or another payment key. pgOrderId={}", pgOrderId);
                return new PgInquiryQueryResult.Unknown(PaymentUnknownReason.CONTRACT);
            }
            String status = response.status() == null ? "" : response.status();
            if (STATUS_DONE.equals(status)) {
                return new PgInquiryQueryResult.Approved(toApproval(pgOrderId, response));
            }
            if (PENDING_STATUSES.contains(status)) {
                return new PgInquiryQueryResult.Pending(paymentKey);
            }
            if (TERMINATED_STATUSES.contains(status)) {
                TossPaymentResponse.Failure failure = response.failure();
                String failureCode = failure == null ? null : normalize(failure.code(), Payment.FAILURE_CODE_MAX_LENGTH);
                return failureCode == null
                    ? new PgInquiryQueryResult.Failed(paymentKey, status, null)
                    : new PgInquiryQueryResult.Failed(paymentKey, failureCode, failure.message());
            }
            if (MANUAL_REVIEW_STATUSES.contains(status)) {
                log.warn("toss inquiry status needs manual review. pgOrderId={}, pgStatus={}", pgOrderId, status);
                return new PgInquiryQueryResult.Unknown(PaymentUnknownReason.MANUAL_REVIEW);
            }
            log.error("toss inquiry status is not recognized. pgOrderId={}, pgStatus={}", pgOrderId, loggableCode(status));
            return new PgInquiryQueryResult.Unknown(PaymentUnknownReason.CONTRACT);
        } catch (RuntimeException exception) {
            // 해석 실패 전부(JSON · 필수 칸 누락 · approvedAt 형식 · 통화 · 금액) — 예외를 밖으로 내지 않는다
            log.error("toss inquiry response violates contract. pgOrderId={}, type={}", pgOrderId, exception.getClass().getSimpleName());
            return new PgInquiryQueryResult.Unknown(PaymentUnknownReason.CONTRACT);
        }
    }

    private PgInquiryQueryResult inquiryOfClientError(String pgOrderId, Exchange.ClientError clientError) {
        if (clientError.status() == HTTP_NOT_FOUND && NOT_FOUND_PAYMENT.equals(clientError.code())) {
            return new PgInquiryQueryResult.NotFound();
        }
        if (FORBIDDEN_CONSECUTIVE_REQUEST.equals(clientError.code())) {
            log.warn("toss inquiry throttled. pgOrderId={}, status={}, code={}", pgOrderId, clientError.status(), FORBIDDEN_CONSECUTIVE_REQUEST);
            return new PgInquiryQueryResult.Unknown(PaymentUnknownReason.SERVER_ERROR);
        }
        boolean configError = clientError.status() == HTTP_UNAUTHORIZED || (clientError.code() != null && CONFIG_ERROR_CODES.contains(clientError.code()));
        log.error("toss inquiry client error{}. pgOrderId={}, status={}, code={}",
            configError ? " (check TOSS_SECRET_KEY)" : "", pgOrderId, clientError.status(), loggableCode(clientError.code()));
        return new PgInquiryQueryResult.Unknown(PaymentUnknownReason.CONTRACT);
    }

    /**
     * 승인 상태(DONE) 응답 → 도메인 승인 결과. 필수 칸이 없거나 해석할 수 없으면 예외다 — 부른 쪽이 계약 위반(UNKNOWN)으로 돌린다.
     *
     * @throws RuntimeException 주문번호가 요청과 다르다 · 필수 칸이 없다 · 모르는 통화 · 금액이 통화의 소수 자릿수를 넘거나 음수다 · approvedAt 형식이 틀렸다
     */
    private static PaymentApproval toApproval(String pgOrderId, TossPaymentResponse response) {
        if (!pgOrderId.equals(response.orderId())) {
            throw new IllegalArgumentException("응답의 주문번호가 요청과 다릅니다");
        }
        // 결제키는 자르거나 다듬지 않는다 — 결제 시도의 결제키와 그대로 대조한다(길이 상한도 도메인이 본다)
        String paymentKey = Objects.requireNonNull(response.paymentKey(), "paymentKey");
        if (paymentKey.isBlank()) {
            throw new IllegalArgumentException("결제키가 비었습니다");
        }
        CurrencyCode currency = CurrencyCode.valueOf(Objects.requireNonNull(response.currency(), "currency"));
        Money amount = Money.ofMajorUnit(Objects.requireNonNull(response.totalAmount(), "totalAmount"), currency);
        Instant approvedAt = OffsetDateTime.parse(Objects.requireNonNull(response.approvedAt(), "approvedAt")).toInstant();
        String easyPayProvider = response.easyPay() == null ? null : normalize(response.easyPay().provider(), Payment.EASY_PAY_PROVIDER_MAX_LENGTH);
        return new PaymentApproval(paymentKey, toPaymentMethod(response.method()), easyPayProvider, approvedAt,
            normalize(response.status(), Payment.PG_STATUS_MAX_LENGTH), amount);
    }

    /** 오류 본문의 {@code {code, message}}. 본문이 없거나 JSON 이 아니면 null 이다. 토스 응답은 UTF-8 JSON 이다. */
    private TossErrorResponse errorOf(FeignException exception) {
        String body = exception.contentUTF8();
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return jsonMapper.readValue(body, TossErrorResponse.class);
        } catch (JacksonException parseFailure) {
            return null;
        }
    }

    /** 원인 체인에 연결 단계의 실패가 있다 — 요청 본문을 보내기 전이다. */
    private static boolean connectFailed(Throwable exception) {
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++, cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof UnknownHostException || cause instanceof NoRouteToHostException) {
                return true;
            }
        }
        return false;
    }

    /** 원인 체인에서 가장 안쪽 I/O 예외의 타입 이름 — 로그용. 메시지는 싣지 않는다. */
    private static String rootType(Throwable exception) {
        String type = exception.getClass().getSimpleName();
        Throwable cause = exception.getCause();
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++, cause = cause.getCause()) {
            if (cause instanceof IOException) {
                type = cause.getClass().getSimpleName();
            }
        }
        return type;
    }

    /** 원격 호출 한 번의 결과 — 승인 · 조회가 같은 분류를 쓰고 해석만 다르다. */
    private sealed interface Exchange {

        record Success(byte[] body) implements Exchange {
        }

        /** 4xx(429 제외). 본문을 읽지 못했으면 {@code error} 가 null 이다. */
        record ClientError(int status, TossErrorResponse error) implements Exchange {

            String code() {
                return error == null || error.code() == null || error.code().isBlank() ? null : error.code().strip();
            }

            String message() {
                return error == null ? null : error.message();
            }
        }

        /** 5xx · 429. */
        record ServerError(int status) implements Exchange {
        }

        record CircuitOpen() implements Exchange {
        }

        record ConnectFailed(String type) implements Exchange {
        }

        /** 보냈는지 · 처리됐는지 모른다. {@code status} 는 응답이 없으면 -1. */
        record Indeterminate(PaymentUnknownReason reason, int status, String type) implements Exchange {
        }
    }
}
