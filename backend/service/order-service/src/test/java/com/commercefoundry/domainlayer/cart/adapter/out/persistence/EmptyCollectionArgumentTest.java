package com.commercefoundry.domainlayer.cart.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.cart.adapter.out.persistence.repository.CartItemCustomOptionRepository;
import com.commercefoundry.domainlayer.cart.adapter.out.persistence.repository.CartItemRepository;
import com.commercefoundry.domainlayer.cart.application.mapper.CartItemMapper;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 빈 결과 · 빈 인자는 이어지는 쿼리를 보내지 않는다 — {@code in ()} 은 방언에 따라 문법 오류이고, 가도 결과는 늘 비어 있다.
 * 실제 질의 결과는 PostgreSQL 리포지토리 테스트({@code CartItemPersistenceAdapterTest})가 본다.
 */
class EmptyCollectionArgumentTest {

    @Test
    @DisplayName("줄이 없는 회원은 입력을 조회하지 않는다")
    void noItemsNoOptionQuery() {
        CartItemRepository itemRepository = mock(CartItemRepository.class);
        CartItemCustomOptionRepository optionRepository = mock(CartItemCustomOptionRepository.class);
        CartItemMapper mapper = mock(CartItemMapper.class);
        given(itemRepository.findAllByMemberIdOrderByIdDesc(1L)).willReturn(List.of());

        assertThat(new CartItemRepositoryAdapter(itemRepository, optionRepository, mapper, mock(EntityManager.class)).findAllByMemberId(1L)).isEmpty();
        verifyNoInteractions(optionRepository, mapper);
    }

    @Test
    @DisplayName("저장할 줄이 없으면 아무것도 보내지 않는다")
    void emptySave() {
        CartItemRepository itemRepository = mock(CartItemRepository.class);
        CartItemCustomOptionRepository optionRepository = mock(CartItemCustomOptionRepository.class);
        EntityManager entityManager = mock(EntityManager.class);

        new CartItemRepositoryAdapter(itemRepository, optionRepository, mock(CartItemMapper.class), entityManager).saveAll(List.of());
        verifyNoInteractions(itemRepository, optionRepository, entityManager);
    }
}
