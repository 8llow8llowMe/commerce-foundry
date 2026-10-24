package com.commercefoundry.domainlayer.display.application.port.in;

import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.response.DisplaySectionAdminListResponse;
import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.response.DisplaySectionAdminResponse;
import com.commercefoundry.domainlayer.display.application.command.DisplaySectionUpsertCommand;
import java.util.List;

/**
 * 어드민 메인 전시 관리. 쓰기는 모두 전시 전체 잠금 안에서 판정 · 저장한다 — 잠금이 시간 상한 안에 안 풀리면 {@code PRODUCT_058}(409)이다.
 */
public interface DisplayAdminUseCase {

    /** 숨김 · 기간 밖 포함 전체 섹션 (노출 순서 → 아이디). */
    DisplaySectionAdminListResponse getSections();

    /** 없으면 {@code PRODUCT_046}(404)이다. */
    DisplaySectionAdminResponse getSection(long sectionId);

    /**
     * 섹션과 아이템을 만들고 맨 뒤에 둔다. 섹션 규칙 위반은 {@code PRODUCT_047~054}(400), 미디어 저장소 미설정은 {@code PRODUCT_055}(503),
     * 섹션 수 상한은 {@code PRODUCT_056}(409)이다.
     */
    DisplaySectionAdminResponse createSection(DisplaySectionUpsertCommand command);

    /** 섹션 칸과 아이템 전체를 바꾼다(노출 순서는 그대로). 없으면 {@code PRODUCT_046}(404), 그 밖은 생성과 같다. */
    DisplaySectionAdminResponse updateSection(long sectionId, DisplaySectionUpsertCommand command);

    /** 섹션과 아이템을 지운다. 없으면 {@code PRODUCT_046}(404)이다. */
    void deleteSection(long sectionId);

    /** 받은 순서대로 0..n-1 을 매긴다. 현재 섹션 아이디 집합과 다르면 {@code PRODUCT_057}(400)이다. 응답은 새 순서의 전체 목록이다. */
    DisplaySectionAdminListResponse reorderSections(List<Long> sectionIds);
}
