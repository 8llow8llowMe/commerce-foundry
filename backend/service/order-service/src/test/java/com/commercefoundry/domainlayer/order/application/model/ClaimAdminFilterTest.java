package com.commercefoundry.domainlayer.order.application.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.order.domain.model.Claim;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClaimAdminFilterTest {

    @Test
    @DisplayName("어드민 클레임 필터의 유형 · 상태 값은 도메인 enum 과 이름 집합이 같다 — 도메인에 값이 생기면 필터도 함께 연다")
    void filterEnumsMirrorDomain() {
        assertThat(Arrays.stream(ClaimAdminFilter.Type.values()).map(Enum::name))
            .containsExactlyElementsOf(Arrays.stream(Claim.Type.values()).map(Enum::name).toList());
        assertThat(Arrays.stream(ClaimAdminFilter.Status.values()).map(Enum::name))
            .containsExactlyElementsOf(Arrays.stream(Claim.Status.values()).map(Enum::name).toList());
        Arrays.stream(ClaimAdminFilter.Type.values()).forEach(type -> assertThat(type.toDomain().name()).isEqualTo(type.name()));
        Arrays.stream(ClaimAdminFilter.Status.values()).forEach(status -> assertThat(status.toDomain().name()).isEqualTo(status.name()));
    }
}
