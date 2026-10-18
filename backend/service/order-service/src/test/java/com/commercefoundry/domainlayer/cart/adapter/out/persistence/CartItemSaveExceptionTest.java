package com.commercefoundry.domainlayer.cart.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import com.commercefoundry.domainlayer.cart.adapter.out.persistence.repository.CartItemCustomOptionRepository;
import com.commercefoundry.domainlayer.cart.adapter.out.persistence.repository.CartItemRepository;
import com.commercefoundry.domainlayer.cart.application.exception.CartErrorCode;
import com.commercefoundry.domainlayer.cart.application.exception.CartException;
import com.commercefoundry.domainlayer.cart.application.mapper.CartItemMapperImpl;
import com.commercefoundry.domainlayer.cart.domain.model.CartItem;
import com.commercefoundry.domainlayer.cart.domain.model.CustomOptions;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.sql.SQLException;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * 저장 실패 중 무엇을 사용자 재시도 대상(409 {@code CART_CONCURRENTLY_MODIFIED})으로 바꾸는지. 실제 제약 위반은 리포지토리 테스트(PostgreSQL)가 본다.
 */
class CartItemSaveExceptionTest {

    private static final CartItem NEW_LINE = CartItem.create(1L, 10L, 100L, 1, CustomOptions.empty(), 99);

    private CartItemRepository itemRepository;
    private EntityManager entityManager;
    private CartItemRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        itemRepository = mock(CartItemRepository.class);
        entityManager = mock(EntityManager.class);
        adapter = new CartItemRepositoryAdapter(itemRepository, mock(CartItemCustomOptionRepository.class), new CartItemMapperImpl(), entityManager);
    }

    private static DataIntegrityViolationException violation(String constraintName) {
        return new DataIntegrityViolationException("x", new ConstraintViolationException("x", new SQLException("x"), constraintName));
    }

    private static void assertConcurrentlyModified(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CartException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(CartErrorCode.CART_CONCURRENTLY_MODIFIED));
    }

    @Test
    @DisplayName("같은 줄 유니크 키 위반은 CART_CONCURRENTLY_MODIFIED 다 — DB 가 대문자 · 스키마를 붙여 돌려줘도 같다")
    void duplicateLine() {
        willThrow(violation("uk_cart_item_member_id_sku_id_custom_option_hash")).given(itemRepository).saveAllAndFlush(anyList());
        assertConcurrentlyModified(() -> adapter.saveAll(List.of(NEW_LINE)));

        willThrow(violation("ORDERS.UK_CART_ITEM_MEMBER_ID_SKU_ID_CUSTOM_OPTION_HASH")).given(itemRepository).saveAllAndFlush(anyList());
        assertConcurrentlyModified(() -> adapter.saveAll(List.of(NEW_LINE)));
    }

    @Test
    @DisplayName("다른 제약 위반(check 등) · 이름 없는 위반은 바꾸지 않고 그대로 던진다 — 버그를 409 로 숨기지 않는다")
    void otherConstraintIsRethrown() {
        DataIntegrityViolationException check = violation("ck_cart_item_quantity_positive");
        willThrow(check).given(itemRepository).saveAllAndFlush(anyList());
        assertThatThrownBy(() -> adapter.saveAll(List.of(NEW_LINE))).isSameAs(check);

        DataIntegrityViolationException unnamed = new DataIntegrityViolationException("x");
        willThrow(unnamed).given(itemRepository).saveAllAndFlush(anyList());
        assertThatThrownBy(() -> adapter.saveAll(List.of(NEW_LINE))).isSameAs(unnamed);
    }

    @Test
    @DisplayName("낡은 버전 저장은 CART_CONCURRENTLY_MODIFIED 다")
    void staleVersion() {
        willThrow(new ObjectOptimisticLockingFailureException("CartItemEntity", 1L)).given(itemRepository).saveAllAndFlush(anyList());

        assertConcurrentlyModified(() -> adapter.saveAll(List.of(NEW_LINE)));
    }

    @Test
    @DisplayName("입력 행 flush 의 JPA 예외도 같은 기준이다 — 같은 줄 유니크 키면 409, 아니면 그대로")
    void customOptionFlush() {
        willThrow(new PersistenceException("x", new ConstraintViolationException("x", new SQLException("x"), "uk_cart_item_member_id_sku_id_custom_option_hash")))
            .given(entityManager).flush();
        assertConcurrentlyModified(() -> adapter.saveAll(List.of(NEW_LINE)));

        PersistenceException other = new PersistenceException("x",
            new ConstraintViolationException("x", new SQLException("x"), "uk_cart_item_custom_option_cart_item_id_field_key"));
        willThrow(other).given(entityManager).flush();
        assertThatThrownBy(() -> adapter.saveAll(List.of(NEW_LINE))).isSameAs(other);
    }
}
