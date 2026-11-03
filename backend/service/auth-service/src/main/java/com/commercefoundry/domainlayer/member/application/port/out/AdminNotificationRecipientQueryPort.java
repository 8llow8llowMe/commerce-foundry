package com.commercefoundry.domainlayer.member.application.port.out;

import java.util.List;

/** 활성 대표 관리자(기존 ADMIN · OWNER — {@code MemberRole.OWNER_ROLES}, #236) 아이디만 읽는다. 이름 · 계정 정보 조회와 분리한다. */
public interface AdminNotificationRecipientQueryPort {

    List<Long> findActiveOwnerIdsAfter(long afterId, int size);
}
