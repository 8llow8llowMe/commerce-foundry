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
 */
@ConfigurationProperties(prefix = "gateway.request-body")
public record GatewayRequestBodyProperties(
    @DefaultValue("2MB") DataSize maxSize,
    @DefaultValue("32MB") DataSize bufferingBudget,
    @DefaultValue("10s") Duration bodyReadTimeout
) {

    /** 길이를 모르는 본문은 상한까지 메모리에 모은다 — 버퍼 크기가 int 다. */
    private static final DataSize UPPER_BOUND = DataSize.ofMegabytes(100);

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
    }
}
