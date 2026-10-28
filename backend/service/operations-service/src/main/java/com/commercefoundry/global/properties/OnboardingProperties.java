package com.commercefoundry.global.properties;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 운영자 온보딩 설정 (#357). 서버에 기록할 수 있는 안내 투어(키 · 배포 버전)의 허용 목록이다 — 목록에 없는 키는 저장하지 않는다(404).
 * 기본값은 {@code application.yml} 에 두고, 비었거나 키 형식 · 중복 · 버전이 틀리면 기동하지 않는다. 순서는 조회 응답 순서다.
 *
 * <p>키는 어드민 FE 투어 id({@code frontend/apps/admin/src/lib/tour/tours.ts})에서 {@code -v1} 같은 버전 꼬리를 뗀 것이다. 안내 내용을 바꿔
 * 다시 보여 줘야 하면 버전을 올린다 — 옛 버전 기록은 미시작으로 보인다.
 */
@ConfigurationProperties(prefix = "operations.onboarding")
public record OnboardingProperties(List<Guide> guides) {

    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");

    public OnboardingProperties {
        if (guides == null || guides.isEmpty()) {
            throw new IllegalArgumentException("operations.onboarding.guides 가 비어 있습니다.");
        }
        Set<String> keys = new HashSet<>();
        for (Guide guide : guides) {
            if (guide == null || guide.key() == null || !KEY_PATTERN.matcher(guide.key()).matches()) {
                throw new IllegalArgumentException("operations.onboarding.guides 의 key 는 ^[a-z][a-z0-9-]{0,63}$ 형식이어야 합니다: "
                    + (guide == null ? null : guide.key()));
            }
            if (!keys.add(guide.key())) {
                throw new IllegalArgumentException("operations.onboarding.guides 의 key 가 중복됩니다: " + guide.key());
            }
            if (guide.version() < 1) {
                throw new IllegalArgumentException("operations.onboarding.guides 의 version 은 1 이상이어야 합니다: " + guide.key());
            }
        }
        guides = List.copyOf(guides);
    }

    public Optional<Guide> find(String key) {
        return guides.stream().filter(guide -> guide.key().equals(key)).findFirst();
    }

    /**
     * @param key 안내 키 — FE 투어 id 에서 버전 꼬리를 뗀 것
     * @param version 지금 배포된 안내 버전 (1부터)
     */
    public record Guide(String key, int version) {
    }
}
