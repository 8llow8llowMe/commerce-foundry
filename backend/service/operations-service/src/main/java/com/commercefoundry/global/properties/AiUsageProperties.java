package com.commercefoundry.global.properties;

import com.commercefoundry.domainlayer.aiusage.domain.model.AiCost;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiEstimatePolicy;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiPrice;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiPriceTable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 사용량 원장 · 월 한도 설정 (#215 PR-B). 기본값은 {@code application.yml} 에 둔다 — 블록이 빠지거나 값이 틀리면 기동하지 않는다.
 *
 * <p>금액 · 단가는 문자열로 받아 {@link BigDecimal} 로 바꾼다 — {@code double} 바인딩의 이진 오차를 들이지 않는다 ({@code coding-conventions.md} §9-3).
 *
 * <p>월 한도({@code budget.monthly-usd})와 단가({@code pricing.models})는 비어 있어도 기동한다 — AI 가 꺼진 배포에서 값을 강요하지 않으려고.
 * AI 를 켰는데 비어 있으면 막는 가드는 ai-core 연결(PR-B3)이 {@code ai.enabled} 와 묶어 둔다. 비어 있는 채 예약하면 {@link IllegalStateException} 이다.
 *
 * @param budget             월 한도
 * @param estimate           보내기 전 예상 비용의 토큰 환산
 * @param pricing            모델별 단가
 * @param reservationSweep   결과가 오지 않은 예약의 회수
 * @param retention          호출 행 보존. 월 카운터 행은 지우지 않는다
 */
@ConfigurationProperties(prefix = "operations.ai")
public record AiUsageProperties(Budget budget, Estimate estimate, Pricing pricing, ReservationSweep reservationSweep, Retention retention) {

    public AiUsageProperties {
        Objects.requireNonNull(budget, "operations.ai.budget 설정이 없습니다.");
        Objects.requireNonNull(estimate, "operations.ai.estimate 설정이 없습니다.");
        Objects.requireNonNull(pricing, "operations.ai.pricing 설정이 없습니다.");
        Objects.requireNonNull(reservationSweep, "operations.ai.reservation-sweep 설정이 없습니다.");
        Objects.requireNonNull(retention, "operations.ai.retention 설정이 없습니다.");
    }

    /**
     * @param monthlyUsd      월 한도(USD, 소수 6자리까지). 비면 한도 없음이 아니라 "설정 안 됨" 이다 — 예약이 실패한다
     * @param zone            월 경계를 자르는 시간대
     * @param warnRatio       경고 임계 비율 (0 초과 1 미만) — 예약 누계가 한도 × 이 비율 이상이 되면 기간당 한 번 경고한다
     * @param notifyMemberIds 경고 · 초과 알림을 받을 어드민 (외부 FK: auth-service member.id). 비면 로그 · 지표만 남긴다
     */
    public record Budget(String monthlyUsd, ZoneId zone, String warnRatio, List<Long> notifyMemberIds) {

        public Budget {
            Objects.requireNonNull(zone, "operations.ai.budget.zone 설정이 없습니다.");
            if (monthlyUsd != null && monthlyUsd.isBlank()) {
                monthlyUsd = null;
            }
            if (monthlyUsd != null) {
                BigDecimal usd = decimal(monthlyUsd, "operations.ai.budget.monthly-usd");
                if (usd.signum() <= 0) {
                    throw new IllegalArgumentException("operations.ai.budget.monthly-usd 는 0 보다 커야 합니다: " + monthlyUsd);
                }
                micros(usd, "operations.ai.budget.monthly-usd");
            }
            BigDecimal ratio = decimal(warnRatio, "operations.ai.budget.warn-ratio");
            if (ratio.signum() <= 0 || ratio.compareTo(BigDecimal.ONE) >= 0) {
                throw new IllegalArgumentException("operations.ai.budget.warn-ratio 는 0 초과 1 미만이어야 합니다: " + warnRatio);
            }
            notifyMemberIds = notifyMemberIds == null ? List.of() : List.copyOf(notifyMemberIds);
            for (Long memberId : notifyMemberIds) {
                if (memberId == null || memberId <= 0) {
                    throw new IllegalArgumentException("operations.ai.budget.notify-member-ids 는 양수 아이디여야 합니다: " + memberId);
                }
            }
        }

        /** 월 한도 (micro-USD). 설정하지 않았으면 비어 있다. */
        public OptionalLong limitMicros() {
            return monthlyUsd == null ? OptionalLong.empty() : OptionalLong.of(micros(new BigDecimal(monthlyUsd.trim()), "operations.ai.budget.monthly-usd"));
        }

        /** 경고 임계 (micro-USD) — 한도 × 비율을 올림한다. 한도가 아주 작아도 0 이 되지 않는다. */
        public long warnThresholdMicros(long limitMicros) {
            return new BigDecimal(warnRatio.trim()).multiply(BigDecimal.valueOf(limitMicros)).setScale(0, RoundingMode.CEILING).longValueExact();
        }

        private static long micros(BigDecimal usd, String key) {
            try {
                return AiCost.usdToMicros(usd);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(key + " 는 소수 6자리 이하 · long 범위여야 합니다: " + usd, exception);
            }
        }
    }

    /**
     * @param charsPerToken  입력 글자 몇 개를 토큰 하나로 칠지 (1 이상)
     * @param tokensPerImage 이미지 한 장을 입력 토큰 몇 개로 칠지 (0 이상)
     */
    public record Estimate(int charsPerToken, int tokensPerImage) {

        public Estimate {
            toPolicy(charsPerToken, tokensPerImage);
        }

        public AiEstimatePolicy toPolicy() {
            return toPolicy(charsPerToken, tokensPerImage);
        }

        private static AiEstimatePolicy toPolicy(int charsPerToken, int tokensPerImage) {
            try {
                return new AiEstimatePolicy(charsPerToken, tokensPerImage);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("operations.ai.estimate 값이 올바르지 않습니다: " + exception.getMessage(), exception);
            }
        }
    }

    /**
     * @param version 단가표 버전(20자 이하) — 호출 행에 남는다. 단가가 있으면 필수
     * @param models  모델 ID → 단가(USD / 1M 토큰). 비어 있을 수 있다
     */
    public record Pricing(String version, Map<String, Price> models) {

        public static final int VERSION_MAX_LENGTH = 20;

        public Pricing {
            models = models == null ? Map.of() : Map.copyOf(models);
            if (version != null && version.isBlank()) {
                version = null;
            }
            if (!models.isEmpty() && version == null) {
                throw new IllegalArgumentException("operations.ai.pricing.models 가 있으면 operations.ai.pricing.version 이 있어야 합니다.");
            }
            if (version != null && version.length() > VERSION_MAX_LENGTH) {
                throw new IllegalArgumentException("operations.ai.pricing.version 은 " + VERSION_MAX_LENGTH + "자 이하여야 합니다: " + version);
            }
            for (Map.Entry<String, Price> entry : models.entrySet()) {
                if (entry.getKey().isBlank() || entry.getKey().length() > 64) {
                    throw new IllegalArgumentException("operations.ai.pricing.models 의 모델 ID 는 1~64자여야 합니다: " + entry.getKey());
                }
                Objects.requireNonNull(entry.getValue(), "operations.ai.pricing.models." + entry.getKey() + " 단가가 없습니다.");
            }
            toTable(version, models);
        }

        public AiPriceTable toTable() {
            return toTable(version, models);
        }

        private static AiPriceTable toTable(String version, Map<String, Price> models) {
            Map<String, AiPrice> prices = new LinkedHashMap<>();
            models.forEach((model, price) -> prices.put(model, price.toPrice("operations.ai.pricing.models." + model)));
            return new AiPriceTable(version, prices);
        }

        /** 단가 네 칸 — 전부 필수, 0 이상. 문자열로 적는다(예 {@code "3"}, {@code "0.30"}). */
        public record Price(String input, String output, String cacheWrite, String cacheRead) {

            AiPrice toPrice(String key) {
                return new AiPrice(
                    nonNegative(input, key + ".input"), nonNegative(output, key + ".output"),
                    nonNegative(cacheWrite, key + ".cache-write"), nonNegative(cacheRead, key + ".cache-read")
                );
            }

            private static BigDecimal nonNegative(String value, String key) {
                BigDecimal decimal = decimal(value, key);
                if (decimal.signum() < 0) {
                    throw new IllegalArgumentException(key + " 는 음수일 수 없습니다: " + value);
                }
                return decimal;
            }
        }
    }

    /**
     * @param staleAfter 예약 뒤 이 시간이 지나도 결과가 없으면 회수한다(ABANDONED, 예상치 유지). AI 호출 타임아웃 × 재시도보다 넉넉히 길게
     * @param batchSize  한 번에 회수하는 호출 수
     */
    public record ReservationSweep(Duration staleAfter, int batchSize) {

        public ReservationSweep {
            Objects.requireNonNull(staleAfter, "operations.ai.reservation-sweep.stale-after 설정이 없습니다.");
            if (staleAfter.isZero() || staleAfter.isNegative()) {
                throw new IllegalArgumentException("operations.ai.reservation-sweep.stale-after 는 0 보다 커야 합니다: " + staleAfter);
            }
            if (batchSize <= 0) {
                throw new IllegalArgumentException("operations.ai.reservation-sweep.batch-size 는 1 이상이어야 합니다: " + batchSize);
            }
        }
    }

    /**
     * @param days             호출 시각이 이 일수보다 오래된 호출 행을 지운다(월 카운터는 남는다)
     * @param batchSize        한 트랜잭션에서 지우는 행 수
     * @param maxBatchesPerRun 한 번의 정리에서 도는 배치 수 상한 — 남은 행은 다음 정리가 이어 간다
     */
    public record Retention(int days, int batchSize, int maxBatchesPerRun) {

        public Retention {
            if (days < 1 || batchSize < 1 || maxBatchesPerRun < 1) {
                throw new IllegalArgumentException("operations.ai.retention 의 days · batch-size · max-batches-per-run 은 1 이상이어야 합니다.");
            }
        }
    }

    private static BigDecimal decimal(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " 설정이 없습니다.");
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " 는 숫자여야 합니다: " + value, exception);
        }
    }
}
