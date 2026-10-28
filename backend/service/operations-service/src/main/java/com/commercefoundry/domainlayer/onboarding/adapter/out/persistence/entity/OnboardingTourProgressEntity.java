package com.commercefoundry.domainlayer.onboarding.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.onboarding.domain.model.TourProgress;
import com.commercefoundry.domainlayer.onboarding.domain.model.TourStatus;
import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 운영자 한 명 · 투어 하나의 안내 진행 기록 (#357). 미시작은 행으로 두지 않는다 — 행이 없으면 미시작이고, 재실행은 행을 지운다.
 * 상태는 도메인 {@code TourProgress} 병합 → 이 엔티티 → {@code saveAndFlush}(버전 비교 merge)로만 바꾼다.
 *
 * <p>유니크 키 {@code (member_id, guide_key)} 가 운영자 · 투어당 한 행으로 막고, 운영자 조회도 이 키의 선두 칸을 탄다 — 별도 인덱스를 두지 않는다.
 * 저장하는 값은 키 · 버전 · 상태 · 단계 · 시각뿐이다(임의 payload · 개인정보 없음). mapper 를 따로 두지 않고 정적 변환을 쓴다 — 칸이 도메인과 1:1 이다.
 *
 * <p>{@code TourStatus} 를 바꾸면 {@code onboarding_tour_progress_status_check} 와 {@code ck_onboarding_tour_progress_status} 를 둘 다 고친다.
 */
@Entity
@Table(
    name = "onboarding_tour_progress",
    comment = "운영자별 안내 투어 진행 기록",
    uniqueConstraints = @UniqueConstraint(name = "uk_onboarding_tour_progress_member_id_guide_key", columnNames = {"member_id", "guide_key"}),
    check = {
        @CheckConstraint(name = "ck_onboarding_tour_progress_status", constraint = "status in ('IN_PROGRESS','COMPLETED','SKIPPED')"),
        @CheckConstraint(name = "ck_onboarding_tour_progress_current_step", constraint = "current_step is null or current_step >= 0")
    }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class OnboardingTourProgressEntity extends BaseEntity {

    @Id
    @Column(name = "id", comment = "진행 기록 아이디 (Snowflake)")
    private Long id;

    @Column(name = "member_id", nullable = false, comment = "운영자 (외부 FK: auth-service member.id)")
    private Long memberId;

    @Column(name = "guide_key", nullable = false, length = 64, comment = "안내 키 — 설정 허용 목록의 key ([a-z][a-z0-9-]{0,63})")
    private String guideKey;

    @Column(name = "guide_version", nullable = false, comment = "기록한 안내 버전 (1부터). 설정 버전과 다르면 미시작으로 보인다")
    private int guideVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16, comment = "안내 상태 (IN_PROGRESS / COMPLETED / SKIPPED). 미시작은 행이 없다")
    private TourStatus status;

    @Column(name = "current_step", comment = "마지막으로 본 단계 (0부터). 모르면 null")
    private Integer currentStep;

    @Column(name = "finished_at", comment = "완료 · 건너뜀이 된 시각. 끝나지 않았으면 null")
    private Instant finishedAt;

    @Version
    @Column(name = "version", nullable = false, comment = "낙관적 락 버전")
    private Long version;

    public static OnboardingTourProgressEntity from(TourProgress progress) {
        return OnboardingTourProgressEntity.builder()
            .id(progress.id())
            .memberId(progress.memberId())
            .guideKey(progress.guideKey())
            .guideVersion(progress.guideVersion())
            .status(progress.status())
            .currentStep(progress.currentStep())
            .finishedAt(progress.finishedAt())
            .version(progress.version())
            .build();
    }

    public TourProgress toDomain() {
        return new TourProgress(id, memberId, guideKey, guideVersion, status, currentStep, finishedAt, version);
    }
}
