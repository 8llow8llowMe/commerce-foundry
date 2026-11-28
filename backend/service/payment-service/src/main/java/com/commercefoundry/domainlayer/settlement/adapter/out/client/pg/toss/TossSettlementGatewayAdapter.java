package com.commercefoundry.domainlayer.settlement.adapter.out.client.pg.toss;

import com.commercefoundry.domainlayer.settlement.adapter.out.client.pg.toss.dto.TossSettlementResponse;
import com.commercefoundry.domainlayer.settlement.application.exception.SettlementCollectionException;
import com.commercefoundry.domainlayer.settlement.application.exception.SettlementCollectionException.Failure;
import com.commercefoundry.domainlayer.settlement.application.port.out.SettlementGatewayPort;
import com.commercefoundry.domainlayer.settlement.domain.model.PgSettlementItem;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementPaymentMethod;
import com.commercefoundry.global.properties.TossPaymentsProperties;
import com.commercefoundry.shared.commerce.CurrencyCode;
import feign.FeignException;
import feign.RetryableException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 토스페이먼츠 정산 조회 어댑터 (#219, {@code payment-guide.md} §10). 매출일 하루를 {@value #PAGE_SIZE} 건씩 모든 페이지 읽어 정산 도메인 거래로 바꾼다 —
 * 토스 응답 타입은 이 패키지 밖으로 나가지 않는다.
 *
 * <ul>
 *   <li><b>429 만</b> 이 어댑터가 다시 부른다(GET 조회라 안전) — 최대 {@value #MAX_RATE_LIMIT_RETRIES}회, 1s → 2s → 4s, {@code Retry-After}(초)가 있으면 그 값
 *       (상한 30s). 5xx · 타임아웃 · 연결 실패 · 그 밖의 4xx 는 다시 부르지 않고 수집 실패다. Feign 단계 재시도는 끈다({@link TossSettlementsClientConfiguration}).</li>
 *   <li><b>서킷을 걸지 않는다.</b> 결제 승인용 {@code toss} 서킷을 같이 쓰면 정산 실패가 승인 서킷을 열어 결제를 막는다. 따로 둘 이유도 약하다 — 하루 몇 번 부르는
 *       배치 조회라 서킷이 열릴 만큼 호출이 쌓이지 않고, 실패는 수집 실패로 끝나 호출자(operations 작업 엔진)가 백오프로 다시 부른다.</li>
 *   <li>로그에 시크릿 · Authorization · 결제키 · 주문번호를 남기지 않는다 — 매출일 · 페이지 · 상태 코드 · 예외 타입만.</li>
 * </ul>
 */
@Slf4j
@Component
public class TossSettlementGatewayAdapter implements SettlementGatewayPort {

    /** 한 페이지 건수. 마지막 페이지는 받은 건수가 이보다 적은 페이지다. */
    static final int PAGE_SIZE = 5_000;
    /** 페이지 상한 — 응답이 계약과 달라 끝나지 않는 순회를 막는다(하루 100만 건). */
    static final int MAX_PAGES = 200;
    static final int MAX_RATE_LIMIT_RETRIES = 3;
    static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);
    static final String DATE_TYPE_SOLD_DATE = "soldDate";

    private static final int TOO_MANY_REQUESTS = 429;
    private static final String RETRY_AFTER = "Retry-After";

    private final TossSettlementsClient client;
    private final TossPaymentsProperties properties;
    private final JsonMapper jsonMapper;
    private final Sleeper sleeper;

    @Autowired
    public TossSettlementGatewayAdapter(TossSettlementsClient client, TossPaymentsProperties properties, JsonMapper jsonMapper) {
        this(client, properties, jsonMapper, Sleeper.THREAD);
    }

    /** 시험이 대기를 기록만 하는 {@link Sleeper} 를 넣는다. */
    TossSettlementGatewayAdapter(TossSettlementsClient client, TossPaymentsProperties properties, JsonMapper jsonMapper, Sleeper sleeper) {
        this.client = client;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.sleeper = sleeper;
    }

    @Override
    public List<PgSettlementItem> fetchSettlements(LocalDate soldDate) {
        Objects.requireNonNull(soldDate, "soldDate");
        Map<String, PgSettlementItem> byTransactionKey = new LinkedHashMap<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            List<PgSettlementItem> items = parsePage(fetchPage(soldDate, page), soldDate);
            for (PgSettlementItem item : items) {
                PgSettlementItem previous = byTransactionKey.putIfAbsent(item.transactionKey(), item);
                if (previous != null && !previous.equals(item)) {
                    log.error("toss settlement violates contract. soldDate={}, page={}, type=DuplicateTransaction", soldDate, page);
                    throw new SettlementCollectionException(Failure.CONTRACT_VIOLATION);
                }
            }
            if (items.size() < PAGE_SIZE) {
                return List.copyOf(byTransactionKey.values());
            }
        }
        log.error("toss settlement violates contract. soldDate={}, type=PageLimit", soldDate);
        throw new SettlementCollectionException(Failure.CONTRACT_VIOLATION);
    }

    private byte[] fetchPage(LocalDate soldDate, int page) {
        for (int retry = 0; ; retry++) {
            try {
                return client.getSettlements(properties.basicAuthorization(), soldDate, soldDate, DATE_TYPE_SOLD_DATE, page, PAGE_SIZE);
            } catch (FeignException exception) {
                if (exception.status() == TOO_MANY_REQUESTS && retry < MAX_RATE_LIMIT_RETRIES) {
                    Duration wait = backoff(retry, exception);
                    log.warn("toss settlement rate limited. soldDate={}, page={}, retry={}, waitMillis={}", soldDate, page, retry + 1, wait.toMillis());
                    sleep(wait);
                    continue;
                }
                throw classify(exception, soldDate, page);
            }
        }
    }

    /**
     * 응답 한 페이지를 정산 도메인 거래로 바꾼다 — 토스 응답 해석은 이 메서드 한 곳이다.
     *
     * <p>항목 칸은 토스 공식 Settlement 객체를 따른다 — 수수료 합 = Σ {@code fees[].supplyAmount}(없으면 0), 지급 금액 = {@code payOutAmount}(없으면 0).
     * 둘 다 저장 · 합계만 하고 판정에 쓰지 않는다. 필수 칸(거래키 · 결제키 · 통화 · 금액 · 매출일)이 없거나, 매출일이 요청한 날이 아니거나, 소수 자릿수가 통화를 넘으면
     * 계약 위반이다 — 부분 결과를 저장하지 않는다.
     */
    // 미확인 가정 — 운영 켜기 전 고객사 · 토스 확인
    // 1) 최상위 모양: JSON 배열(정산 항목 목록)이고 page 는 1부터다. 마지막 페이지 = 받은 건수 < size. 래퍼 객체({"data": [...]} 등)면 이 메서드만 바꾼다.
    // 2) 취소 거래 표기: 항목에 cancel 객체가 있거나 amount 가 음수면 취소 거래다. amount 가 양수로 와도 취소면 음수로 정규화한다(부호와 무관하게 |amount| 로 다룬다).
    List<PgSettlementItem> parsePage(byte[] body, LocalDate soldDate) {
        JsonNode root;
        try {
            root = body == null ? null : jsonMapper.readTree(body);
        } catch (JacksonException exception) {
            throw contractViolation(soldDate, "Json");
        }
        if (root == null || !root.isArray()) {
            throw contractViolation(soldDate, "Shape");
        }
        List<PgSettlementItem> items = new ArrayList<>(root.size());
        for (JsonNode node : root) {
            try {
                items.add(toItem(jsonMapper.treeToValue(node, TossSettlementResponse.class), soldDate));
            } catch (JacksonException | IllegalArgumentException | ArithmeticException | DateTimeException exception) {
                throw contractViolation(soldDate, "Fields");
            }
        }
        return items;
    }

    private static PgSettlementItem toItem(TossSettlementResponse raw, LocalDate soldDate) {
        if (raw == null) {
            throw new IllegalArgumentException("정산 항목이 null 입니다");
        }
        required(raw.transactionKey(), "transactionKey");
        required(raw.paymentKey(), "paymentKey");
        CurrencyCode currency = CurrencyCode.valueOf(required(raw.currency(), "currency"));
        LocalDate itemSoldDate = LocalDate.parse(required(raw.soldDate(), "soldDate"));
        if (!itemSoldDate.equals(soldDate)) {
            throw new IllegalArgumentException("요청한 매출일의 거래가 아닙니다");
        }
        long rawAmount = minorUnits(required(raw.amount(), "amount"), currency);
        boolean canceled = (raw.cancel() != null && raw.cancel().isObject()) || rawAmount < 0;
        long amount = canceled ? Math.negateExact(Math.absExact(rawAmount)) : rawAmount;
        long fee = 0;
        if (raw.fees() != null) {
            for (TossSettlementResponse.Fee item : raw.fees()) {
                if (item != null && item.supplyAmount() != null) {
                    fee = Math.addExact(fee, minorUnits(item.supplyAmount(), currency));
                }
            }
        }
        long payOut = raw.payOutAmount() == null ? 0L : minorUnits(raw.payOutAmount(), currency);
        LocalDate paidOutDate = raw.paidOutDate() == null ? null : LocalDate.parse(raw.paidOutDate());
        Instant approvedAt = raw.approvedAt() == null ? null : OffsetDateTime.parse(raw.approvedAt()).toInstant();
        return new PgSettlementItem(raw.transactionKey(), raw.paymentKey(), raw.orderId(), itemSoldDate, paidOutDate, methodOf(raw.method()),
            amount, fee, payOut, currency, canceled, approvedAt);
    }

    private static <T> T required(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " 칸이 없습니다");
        }
        return value;
    }

    /** 통화의 주 단위 숫자 → 최소 단위 정수. 음수를 받는다(취소 · 환급). 자릿수가 넘치면 반올림하지 않고 실패한다 — 조용히 깎으면 대사가 어긋난다. */
    private static long minorUnits(BigDecimal value, CurrencyCode currency) {
        return value.setScale(currency.getFractionDigits(), RoundingMode.UNNECESSARY).movePointRight(currency.getFractionDigits()).longValueExact();
    }

    /** 토스 결제수단 표시명 → 정산 결제수단. 모르는 값은 OTHER, 없으면 null. */
    private static SettlementPaymentMethod methodOf(String method) {
        if (method == null || method.isBlank()) {
            return null;
        }
        return switch (method) {
            case "카드" -> SettlementPaymentMethod.CARD;
            case "간편결제" -> SettlementPaymentMethod.EASY_PAY;
            case "계좌이체" -> SettlementPaymentMethod.TRANSFER;
            case "가상계좌" -> SettlementPaymentMethod.VIRTUAL_ACCOUNT;
            case "휴대폰" -> SettlementPaymentMethod.MOBILE_PHONE;
            case String giftCertificate when giftCertificate.endsWith("상품권") -> SettlementPaymentMethod.GIFT_CERTIFICATE;
            default -> SettlementPaymentMethod.OTHER;
        };
    }

    /** 1s → 2s → 4s. {@code Retry-After}(초)가 있으면 그 값(상한 30s). */
    static Duration backoff(int retry, FeignException exception) {
        Duration retryAfter = retryAfter(exception);
        return retryAfter != null ? retryAfter : Duration.ofSeconds(1L << retry);
    }

    /**
     * {@code Retry-After} 의 초 값. 없거나 숫자가 아니면(HTTP 날짜 형식 포함) null. Feign 기본 ErrorDecoder 는 이 헤더가 있으면 원래 예외를 원인으로 품은
     * {@link RetryableException} 을 던지므로 헤더는 원인 쪽에서 읽는다.
     */
    static Duration retryAfter(FeignException exception) {
        Map<String, Collection<String>> headers = exception instanceof RetryableException && exception.getCause() instanceof FeignException original
            ? original.responseHeaders() : exception.responseHeaders();
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, Collection<String>> header : headers.entrySet()) {
            if (!RETRY_AFTER.equalsIgnoreCase(header.getKey()) || header.getValue() == null || header.getValue().isEmpty()) {
                continue;
            }
            String value = header.getValue().iterator().next();
            if (value == null || !value.strip().matches("\\d{1,9}")) {
                return null;
            }
            Duration seconds = Duration.ofSeconds(Long.parseLong(value.strip()));
            return seconds.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : seconds;
        }
        return null;
    }

    private void sleep(Duration wait) {
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SettlementCollectionException(Failure.UNAVAILABLE);
        }
    }

    /** 원인 예외 · 응답 본문을 싣지 않는다 — Feign 메시지에 요청 URL · 본문이 들어 있다. */
    private static SettlementCollectionException classify(FeignException exception, LocalDate soldDate, int page) {
        int status = exception.status();
        if (status == TOO_MANY_REQUESTS) {
            log.warn("toss settlement rate limit exhausted. soldDate={}, page={}", soldDate, page);
            return new SettlementCollectionException(Failure.RATE_LIMITED);
        }
        if (status >= 400 && status < 500) {
            log.error("toss settlement rejected. soldDate={}, page={}, status={}", soldDate, page, status);
            return new SettlementCollectionException(Failure.REJECTED);
        }
        // 5xx · 타임아웃 · 연결 실패(IOException 은 status -1 의 RetryableException)
        log.warn("toss settlement unavailable. soldDate={}, page={}, status={}, type={}", soldDate, page, status, exception.getClass().getSimpleName());
        return new SettlementCollectionException(Failure.UNAVAILABLE);
    }

    private static SettlementCollectionException contractViolation(LocalDate soldDate, String type) {
        log.error("toss settlement violates contract. soldDate={}, type={}", soldDate, type);
        return new SettlementCollectionException(Failure.CONTRACT_VIOLATION);
    }

    /** 429 백오프 대기. 시험은 기록만 하는 구현을 넣는다. */
    @FunctionalInterface
    interface Sleeper {

        Sleeper THREAD = duration -> Thread.sleep(duration.toMillis());

        void sleep(Duration duration) throws InterruptedException;
    }
}
