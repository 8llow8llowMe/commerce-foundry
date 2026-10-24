package com.commercefoundry.domainlayer.display.adapter.out.persistence;

import com.commercefoundry.domainlayer.display.adapter.out.persistence.entity.DisplaySectionEntity;
import com.commercefoundry.domainlayer.display.adapter.out.persistence.repository.DisplayItemRepository;
import com.commercefoundry.domainlayer.display.adapter.out.persistence.repository.DisplaySectionRepository;
import com.commercefoundry.domainlayer.display.application.mapper.DisplaySectionMapper;
import com.commercefoundry.domainlayer.display.application.port.out.DisplaySectionRepositoryPort;
import com.commercefoundry.domainlayer.display.domain.model.DisplayItem;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySection;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionContent;
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
public class DisplaySectionRepositoryAdapter implements DisplaySectionRepositoryPort {

    private final DisplaySectionRepository displaySectionRepository;
    private final DisplayItemRepository displayItemRepository;
    private final DisplaySectionMapper displaySectionMapper;

    @Override
    public List<DisplaySection> findAll() {
        return displaySectionMapper.toDomains(displaySectionRepository.findAllByOrderByDisplayOrderAscIdAsc());
    }

    @Override
    public Optional<DisplaySection> findById(long sectionId) {
        return displaySectionRepository.findById(sectionId).map(displaySectionMapper::toDomain);
    }

    /**
     * 잠금과 읽기를 두 문장으로 나눈다. PostgreSQL READ COMMITTED 에서 잠금을 기다린 문장은 그 문장이 시작될 때의 스냅숏을 쓰므로, 기다리는 사이 다른 어드민이
     * 커밋한 새 섹션이 결과에 없다. 잠금을 얻은 뒤의 두 번째 문장은 새 스냅숏이라 그 행까지 읽는다 — 상한 판정이 막 생긴 섹션을 놓치지 않는다(어드민 카테고리와 같다).
     */
    @Override
    public List<DisplaySection> findAllForUpdate() {
        displaySectionRepository.findAllForUpdateByOrderByIdAsc();
        return displaySectionMapper.toDomains(displaySectionRepository.findAllByOrderByDisplayOrderAscIdAsc());
    }

    @Override
    public List<DisplayItem> findItemsBySectionIds(Collection<Long> sectionIds) {
        if (sectionIds.isEmpty()) {
            return List.of();
        }
        return displaySectionMapper.toItemDomains(displayItemRepository.findBySectionIdInOrderBySectionIdAscDisplayOrderAscIdAsc(sectionIds));
    }

    /** 두 엔티티 모두 {@code Persistable} 이라 새 행은 persist(INSERT) 로 간다 — 아이디가 겹치면 덮어쓰지 않고 PK 위반이다. */
    @Override
    public void insert(DisplaySectionContent content) {
        displaySectionRepository.save(displaySectionMapper.toEntity(content.section()));
        displayItemRepository.saveAll(content.items().stream().map(displaySectionMapper::toEntity).toList());
    }

    /** 섹션은 관리 엔티티를 고쳐 dirty checking 으로, 아이템은 bulk DELETE 뒤 새로 INSERT 한다 (아이템 수만큼 읽지 않는다). */
    @Override
    public void replace(DisplaySectionContent content) {
        DisplaySection section = content.section();
        DisplaySectionEntity entity = displaySectionRepository.findById(section.id())
            .orElseThrow(() -> new IllegalStateException("수정할 전시 섹션이 없습니다. sectionId=" + section.id()));
        entity.change(
            section.sectionType(), section.title(), section.subtitle(), section.body(),
            section.linkUrl(), section.linkLabel(), section.visible(), section.startAt(), section.endAt()
        );
        displayItemRepository.deleteAllBySectionIdInBulk(section.id());
        displayItemRepository.saveAll(content.items().stream().map(displaySectionMapper::toEntity).toList());
    }

    @Override
    public void delete(long sectionId) {
        displayItemRepository.deleteAllBySectionIdInBulk(sectionId);
        displaySectionRepository.deleteById(sectionId);
    }

    /** 잠금으로 이미 읽은 관리 엔티티를 한 번(in 절) 다시 받아 고친다 — 바뀐 행만 커밋 때 UPDATE 된다. */
    @Override
    public void changeDisplayOrders(Map<Long, Integer> displayOrders) {
        Map<Long, DisplaySectionEntity> entities = displaySectionRepository.findAllById(displayOrders.keySet()).stream()
            .collect(Collectors.toMap(DisplaySectionEntity::getId, Function.identity()));
        displayOrders.forEach((sectionId, displayOrder) -> {
            DisplaySectionEntity entity = entities.get(sectionId);
            if (entity == null) {
                throw new IllegalStateException("순서를 바꿀 전시 섹션이 없습니다. sectionId=" + sectionId);
            }
            entity.changeDisplayOrder(displayOrder);
        });
    }
}
