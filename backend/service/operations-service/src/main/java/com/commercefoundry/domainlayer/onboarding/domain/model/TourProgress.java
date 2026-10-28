package com.commercefoundry.domainlayer.onboarding.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 운영자 한 명 · 투어 하나의 안내 진행 기록 (#357). 상태는 {@link #start} · {@link #merge} · {@link #replace} 로만 바뀐다 — 바뀌지 않으면 같은 인스턴스를 돌려준다(저장하지 않는다).
 *
 * <p>여러 기기 · 탭이 같은 기록을 보내도 결과가 하나로 모이게 병합한다. 같은 버전이면 순위({@code IN_PROGRESS < {COMPLETED, SKIPPED}})가 내려가지 않고,
 * 건너뜀 → 완료만 허용한다(완료 → 건너뜀은 그대로). 진행 중끼리는 단계가 큰 쪽이다. 요청 버전이 더 크면 새 버전 기록으로 교체한다.
 * 이 기록은 안내를 본 것뿐이다 — 실제 업무 완료는 담당 서비스의 사실로만 판정한다.
 *
 * @param id 행 아이디. 행이 없는 미시작이면 null
 * @param currentStep 마지막으로 본 단계 (0부터). 모르면 null
 * @param finishedAt 완료 · 건너뜀이 된 시각. 끝나지 않았으면 null
 * @param version 낙관적 락 버전. 저장 전이면 null
 */
public record TourProgress(
    Long id,
    long memberId,
    String guideKey,
    int guideVersion,
    TourStatus status,
    Integer currentStep,
    Instant finishedAt,
    Long version
) {

    public TourProgress {
        Objects.requireNonNull(guideKey, "guideKey");
        Objects.requireNonNull(status, "status");
        if (memberId <= 0 || guideVersion < 1 || (currentStep != null && currentStep < 0)) {
            throw new IllegalArgumentException("tour progress");
        }
        if ((id == null) != (status == TourStatus.NOT_STARTED)) {
            throw new IllegalArgumentException("미시작만 행 없이 둔다");
        }
        if ((finishedAt != null) != status.isFinished()) {
            throw new IllegalArgumentException("finishedAt 은 완료 · 건너뜀일 때만 있다");
        }
    }

    /** 행이 없는 미시작 — 응답 합성용. */
    public static TourProgress notStarted(long memberId, String guideKey, int guideVersion) {
        return new TourProgress(null, memberId, guideKey, guideVersion, TourStatus.NOT_STARTED, null, null, null);
    }

    /** 첫 기록. 미시작은 행으로 만들지 않는다. */
    public static TourProgress start(long id, long memberId, String guideKey, int guideVersion, TourStatus status, Integer currentStep, Instant now) {
        requireRecordable(status, now);
        return new TourProgress(id, memberId, guideKey, guideVersion, status, currentStep, status.isFinished() ? now : null, null);
    }

    public boolean stored() {
        return id != null;
    }

    /**
     * 저장된 기록에 요청을 합친다. 바뀌는 것이 없으면 같은 인스턴스다. 요청 버전이 저장 버전보다 작으면 합치지 않는다 — 호출자가 거절하거나({@code 020}) 교체한다({@link #replace}, 설정 롤백).
     */
    public TourProgress merge(int requestVersion, TourStatus requestStatus, Integer requestStep, Instant now) {
        requireRecordable(requestStatus, now);
        if (!stored()) {
            throw new IllegalStateException("저장된 기록에만 합친다");
        }
        if (requestVersion < guideVersion) {
            throw new IllegalArgumentException("요청 버전이 저장 버전보다 작다");
        }
        if (requestVersion > guideVersion) {
            // 새 가이드 버전 — 옛 기록은 의미가 없어 요청으로 교체한다
            return replace(requestVersion, requestStatus, requestStep, now);
        }
        if (requestStatus == TourStatus.IN_PROGRESS) {
            if (status.isFinished()) {
                return this;
            }
            Integer step = maxStep(currentStep, requestStep);
            return Objects.equals(step, currentStep) ? this : withState(status, step, finishedAt);
        }
        if (status == TourStatus.COMPLETED || status == requestStatus) {
            return this;
        }
        // 진행 중 → 완료 · 건너뜀, 건너뜀 → 완료. 끝나는 순간을 남긴다
        return withState(requestStatus, maxStep(currentStep, requestStep), now);
    }

    /**
     * 저장된 행을 요청 기록으로 통째로 바꾼다 — 아이디 · 낙관 락 버전은 이어 받아 같은 행을 버전 비교로 덮어쓴다. 버전 순서는 보지 않는다:
     * 새 가이드 버전({@link #merge})과, 설정 버전이 롤백으로 내려가 저장 버전이 더 큰 행(호출자가 판정 — 없는 행으로 본다)이 함께 쓴다.
     */
    public TourProgress replace(int requestVersion, TourStatus requestStatus, Integer requestStep, Instant now) {
        requireRecordable(requestStatus, now);
        if (!stored()) {
            throw new IllegalStateException("저장된 기록만 교체한다");
        }
        return new TourProgress(id, memberId, guideKey, requestVersion, requestStatus, requestStep, requestStatus.isFinished() ? now : null, version);
    }

    /** 저장 버전이 배포된 설정 버전과 다르면 새 안내라 미시작으로 보인다. */
    public TourProgress viewAt(int configuredVersion) {
        if (!stored() || guideVersion != configuredVersion) {
            return notStarted(memberId, guideKey, configuredVersion);
        }
        return this;
    }

    private TourProgress withState(TourStatus nextStatus, Integer nextStep, Instant nextFinishedAt) {
        return new TourProgress(id, memberId, guideKey, guideVersion, nextStatus, nextStep, nextFinishedAt, version);
    }

    private static void requireRecordable(TourStatus status, Instant now) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(now, "now");
        if (status == TourStatus.NOT_STARTED) {
            throw new IllegalArgumentException("미시작은 기록이 아니라 삭제다");
        }
    }

    private static Integer maxStep(Integer stored, Integer requested) {
        if (stored == null) {
            return requested;
        }
        if (requested == null) {
            return stored;
        }
        return Math.max(stored, requested);
    }
}
