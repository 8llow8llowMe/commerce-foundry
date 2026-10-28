package com.commercefoundry.domainlayer.onboarding.application.port.out;

import com.commercefoundry.domainlayer.onboarding.domain.model.TourProgress;
import java.util.List;
import java.util.Optional;

/**
 * 운영자별 투어 진행 기록 저장소 (#357). 쓰기는 바로 flush 한다 — 버전 충돌 · 첫 행 유니크 키 위반은
 * {@code OnboardingException(CONCURRENT_UPDATE)} 로 바꿔 올린다.
 */
public interface TourProgressRepositoryPort {

    List<TourProgress> findAllByMember(long memberId);

    Optional<TourProgress> find(long memberId, String guideKey);

    /** 새 행(버전 null)은 INSERT, 저장된 행은 버전 비교 merge 다. */
    TourProgress save(TourProgress progress);

    /** 재실행 — 그 운영자의 그 투어 기록을 지운다. 없으면 0. */
    int delete(long memberId, String guideKey);
}
