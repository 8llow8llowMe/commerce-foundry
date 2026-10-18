package com.commercefoundry.domainlayer.cart.adapter.out.persistence;

import com.commercefoundry.domainlayer.cart.adapter.out.persistence.entity.CartItemCustomOptionEntity;
import com.commercefoundry.domainlayer.cart.adapter.out.persistence.entity.CartItemEntity;
import com.commercefoundry.domainlayer.cart.adapter.out.persistence.repository.CartItemCustomOptionRepository;
import com.commercefoundry.domainlayer.cart.adapter.out.persistence.repository.CartItemRepository;
import com.commercefoundry.domainlayer.cart.application.exception.CartErrorCode;
import com.commercefoundry.domainlayer.cart.application.exception.CartException;
import com.commercefoundry.domainlayer.cart.application.mapper.CartItemMapper;
import com.commercefoundry.domainlayer.cart.application.port.out.CartItemRepositoryPort;
import com.commercefoundry.domainlayer.cart.domain.model.CartItem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CartItemRepositoryAdapter implements CartItemRepositoryPort {

    /** 같은 줄(회원 · SKU · 입력 해시) 유니크 키 — {@code CartItemEntity} 의 {@code @UniqueConstraint} 이름과 같다. */
    static final String DUPLICATE_LINE_CONSTRAINT = "uk_cart_item_member_id_sku_id_custom_option_hash";

    private final CartItemRepository cartItemRepository;
    private final CartItemCustomOptionRepository cartItemCustomOptionRepository;
    private final CartItemMapper cartItemMapper;
    private final EntityManager entityManager;

    @Override
    public List<CartItem> findAllByMemberId(long memberId) {
        List<CartItemEntity> entities = cartItemRepository.findAllByMemberIdOrderByIdDesc(memberId);
        if (entities.isEmpty()) {
            return List.of();
        }
        Map<Long, List<CartItemCustomOptionEntity>> optionsByItemId = findOptions(entities.stream().map(CartItemEntity::getId).toList());
        return entities.stream().map(entity -> cartItemMapper.toDomain(entity, optionsByItemId.getOrDefault(entity.getId(), List.of()))).toList();
    }

    @Override
    public Optional<CartItem> findByIdAndMemberId(long cartItemId, long memberId) {
        return cartItemRepository.findByIdAndMemberId(cartItemId, memberId)
            .map(entity -> cartItemMapper.toDomain(entity, findOptions(List.of(entity.getId())).getOrDefault(entity.getId(), List.of())));
    }

    /**
     * 줄은 리포지토리로 저장한다 — 버전이 null 이면 insert, 아니면 merge(버전 대조 후 update). 입력은 새 줄만 넣는다.
     * 입력 행은 아이디를 미리 채운 새 행이라 {@code save}(merge)를 쓰면 행마다 select 가 먼저 나간다 — {@code persist} 로 바로 넣는다.
     *
     * <p>{@code CART_CONCURRENTLY_MODIFIED} 로 바꾸는 것은 두 경우뿐이다 — 낡은 버전(@Version)과 같은 줄 유니크 키
     * ({@value #DUPLICATE_LINE_CONSTRAINT}) 위반. 다른 제약 위반(check · 입력 유니크 키 등)은 버그라 그대로 던진다.
     * {@code persist} · {@code flush} 는 리포지토리 프록시를 거치지 않아 Spring 예외로 번역되지 않으므로 JPA 예외도 같은 기준으로 본다.
     */
    @Override
    public void saveAll(List<CartItem> cartItems) {
        if (cartItems.isEmpty()) {
            return;
        }
        try {
            cartItemRepository.saveAllAndFlush(cartItems.stream().map(cartItemMapper::toEntity).toList());
            cartItems.stream()
                .filter(CartItem::isNew)
                .flatMap(cartItem -> cartItemMapper.toCustomOptionEntities(cartItem).stream())
                .forEach(entityManager::persist);
            entityManager.flush();
        } catch (OptimisticLockingFailureException | OptimisticLockException exception) {
            throw concurrentlyModified(cartItems, exception);
        } catch (DataIntegrityViolationException | PersistenceException exception) {
            if (violates(exception, DUPLICATE_LINE_CONSTRAINT)) {
                throw concurrentlyModified(cartItems, exception);
            }
            throw exception;
        }
    }

    private static CartException concurrentlyModified(List<CartItem> cartItems, RuntimeException cause) {
        log.info("cart item concurrently modified. memberId={}, cartItemIds={}", cartItems.getFirst().memberId(), cartItems.stream().map(CartItem::id).toList());
        return new CartException(CartErrorCode.CART_CONCURRENTLY_MODIFIED, cause);
    }

    /** 원인 사슬에 Hibernate 제약 위반이 있고 그 제약 이름이 {@code constraintName} 인가 (DB 가 대소문자 · 따옴표를 붙여 돌려줘도 같게 본다). */
    static boolean violates(Throwable exception, String constraintName) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null
                && violation.getConstraintName().toLowerCase(Locale.ROOT).contains(constraintName)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void delete(long cartItemId) {
        cartItemCustomOptionRepository.deleteAllOfCartItem(cartItemId);
        cartItemRepository.deleteItem(cartItemId);
    }

    /** 줄 아이디 → 입력 (표시 순서). 한 번의 {@code in} 조회다. */
    private Map<Long, List<CartItemCustomOptionEntity>> findOptions(Collection<Long> cartItemIds) {
        return cartItemCustomOptionRepository.findAllByCartItemIdInOrderByCartItemIdAscDisplayOrderAsc(cartItemIds).stream()
            .collect(Collectors.groupingBy(CartItemCustomOptionEntity::getCartItemId));
    }
}
