package com.commercefoundry.domainlayer.member.application.port.out;

import java.util.List;

/** 활성 ADMIN 아이디만 읽는다. 이름 · 계정 정보 조회와 분리한다. */
public interface AdminNotificationRecipientQueryPort {

    List<Long> findActiveAdminIdsAfter(long afterId, int size);
}
