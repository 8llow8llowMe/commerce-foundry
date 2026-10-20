package com.commercefoundry.domainlayer.shippingaddress.adapter.out.persistence;

import com.commercefoundry.domainlayer.shippingaddress.adapter.out.persistence.entity.ShippingAddressEntity;
import com.commercefoundry.domainlayer.shippingaddress.adapter.out.persistence.repository.ShippingAddressRepository;
import com.commercefoundry.domainlayer.shippingaddress.application.exception.ShippingAddressErrorCode;
import com.commercefoundry.domainlayer.shippingaddress.application.exception.ShippingAddressException;
import com.commercefoundry.domainlayer.shippingaddress.application.mapper.ShippingAddressMapper;
import com.commercefoundry.domainlayer.shippingaddress.application.port.out.ShippingAddressRepositoryPort;
import com.commercefoundry.domainlayer.shippingaddress.domain.model.ShippingAddress;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ShippingAddressRepositoryAdapter implements ShippingAddressRepositoryPort {

    private final ShippingAddressRepository shippingAddressRepository;
    private final ShippingAddressMapper shippingAddressMapper;

    @Override
    public List<ShippingAddress> findAllByMemberId(long memberId) {
        return shippingAddressRepository.findAllByMemberIdOrderByDefaultFirst(memberId).stream().map(shippingAddressMapper::toDomain).toList();
    }

    @Override
    public Optional<ShippingAddress> findByIdAndMemberId(long addressId, long memberId) {
        return shippingAddressRepository.findByIdAndMemberId(addressId, memberId).map(shippingAddressMapper::toDomain);
    }

    @Override
    public Optional<ShippingAddress> findLatestByMemberId(long memberId) {
        return shippingAddressRepository.findFirstByMemberIdOrderByIdDesc(memberId).map(shippingAddressMapper::toDomain);
    }

    @Override
    public long countByMemberId(long memberId) {
        return shippingAddressRepository.countByMemberId(memberId);
    }

    /**
     * {@code saveAndFlush} 로 바로 내보낸다 — 커밋 때 터지면 예외가 Facade 밖(트랜잭션 프록시)에서 나 도메인 오류로 바꿀 자리가 없다.
     * 옛 버전 · 지워진 행에 대한 수정은 낙관적 락 실패로 나온다.
     */
    @Override
    public ShippingAddress save(ShippingAddress address) {
        try {
            return shippingAddressMapper.toDomain(shippingAddressRepository.saveAndFlush(shippingAddressMapper.toEntity(address)));
        } catch (OptimisticLockingFailureException exception) {
            throw new ShippingAddressException(ShippingAddressErrorCode.SHIPPING_ADDRESS_UPDATE_CONFLICT, exception);
        } catch (DataIntegrityViolationException exception) {
            throw translate(exception);
        }
    }

    /** 벌크 UPDATE 라 바로 나간다 — 같은 트랜잭션의 다음 쓰기(새 기본 지정)보다 먼저 DB 에 닿는다. */
    @Override
    public void clearDefault(long memberId) {
        shippingAddressRepository.clearDefault(memberId, Instant.now());
    }

    @Override
    public boolean markDefault(long addressId, long memberId) {
        try {
            return shippingAddressRepository.markDefault(addressId, memberId, Instant.now()) > 0;
        } catch (DataIntegrityViolationException exception) {
            throw translate(exception);
        }
    }

    @Override
    public boolean deleteByIdAndMemberId(long addressId, long memberId) {
        return shippingAddressRepository.deleteByIdAndMemberIdInBulk(addressId, memberId) > 0;
    }

    /** 기본 배송지 유니크 키 위반은 수정 충돌(AUTH_022)이다. 그 밖의 제약 위반은 호출 코드의 오류라 그대로 올린다. */
    private static RuntimeException translate(DataIntegrityViolationException exception) {
        if (violates(exception, ShippingAddressEntity.UK_DEFAULT_MEMBER_ID)) {
            return new ShippingAddressException(ShippingAddressErrorCode.SHIPPING_ADDRESS_UPDATE_CONFLICT, exception);
        }
        return exception;
    }

    /**
     * 원인 사슬의 Hibernate 제약 위반이 그 제약인가. 예외 메시지가 아니라 드라이버가 알려 준 제약 이름으로 본다.
     * PostgreSQL 은 이름 그대로, H2(컨텍스트 테스트)는 이름 뒤에 인덱스 접미사를 붙여 포함 여부로 비교한다.
     */
    private static boolean violates(DataIntegrityViolationException exception, String constraintName) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                return violation.getConstraintName().toLowerCase(Locale.ROOT).contains(constraintName);
            }
        }
        return false;
    }
}
