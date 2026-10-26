package com.commercefoundry.domainlayer.member.adapter.in.internal.presenter;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.member.adapter.in.internal.dto.item.MemberDisplayNameInternalItem;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MemberDisplayNameInternalPresenterTest {

    private final MemberDisplayNameInternalPresenter presenter = new MemberDisplayNameInternalPresenter();

    @Test
    @DisplayName("요청 순서로 중복을 빼고, 없는 회원은 빼며, 이름이 없으면 null — 원문은 마스킹한다")
    void ordersAndMasks() {
        Map<Long, String> names = new HashMap<>();
        names.put(2L, "홍길동");
        names.put(1L, null);

        assertThat(presenter.toResponse(List.of(2L, 1L, 2L, 3L), names).members()).containsExactly(
            MemberDisplayNameInternalItem.builder().memberId("2").displayName("홍*동").build(),
            MemberDisplayNameInternalItem.builder().memberId("1").displayName(null).build()
        );
    }
}
