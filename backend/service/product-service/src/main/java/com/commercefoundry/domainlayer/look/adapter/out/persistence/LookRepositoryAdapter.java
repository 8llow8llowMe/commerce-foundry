package com.commercefoundry.domainlayer.look.adapter.out.persistence;

import com.commercefoundry.domainlayer.look.adapter.out.persistence.entity.LookEntity;
import com.commercefoundry.domainlayer.look.adapter.out.persistence.repository.LookItemRepository;
import com.commercefoundry.domainlayer.look.adapter.out.persistence.repository.LookRepository;
import com.commercefoundry.domainlayer.look.application.mapper.LookMapper;
import com.commercefoundry.domainlayer.look.application.port.out.LookRepositoryPort;
import com.commercefoundry.domainlayer.look.domain.model.Look;
import com.commercefoundry.domainlayer.look.domain.model.LookContent;
import com.commercefoundry.domainlayer.look.domain.model.LookImage;
import com.commercefoundry.domainlayer.look.domain.model.LookItem;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LookRepositoryAdapter implements LookRepositoryPort {

    private final LookRepository lookRepository;
    private final LookItemRepository lookItemRepository;
    private final LookMapper lookMapper;

    @Override
    public List<Look> findAll() {
        return lookMapper.toDomains(lookRepository.findAllByOrderByDisplayOrderAscIdAsc());
    }

    @Override
    public Optional<Look> findById(long lookId) {
        return lookRepository.findById(lookId).map(lookMapper::toDomain);
    }

    /**
     * 잠금과 읽기를 두 문장으로 나눈다. PostgreSQL READ COMMITTED 에서 잠금을 기다린 문장은 그 문장이 시작될 때의 스냅숏을 쓰므로, 기다리는 사이 다른 어드민이
     * 커밋한 새 룩이 결과에 없다. 잠금을 얻은 뒤의 두 번째 문장은 새 스냅숏이라 그 행까지 읽는다 — 상한 판정이 막 생긴 룩을 놓치지 않는다(메인 전시와 같다).
     */
    @Override
    public List<Look> findAllForUpdate() {
        lookRepository.findAllForUpdateByOrderByIdAsc();
        return lookMapper.toDomains(lookRepository.findAllByOrderByDisplayOrderAscIdAsc());
    }

    @Override
    public List<LookItem> findItemsByLookIds(Collection<Long> lookIds) {
        if (lookIds.isEmpty()) {
            return List.of();
        }
        return lookMapper.toItemDomains(lookItemRepository.findAllByLookIdInOrderByLookIdAscDisplayOrderAscIdAsc(lookIds));
    }

    /** 두 엔티티 모두 {@code Persistable} 이라 새 행은 persist(INSERT) 로 간다 — 아이디가 겹치면 덮어쓰지 않고 PK 위반이다. */
    @Override
    public void insert(LookContent content) {
        lookRepository.save(lookMapper.toEntity(content.look()));
        lookItemRepository.saveAll(content.items().stream().map(lookMapper::toEntity).toList());
    }

    /** 룩은 관리 엔티티를 고쳐 dirty checking 으로, 아이템은 bulk DELETE 뒤 새로 INSERT 한다 (아이템 수만큼 읽지 않는다). */
    @Override
    public void replace(LookContent content) {
        Look look = content.look();
        LookEntity entity = lookRepository.findById(look.id())
            .orElseThrow(() -> new IllegalStateException("수정할 룩이 없습니다. lookId=" + look.id()));
        LookImage image = look.image();
        entity.change(look.title(), look.description(), image.url(), image.width(), image.height(), image.altText(), look.visible());
        lookItemRepository.deleteAllByLookIdInBulk(look.id());
        lookItemRepository.saveAll(content.items().stream().map(lookMapper::toEntity).toList());
    }

    @Override
    public void delete(long lookId) {
        lookItemRepository.deleteAllByLookIdInBulk(lookId);
        lookRepository.deleteById(lookId);
    }

    /** 잠금으로 이미 읽은 관리 엔티티를 한 번(in 절) 다시 받아 고친다 — 바뀐 행만 커밋 때 UPDATE 된다. */
    @Override
    public void changeDisplayOrders(Map<Long, Integer> displayOrders) {
        Map<Long, LookEntity> entities = lookRepository.findAllById(displayOrders.keySet()).stream()
            .collect(Collectors.toMap(LookEntity::getId, Function.identity()));
        displayOrders.forEach((lookId, displayOrder) -> {
            LookEntity entity = entities.get(lookId);
            if (entity == null) {
                throw new IllegalStateException("순서를 바꿀 룩이 없습니다. lookId=" + lookId);
            }
            entity.changeDisplayOrder(displayOrder);
        });
    }
}
