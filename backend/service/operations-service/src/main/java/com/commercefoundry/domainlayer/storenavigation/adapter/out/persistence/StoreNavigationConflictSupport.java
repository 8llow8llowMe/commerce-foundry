package com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence;

import com.commercefoundry.persistence.util.ConstraintViolations;

final class StoreNavigationConflictSupport {
    private StoreNavigationConflictSupport() {
    }

    /**
     * 동시 저장 경합으로만 날 수 있는 제약 위반을 409 로 본다. 항목 PK(리비전, key) 중복은 새 Snowflake 리비전 안에서
     * 검증을 건너뛴 입력일 때만 나므로 충돌로 감추지 않는다.
     */
    static boolean knownIntegrityConflict(Throwable exception, boolean creating) {
        return (creating && ConstraintViolations.violates(exception, "store_navigation_pkey"))
            || ConstraintViolations.violates(exception, "uk_store_navigation_revision_navigation_id_revision_number");
    }
}
