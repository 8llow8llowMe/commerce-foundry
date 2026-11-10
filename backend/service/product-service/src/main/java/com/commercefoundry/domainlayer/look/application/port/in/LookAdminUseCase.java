package com.commercefoundry.domainlayer.look.application.port.in;

import com.commercefoundry.domainlayer.look.adapter.in.admin.dto.response.LookAdminListResponse;
import com.commercefoundry.domainlayer.look.adapter.in.admin.dto.response.LookAdminResponse;
import com.commercefoundry.domainlayer.look.application.command.LookUpsertCommand;
import java.util.List;

/**
 * 어드민 룩 관리. 기능 플래그({@code store.features.looks})와 무관하게 동작한다 — 플래그는 고객 화면 노출만 정한다.
 * 쓰기는 모두 룩 전체 잠금 안에서 판정 · 저장한다 — 잠금이 시간 상한 안에 안 풀리면 {@code LOOK_008}(409)이다.
 * 쓰기의 마지막 인자 {@code actorMemberId} 는 감사(#163, {@code LOOK_*})에만 쓰는 행위자(access 토큰의 memberId)다 — 룩 표에는 남지 않는다.
 */
public interface LookAdminUseCase {

    /** 숨긴 룩 포함 전체 (노출 순서 → 아이디). */
    LookAdminListResponse getLooks();

    /** 없으면 {@code LOOK_001}(404)이다. */
    LookAdminResponse getLook(long lookId);

    /**
     * 룩과 구성 상품을 만들고 맨 뒤에 둔다. 상품 중복은 {@code LOOK_003}, 없는 상품은 {@code LOOK_004}, 이미지 주소는 {@code LOOK_005}(400) ·
     * 미디어 저장소 미설정은 {@code LOOK_006}(503), 룩 수 상한은 {@code LOOK_002}(409)이다.
     */
    LookAdminResponse create(LookUpsertCommand command, long actorMemberId);

    /** 룩 칸과 구성 상품 전체를 바꾼다(노출 순서는 그대로). 없으면 {@code LOOK_001}(404), 그 밖은 생성과 같다. */
    LookAdminResponse update(long lookId, LookUpsertCommand command, long actorMemberId);

    /** 룩과 구성 상품을 지운다. 없으면 {@code LOOK_001}(404)이다. */
    void delete(long lookId, long actorMemberId);

    /** 받은 순서대로 0..n-1 을 매긴다. 현재 룩 아이디 집합과 다르면 {@code LOOK_007}(400)이다. 응답은 새 순서의 전체 목록이다. */
    LookAdminListResponse reorder(List<Long> lookIds, long actorMemberId);
}
