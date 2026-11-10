package com.commercefoundry.domainlayer.look.application.service;

import com.commercefoundry.domainlayer.adminaudit.application.service.processor.ProductAdminAuditProcessor;
import com.commercefoundry.domainlayer.look.adapter.in.admin.dto.response.LookAdminListResponse;
import com.commercefoundry.domainlayer.look.adapter.in.admin.dto.response.LookAdminResponse;
import com.commercefoundry.domainlayer.look.adapter.in.admin.presenter.LookAdminPresenter;
import com.commercefoundry.domainlayer.look.application.command.LookUpsertCommand;
import com.commercefoundry.domainlayer.look.application.exception.LookErrorCode;
import com.commercefoundry.domainlayer.look.application.exception.LookException;
import com.commercefoundry.domainlayer.look.application.port.in.LookAdminUseCase;
import com.commercefoundry.domainlayer.look.application.service.processor.LookProcessor;
import com.commercefoundry.domainlayer.look.domain.model.Look;
import com.commercefoundry.domainlayer.look.domain.model.LookContent;
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
 * 어드민 룩 관리. 상품 확인(존재 · 이름 · 노출 · 판매 상태)은 상품 컨텍스트 {@link ProductLookupProcessor} 로 한 번(in 절)에 받는다 — 숨김 · 판매 종료 상품도 담는다.
 *
 * <p>쓰기는 외부 I/O 가 없어 Facade 에 트랜잭션을 건다. 저장소 없이 볼 수 있는 규칙(상품 중복 · 이미지 주소)은 잠금 전에 {@link LookProcessor#draftOf} 로 보고,
 * 그다음 {@link LookProcessor#lockAll()} 로 모든 룩 행을 잠가 어드민 룩 쓰기를 직렬화한다. 잠금은 커밋까지 쥐므로 판정 뒤 저장 전에 다른 쓰기가 룩을 바꾸거나 지우지 못한다.
 * 잠금을 기다린 쓰기는 잠금 다음 문장으로 전체를 다시 읽어 앞선 생성의 새 행까지 상한 · 존재 · 순서 판정에 넣는다 ({@code LookRepositoryAdapter#findAllForUpdate}).
 * 버전 칸을 두지 않아 같은 룩을 두 어드민이 고치면 마지막 쓰기가 이긴다(메인 전시와 같다).
 *
 * <p><b>잠금 대기 상한</b> — 잠금 문장에 대기 상한 힌트({@code LookRepository#LOCK_TIMEOUT_MILLIS})가 있고, 쓰기 트랜잭션에 시간 상한({@value #LOCKING_TIMEOUT_SECONDS}초)이 있다.
 * 잠금 획득 실패({@link PessimisticLockingFailureException}) · 문장 취소({@link QueryTimeoutException}) · 상한 뒤 쿼리({@link TransactionTimedOutException})는 모두
 * 다른 어드민의 룩 쓰기와 겹친 것이라 409 · {@code LOOK_008} 로 바꾼다 (선례: {@code DisplayAdminFacade}).
 *
 * <p><b>직렬화되지 않는 유일한 경우 — 룩이 하나도 없을 때</b>다. 잠글 행이 없어 동시 생성 둘이 줄을 서지 않고, 결과는 노출 순서 0 이 둘이다(유니크 키 없음 — 아이디 순으로 정렬된다).
 * 0개에서 출발하므로 상한(100)을 넘지는 않는다.
 *
 * <p><b>감사 (#163 PR-C)</b> — 쓰기는 성공 · 실패를 {@link ProductAdminAuditProcessor} 로 남긴다(선례: {@code CategoryAdminFacade}). 트랜잭션 안에서 불러도 줄은
 * 커밋 결과로 남는다(롤백되면 성공으로 부른 것도 {@code FAILURE}). 잠금 전 규칙 판정({@code draftOf})도 업무 실패라 같이 기록한다. 업무 예외는 기록한 뒤 그대로 다시 던진다.
 * 룩 내용(제목 · 설명 · 이미지 · 구성 상품)과 순서 변경의 아이디 목록은 싣지 않는다.
 */
@Service
@RequiredArgsConstructor
public class LookAdminFacade implements LookAdminUseCase {

    /** 룩 잠금을 거는 트랜잭션의 시간 상한(초). 잠금은 짧은 쓰기 몇 문장만 쥐므로 정상 경로는 이 값에 한참 못 미친다. */
    static final int LOCKING_TIMEOUT_SECONDS = 5;

    private final LookProcessor lookProcessor;
    private final ProductLookupProcessor productLookupProcessor;
    private final LookAdminPresenter lookAdminPresenter;
    private final ProductAdminAuditProcessor productAdminAuditProcessor;

    @Override
    @Transactional(readOnly = true)
    public LookAdminListResponse getLooks() {
        return toListResponse(lookProcessor.getAllContents());
    }

    @Override
    @Transactional(readOnly = true)
    public LookAdminResponse getLook(long lookId) {
        LookContent content = lookProcessor.getContent(lookId);
        Map<Long, Product> products = productLookupProcessor.findAllIncludingHidden(content.productIds());
        return lookAdminPresenter.toResponse(lookProcessor.toAdminInfo(content, products));
    }

    @Override
    @Transactional(timeout = LOCKING_TIMEOUT_SECONDS)
    public LookAdminResponse create(LookUpsertCommand command, long actorMemberId) {
        Created created;
        try {
            LookContent draft = lookProcessor.draftOf(command);
            created = inLookQueue(locked -> {
                lookProcessor.validateCanCreate(locked);
                Map<Long, Product> products = productLookupProcessor.findAllIncludingHidden(draft.productIds());
                lookProcessor.validateProductsExist(draft.productIds(), products);
                return new Created(lookProcessor.create(draft, locked), products);
            });
        } catch (RuntimeException exception) {
            productAdminAuditProcessor.lookCreated(actorMemberId, null, false);
            throw exception;
        }
        productAdminAuditProcessor.lookCreated(actorMemberId, created.content().look().id(), true);
        return lookAdminPresenter.toResponse(lookProcessor.toAdminInfo(created.content(), created.products()));
    }

    @Override
    @Transactional(timeout = LOCKING_TIMEOUT_SECONDS)
    public LookAdminResponse update(long lookId, LookUpsertCommand command, long actorMemberId) {
        LookAdminResponse response;
        try {
            LookContent draft = lookProcessor.draftOf(command);
            response = inLookQueue(locked -> {
                Look current = lookProcessor.requireLook(locked, lookId);
                Map<Long, Product> products = productLookupProcessor.findAllIncludingHidden(draft.productIds());
                lookProcessor.validateProductsExist(draft.productIds(), products);
                LookContent replaced = lookProcessor.replace(current, draft);
                return lookAdminPresenter.toResponse(lookProcessor.toAdminInfo(replaced, products));
            });
        } catch (RuntimeException exception) {
            productAdminAuditProcessor.lookUpdated(actorMemberId, lookId, false);
            throw exception;
        }
        productAdminAuditProcessor.lookUpdated(actorMemberId, lookId, true);
        return response;
    }

    @Override
    @Transactional(timeout = LOCKING_TIMEOUT_SECONDS)
    public void delete(long lookId, long actorMemberId) {
        try {
            inLookQueue(locked -> {
                lookProcessor.requireLook(locked, lookId);
                lookProcessor.delete(lookId);
                return null;
            });
        } catch (RuntimeException exception) {
            productAdminAuditProcessor.lookDeleted(actorMemberId, lookId, false);
            throw exception;
        }
        productAdminAuditProcessor.lookDeleted(actorMemberId, lookId, true);
    }

    @Override
    @Transactional(timeout = LOCKING_TIMEOUT_SECONDS)
    public LookAdminListResponse reorder(List<Long> lookIds, long actorMemberId) {
        LookAdminListResponse response;
        try {
            response = inLookQueue(locked -> {
                lookProcessor.reorder(locked, lookIds);
                return toListResponse(lookProcessor.getAllContents());
            });
        } catch (RuntimeException exception) {
            productAdminAuditProcessor.looksReordered(actorMemberId, false);
            throw exception;
        }
        productAdminAuditProcessor.looksReordered(actorMemberId, true);
        return response;
    }

    /**
     * 생성 결과 — 감사에 룩 아이디를 넘긴 뒤 같은 상품 조회 결과로 응답을 만든다(상품을 다시 읽지 않는다).
     * 응답 조립은 잠금 큐({@code inLookQueue}) 밖이라 DB 를 읽지 않는 순수 매핑이어야 한다 — 조회를 더하면 잠금 시간 초과가 {@code LOOK_BUSY} 로 바뀌지 않는다.
     */
    private record Created(LookContent content, Map<Long, Product> products) {
    }

    private LookAdminListResponse toListResponse(List<LookContent> contents) {
        Map<Long, Product> products = productLookupProcessor.findAllIncludingHidden(lookProcessor.productIdsOf(contents));
        return lookAdminPresenter.toListResponse(lookProcessor.toAdminInfos(contents, products));
    }

    /** 모든 룩 행을 잠근 목록으로 판정 · 쓰기를 한다. 잠금 실패 · 시간 상한 초과는 409 · {@code LOOK_008} 이다 — 트랜잭션은 예외로 롤백된다. */
    private <T> T inLookQueue(Function<List<Look>, T> work) {
        try {
            return work.apply(lookProcessor.lockAll());
        } catch (QueryTimeoutException | TransactionTimedOutException | PessimisticLockingFailureException exception) {
            throw new LookException(LookErrorCode.LOOK_BUSY, exception);
        }
    }
}
