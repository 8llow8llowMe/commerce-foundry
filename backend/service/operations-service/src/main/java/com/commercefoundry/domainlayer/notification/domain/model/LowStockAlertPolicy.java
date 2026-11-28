package com.commercefoundry.domainlayer.notification.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * 품절 임박 정기 점검(#229)의 순수 판정. 원천 HTTP · 저장 · 알림 저장은 하지 않는다.
 *
 * <p>입력은 SKU 아이디와 시각뿐이다 — 상품명 · 수량 · 기준값은 받지 않는다. 시각은 PostgreSQL 정밀도(마이크로초)로 내려서 비교 · 저장한다.
 * 페이지 간 전역 스냅숏이 없으므로 완전 스캔(마지막 페이지 {@code hasNext=false})일 때만 부재를 판정하고, 처음 빠진 SKU 는 표시만 한 뒤
 * {@link Settings#resolveAfter()} 이상 연속으로 빠졌을 때 추적을 끝낸다 — 빈 페이지나 EOF 한 번으로 회복을 확정하지 않는다.
 */
public final class LowStockAlertPolicy {
    /** product {@code GET /internal/v1/stocks/low} 한 페이지 상한. */
    public static final int PAGE_SIZE = 100;
    /** 한 번의 점검이 읽는 페이지 수의 절대 상한. 설정값은 이 안에서 고른다. */
    public static final int MAX_PAGES_LIMIT = 40;
    /** 알림 수신자 상한. 요약 1건 × 수신자라 알림 저장 한 번(5,000)을 넘지 않는다. */
    public static final int MAX_RECIPIENTS = 1000;

    private LowStockAlertPolicy() {
    }

    /** 정기 점검 판정 설정. 스케줄 주기 · 활성화 게이트는 호출 쪽 설정이 갖는다. */
    public record Settings(int maxPages, Duration resolveAfter, Duration reminderInterval, Duration lockAtMost) {
        public Settings {
            Objects.requireNonNull(resolveAfter, "resolveAfter");
            Objects.requireNonNull(reminderInterval, "reminderInterval");
            Objects.requireNonNull(lockAtMost, "lockAtMost");
            if (maxPages < 1 || maxPages > MAX_PAGES_LIMIT) {
                throw new IllegalArgumentException("maxPages");
            }
            if (!resolveAfter.isPositive() || !reminderInterval.isPositive() || !lockAtMost.isPositive()) {
                throw new IllegalArgumentException("duration");
            }
        }

        /** 기본값 — 해소 판정 6시간(재고 선점 TTL 30분보다 길게), 재알림 7일, 점검 한 번의 상한 5분. */
        public static Settings defaults() {
            return new Settings(20, Duration.ofHours(6), Duration.ofDays(7), Duration.ofMinutes(5));
        }
    }

    /**
     * product 한 페이지에서 읽은 값. {@code afterSkuId} 는 이 페이지를 요청한 커서다.
     * SKU 아이디는 그 커서보다 큰 오름차순이며 다음 커서는 마지막 반환 SKU 다.
     */
    public record Page(long afterSkuId, Instant observedAt, List<Long> skuIds, boolean hasNext, Long nextAfterSkuId) {
        public Page {
            Objects.requireNonNull(observedAt, "observedAt");
            Objects.requireNonNull(skuIds, "skuIds");
            skuIds = List.copyOf(skuIds);
            if (afterSkuId < 0) {
                throw new IllegalArgumentException("afterSkuId");
            }
            if (skuIds.size() > PAGE_SIZE) {
                throw new IllegalArgumentException("page size");
            }
            requireAscending(skuIds, afterSkuId);
            if (hasNext ? skuIds.isEmpty() || !skuIds.getLast().equals(nextAfterSkuId) : nextAfterSkuId != null) {
                throw new IllegalArgumentException("cursor");
            }
        }
    }

    /** 첫 페이지 관측 시각과 이번 점검에서 본 SKU. {@code complete} 가 거짓이면 {@code maxPages} 에서 잘린 점검이다. */
    public record Scan(Instant observedAt, List<Long> skuIds, boolean complete) {
        public Scan {
            Objects.requireNonNull(observedAt, "observedAt");
            Objects.requireNonNull(skuIds, "skuIds");
            observedAt = micros(observedAt);
            skuIds = List.copyOf(skuIds);
            if (skuIds.size() > PAGE_SIZE * MAX_PAGES_LIMIT) {
                throw new IllegalArgumentException("scan size");
            }
            requireAscending(skuIds, 0);
        }

        /**
         * 커서 순서대로 받은 페이지를 묶는다. 첫 페이지는 커서 0 에서 시작하고, 뒤 페이지는 앞 페이지가 약속한 다음 커서로 요청한 것이어야 한다.
         * 어긋나면(건너뜀 · 되감기 · 끝난 뒤 이어짐) 계약 위반으로 점검 전체를 버린다.
         */
        public static Scan of(List<Page> pages) {
            Objects.requireNonNull(pages, "pages");
            if (pages.isEmpty() || pages.size() > MAX_PAGES_LIMIT) {
                throw new IllegalArgumentException("pages");
            }
            var ids = new ArrayList<Long>();
            long expected = 0;
            for (int index = 0; index < pages.size(); index++) {
                var page = Objects.requireNonNull(pages.get(index), "page");
                if (index > 0 && !pages.get(index - 1).hasNext()) {
                    throw new IllegalArgumentException("page after end");
                }
                if (page.afterSkuId() != expected) {
                    throw new IllegalArgumentException("cursor continuity");
                }
                ids.addAll(page.skuIds());
                if (page.hasNext()) {
                    expected = page.nextAfterSkuId();
                }
            }
            return new Scan(pages.getFirst().observedAt(), ids, !pages.getLast().hasNext());
        }
    }

    /** 점검 워터마크(단일 행). {@code lastAlertAt} 은 아직 알린 적 없으면 null 이다. */
    public record State(Instant lastObservedAt, Instant lastAcceptedLocalAt, Instant lastAlertAt) {
        public State {
            Objects.requireNonNull(lastObservedAt, "lastObservedAt");
            Objects.requireNonNull(lastAcceptedLocalAt, "lastAcceptedLocalAt");
            if (lastAlertAt != null && lastAlertAt.isAfter(lastAcceptedLocalAt)) {
                throw new IllegalArgumentException("lastAlertAt");
            }
        }
    }

    /** 이미 알린 SKU 의 추적 행. {@code missingSince} 는 완전 스캔에서 처음 빠진 로컬 시각이다. */
    public record Tracked(long skuId, Instant missingSince) {
        public Tracked {
            if (skuId <= 0) {
                throw new IllegalArgumentException("skuId");
            }
        }
    }

    public enum Rejection {
        /** 점검이 {@code lockAtMost} 보다 오래 걸렸다 — 다른 인스턴스가 이미 다음 점검을 시작했을 수 있다. */
        LOCK_EXPIRED,
        /** 첫 페이지 관측 시각이 저장된 워터마크보다 늦지 않다 — 늦게 끝난 이전 점검이거나 재실행이다. */
        STALE_OBSERVATION,
        /** 로컬 시계가 마지막 수락 시각보다 뒤로 갔다. */
        LOCAL_CLOCK_REGRESSED
    }

    public enum Notice {
        NONE(null),
        /** 새로 품절 임박이 된 SKU 가 있다. 건수는 새 SKU 수다. */
        NEW_LOW_STOCK(NotificationSeverity.WARNING),
        /** 새 SKU 는 없지만 품절 임박이 남아 있고 재알림 간격이 지났다. 건수는 이번 점검에서 본 SKU 수다. */
        STILL_LOW_STOCK(NotificationSeverity.INFO);

        private final NotificationSeverity severity;

        Notice(NotificationSeverity severity) {
            this.severity = severity;
        }

        public NotificationSeverity severity() {
            return severity;
        }
    }

    /** 추적 행 변경. 모든 목록은 SKU 아이디 오름차순이며 서로 겹치지 않는다. {@code missing} 은 {@code at} 으로 표시한다. */
    public record Changes(List<Long> inserted, List<Long> reappeared, List<Long> missing, List<Long> resolved, Instant at) {
        public Changes {
            inserted = List.copyOf(inserted);
            reappeared = List.copyOf(reappeared);
            missing = List.copyOf(missing);
            resolved = List.copyOf(resolved);
            Objects.requireNonNull(at, "at");
        }

        public boolean isEmpty() {
            return inserted.isEmpty() && reappeared.isEmpty() && missing.isEmpty() && resolved.isEmpty();
        }
    }

    /** 수락이면 {@code rejection} 이 null 이고 나머지가 채워진다. {@code count} 는 알림 문구의 건수다. */
    public record Decision(Rejection rejection, State state, Changes changes, Notice notice, int count, boolean complete) {
        static Decision rejected(Rejection rejection) {
            return new Decision(Objects.requireNonNull(rejection), null, null, Notice.NONE, 0, false);
        }

        public boolean accepted() {
            return rejection == null;
        }
    }

    /**
     * 잠긴 워터마크 · 추적 행과 이번 점검 결과로 다음 상태를 정한다.
     *
     * @param elapsed 점검 시작(락 획득)부터 저장 트랜잭션에서 워터마크를 잠근 뒤까지 같은 JVM 단조 시계로 잰 시간
     */
    public static Decision decide(State previous, List<Tracked> tracked, Scan scan, Instant now, Duration elapsed, Settings settings) {
        Objects.requireNonNull(tracked, "tracked");
        Objects.requireNonNull(scan, "scan");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(elapsed, "elapsed");
        Objects.requireNonNull(settings, "settings");
        if (elapsed.isNegative() || elapsed.compareTo(settings.lockAtMost()) > 0) {
            return Decision.rejected(Rejection.LOCK_EXPIRED);
        }
        var local = micros(now);
        if (previous != null && !scan.observedAt().isAfter(previous.lastObservedAt())) {
            return Decision.rejected(Rejection.STALE_OBSERVATION);
        }
        if (previous != null && local.isBefore(previous.lastAcceptedLocalAt())) {
            return Decision.rejected(Rejection.LOCAL_CLOCK_REGRESSED);
        }
        var known = new HashMap<Long, Tracked>();
        for (var row : tracked) {
            if (known.put(Objects.requireNonNull(row, "tracked row").skuId(), row) != null) {
                throw new IllegalArgumentException("duplicate tracked sku");
            }
        }
        var seen = new HashSet<>(scan.skuIds());
        var inserted = new ArrayList<Long>();
        var reappeared = new ArrayList<Long>();
        for (long skuId : scan.skuIds()) {
            var row = known.get(skuId);
            if (row == null) {
                inserted.add(skuId);
            } else if (row.missingSince() != null) {
                reappeared.add(skuId);
            }
        }
        var missing = new ArrayList<Long>();
        var resolved = new ArrayList<Long>();
        if (scan.complete()) {
            for (var row : tracked.stream().filter(row -> !seen.contains(row.skuId())).sorted((a, b) -> Long.compare(a.skuId(), b.skuId())).toList()) {
                if (row.missingSince() == null) {
                    missing.add(row.skuId());
                } else if (!local.isBefore(row.missingSince().plus(settings.resolveAfter()))) {
                    resolved.add(row.skuId());
                }
            }
        }
        var lastAlertAt = previous == null ? null : previous.lastAlertAt();
        Notice notice;
        int count;
        if (!inserted.isEmpty()) {
            notice = Notice.NEW_LOW_STOCK;
            count = inserted.size();
        } else if (!scan.skuIds().isEmpty() && (lastAlertAt == null || !local.isBefore(lastAlertAt.plus(settings.reminderInterval())))) {
            notice = Notice.STILL_LOW_STOCK;
            count = scan.skuIds().size();
        } else {
            notice = Notice.NONE;
            count = 0;
        }
        var state = new State(scan.observedAt(), local, notice == Notice.NONE ? lastAlertAt : local);
        return new Decision(null, state, new Changes(inserted, reappeared, missing, resolved, local), notice, count, scan.complete());
    }

    private static Instant micros(Instant value) {
        // PostgreSQL timestamptz 는 마이크로초다. 나노초를 거부하지 않고 내려서 저장 왕복 뒤에도 같은 값으로 비교한다.
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    private static void requireAscending(List<Long> ids, long after) {
        long previous = after;
        for (var id : ids) {
            if (id == null || id <= previous) {
                throw new IllegalArgumentException("sku order");
            }
            previous = id;
        }
    }
}
