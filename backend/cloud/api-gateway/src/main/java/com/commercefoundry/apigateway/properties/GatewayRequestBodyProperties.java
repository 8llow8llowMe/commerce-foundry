package com.commercefoundry.apigateway.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * 게이트웨이가 하류로 넘기는 요청 본문의 크기 상한 ({@code security-guide.md} §7-4). 넘으면 413 {@code GATEWAY_007} 로 끊고 하류로 보내지 않는다.
 *
 * @param maxSize         본문 상한. 기본 2MB — 가장 큰 정상 요청인 장바구니 병합(50줄 × 입력 20개 × 500자, 한글 UTF-8 약 1.5MB)이 들어가는 값.
 *                        업로드(#9)는 그 라우트에만 따로 상한을 둔다 — {@code Content-Type} 만 바꿔 상한을 올릴 수 없게 여기에 두지 않는다
 * @param bufferingBudget 길이 모르는(chunked) 본문을 동시에 모을 수 있는 총량. 기본 32MB(상한 2MB 면 동시 16개). 넘으면 411 로 끊는다 — 천천히 보내는 연결을 여러 개 열어
 *                        게이트웨이 메모리를 채우는 공격(slowloris)의 상한
 * @param bodyReadTimeout 길이 모르는 본문을 다 받기까지의 상한. 기본 10초. 넘으면 408 로 끊는다
 * @param discardMaxSize  상한을 넘은 본문을 413 전에 읽어 버리는(배출) 전체 본문 크기 상한. 기본 4MB, max-size 이상 100MB 이하. 읽지 않은 본문을 남긴 채 닫으면 커널이 RST 를 보내
 *                        클라이언트가 413 대신 연결 오류를 보므로(#145) 이 크기까지는 끝까지 받아 버린 뒤 닫는다. 넘으면 바로 끊는다 — 남용 쪽이라 연결 오류를 감수한다.
 *                        배출은 메모리를 잡지 않는다(받는 즉시 해제). Tomcat {@code maxSwallowSize}(기본 2MB)와 같은 역할이다
 * @param discardTimeout  배출에 쓰는 시간 상한. 기본 5초, 0 초과 60초 이하. 넘으면 그때 413 을 쓰고 끊는다 — 천천히 보내며 연결을 잡아 두는 쪽의 상한.
 *                        chunked 는 상한을 넘은 때부터 세고, 요청 전체에 걸린 {@code body-read-timeout} 이 먼저 끝나면 배출도 거기서 잘린다(그때도 413)
 */
@ConfigurationProperties(prefix = "gateway.request-body")
public record GatewayRequestBodyProperties(
    @DefaultValue("2MB") DataSize maxSize,
    @DefaultValue("32MB") DataSize bufferingBudget,
    @DefaultValue("10s") Duration bodyReadTimeout,
    @DefaultValue("4MB") DataSize discardMaxSize,
    @DefaultValue("5s") Duration discardTimeout
) {

    /** 길이를 모르는 본문은 상한까지 메모리에 모은다 — 버퍼 크기가 int 다. 배출 상한도 같은 값으로 묶는다(설정 실수로 큰 본문을 끝까지 받지 않게). */
    private static final DataSize UPPER_BOUND = DataSize.ofMegabytes(100);
    /** 배출 시간의 위쪽 상한 — 연결을 오래 잡아 두는 설정 실수를 기동에서 막는다. */
    private static final Duration DISCARD_TIMEOUT_UPPER_BOUND = Duration.ofSeconds(60);

    public GatewayRequestBodyProperties {
        if (maxSize == null || maxSize.toBytes() <= 0 || maxSize.compareTo(UPPER_BOUND) > 0) {
            throw new IllegalArgumentException("gateway.request-body.max-size 는 0 보다 크고 " + UPPER_BOUND + " 이하여야 합니다: " + maxSize);
        }
        if (bufferingBudget == null || bufferingBudget.compareTo(maxSize) < 0) {
            throw new IllegalArgumentException("gateway.request-body.buffering-budget 은 max-size 이상이어야 합니다: " + bufferingBudget);
        }
        if (bodyReadTimeout == null || bodyReadTimeout.isNegative() || bodyReadTimeout.isZero()) {
            throw new IllegalArgumentException("gateway.request-body.body-read-timeout 은 0 보다 커야 합니다: " + bodyReadTimeout);
        }
        if (discardMaxSize == null || discardMaxSize.compareTo(maxSize) < 0 || discardMaxSize.compareTo(UPPER_BOUND) > 0) {
            throw new IllegalArgumentException("gateway.request-body.discard-max-size 는 max-size 이상 " + UPPER_BOUND + " 이하여야 합니다: " + discardMaxSize);
        }
        if (discardTimeout == null || discardTimeout.isNegative() || discardTimeout.isZero() || discardTimeout.compareTo(DISCARD_TIMEOUT_UPPER_BOUND) > 0) {
            throw new IllegalArgumentException("gateway.request-body.discard-timeout 은 0 보다 크고 " + DISCARD_TIMEOUT_UPPER_BOUND + " 이하여야 합니다: " + discardTimeout);
        }
    }
}
