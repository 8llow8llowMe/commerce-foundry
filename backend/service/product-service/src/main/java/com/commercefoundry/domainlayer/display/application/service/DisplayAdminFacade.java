package com.commercefoundry.domainlayer.display.application.service;

import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.response.DisplaySectionAdminListResponse;
import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.response.DisplaySectionAdminResponse;
import com.commercefoundry.domainlayer.display.adapter.in.admin.presenter.DisplayAdminPresenter;
import com.commercefoundry.domainlayer.display.application.command.DisplaySectionUpsertCommand;
import com.commercefoundry.domainlayer.display.application.exception.DisplayErrorCode;
import com.commercefoundry.domainlayer.display.application.exception.DisplayException;
import com.commercefoundry.domainlayer.display.application.port.in.DisplayAdminUseCase;
import com.commercefoundry.domainlayer.display.application.service.processor.DisplaySectionProcessor;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySection;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionContent;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductLookupProcessor;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 메인 전시 관리. 상품 확인(존재 · 이름 · 노출)은 상품 컨텍스트 {@link ProductLookupProcessor} 로 한 번(in 절)에 받는다 — 숨긴 상품도 담는다.
 *
 * <p>쓰기는 외부 I/O 가 없어 Facade 에 트랜잭션을 건다. 저장소 없이 볼 수 있는 규칙(섹션 규칙 · 미디어 주소)은 잠금 전에 {@link DisplaySectionProcessor#draftOf} 로 보고,
 * 그다음 {@link DisplaySectionProcessor#lockAll()} 로 모든 섹션 행을 잠가 어드민 전시 쓰기를 직렬화한다. 잠금은 커밋까지 쥐므로, 판정 뒤 저장 전에 다른 쓰기가
 * 섹션을 바꾸거나 지우지 못한다. 잠금을 기다린 쓰기는 앞선 쓰기가 커밋한 뒤 <b>잠금 다음 문장으로 전체를 다시 읽어</b>(READ COMMITTED 의 새 스냅숏) 앞선 생성의 새 행까지
 * 상한 · 존재 · 순서(맨 뒤 = 최대 + 1) 판정에 넣는다 ({@code DisplaySectionRepositoryAdapter#findAllForUpdate}).
 * 버전 칸을 두지 않아 같은 섹션을 두 어드민이 고치면 마지막 쓰기가 이긴다(어드민 카테고리와 같다, {@code api-design-guide.md} §2-5).
 *
 * <p><b>잠금 대기 상한</b> — 쓰기 메서드는 트랜잭션 시간 상한({@value #LOCKING_TIMEOUT_SECONDS}초)을 건다. 잠금을 기다리는 {@code select ... for update} 도 그 안에 끊긴다
 * (드라이버가 문장을 취소 — {@link QueryTimeoutException}). 상한이 지난 뒤의 쿼리는 {@link TransactionTimedOutException} 이다. 둘 다 다른 어드민의 전시 쓰기와 겹친 것이라
 * 409 · {@code PRODUCT_058} 로 바꾼다 (선례: {@code CategoryAdminFacade}).
 *
 * <p><b>직렬화되지 않는 유일한 경우 — 섹션이 하나도 없을 때</b>다. 잠글 행이 없어 동시 생성 둘이 줄을 서지 않고, 결과는 노출 순서 0 이 둘(유니크 키 없음 — 아이디 순으로
 * 정렬되어 화면 순서는 정해진다)이다. 0개에서 출발하므로 상한(50)을 넘지는 않는다. 섹션이 하나라도 있으면 모든 쓰기가 그 행 잠금에서 줄을 선다.
 */
@Service
@RequiredArgsConstructor
public class DisplayAdminFacade implements DisplayAdminUseCase {

    /** 전시 잠금을 거는 트랜잭션의 시간 상한(초). 잠금은 짧은 쓰기 몇 문장만 쥐므로 정상 경로는 이 값에 한참 못 미친다. */
    static final int LOCKING_TIMEOUT_SECONDS = 5;

    private final DisplaySectionProcessor displaySectionProcessor;
    private final ProductLookupProcessor productLookupProcessor;
    private final DisplayAdminPresenter displayAdminPresenter;

    @Override
    @Transactional(readOnly = true)
    public DisplaySectionAdminListResponse getSections() {
        return toListResponse(displaySectionProcessor.getAllContents());
    }

    @Override
    @Transactional(readOnly = true)
    public DisplaySectionAdminResponse getSection(long sectionId) {
        DisplaySectionContent content = displaySectionProcessor.getContent(sectionId);
        Map<Long, Product> products = productLookupProcessor.findAllIncludingHidden(content.productIds());
        return displayAdminPresenter.toResponse(displaySectionProcessor.toAdminInfo(content, products));
    }

    @Override
    @Transactional(timeout = LOCKING_TIMEOUT_SECONDS)
    public DisplaySectionAdminResponse createSection(DisplaySectionUpsertCommand command) {
        DisplaySectionContent draft = displaySectionProcessor.draftOf(command);
        return inDisplayQueue(locked -> {
            displaySectionProcessor.validateCanCreate(locked);
            Map<Long, Product> products = productLookupProcessor.findAllIncludingHidden(draft.productIds());
            displaySectionProcessor.validateProductsExist(draft.productIds(), products);
            DisplaySectionContent created = displaySectionProcessor.create(draft, locked);
            return displayAdminPresenter.toResponse(displaySectionProcessor.toAdminInfo(created, products));
        });
    }

    @Override
    @Transactional(timeout = LOCKING_TIMEOUT_SECONDS)
    public DisplaySectionAdminResponse updateSection(long sectionId, DisplaySectionUpsertCommand command) {
        DisplaySectionContent draft = displaySectionProcessor.draftOf(command);
        return inDisplayQueue(locked -> {
            DisplaySection current = displaySectionProcessor.requireSection(locked, sectionId);
            Map<Long, Product> products = productLookupProcessor.findAllIncludingHidden(draft.productIds());
            displaySectionProcessor.validateProductsExist(draft.productIds(), products);
            DisplaySectionContent replaced = displaySectionProcessor.replace(current, draft);
            return displayAdminPresenter.toResponse(displaySectionProcessor.toAdminInfo(replaced, products));
        });
    }

    @Override
    @Transactional(timeout = LOCKING_TIMEOUT_SECONDS)
    public void deleteSection(long sectionId) {
        inDisplayQueue(locked -> {
            displaySectionProcessor.requireSection(locked, sectionId);
            displaySectionProcessor.delete(sectionId);
            return null;
        });
    }

    @Override
    @Transactional(timeout = LOCKING_TIMEOUT_SECONDS)
    public DisplaySectionAdminListResponse reorderSections(List<Long> sectionIds) {
        return inDisplayQueue(locked -> {
            displaySectionProcessor.reorder(locked, sectionIds);
            return toListResponse(displaySectionProcessor.getAllContents());
        });
    }

    private DisplaySectionAdminListResponse toListResponse(List<DisplaySectionContent> contents) {
        Map<Long, Product> products = productLookupProcessor.findAllIncludingHidden(displaySectionProcessor.productIdsOf(contents));
        return displayAdminPresenter.toListResponse(displaySectionProcessor.toAdminInfos(contents, products));
    }

    /**
     * 모든 섹션 행을 잠근 목록으로 판정 · 쓰기를 한다. 잠금 대기 · 작업 중 시간 상한을 넘기면 409 · {@code PRODUCT_058} 이다 — 트랜잭션은 예외로 롤백된다.
     * {@link PessimisticLockingFailureException}(잠금 획득 실패 · 교착 상태 희생)도 같은 뜻이라 같이 바꾼다.
     */
    private <T> T inDisplayQueue(Function<List<DisplaySection>, T> work) {
        try {
            return work.apply(displaySectionProcessor.lockAll());
        } catch (QueryTimeoutException | TransactionTimedOutException | PessimisticLockingFailureException exception) {
            throw new DisplayException(DisplayErrorCode.DISPLAY_BUSY, exception);
        }
    }
}
