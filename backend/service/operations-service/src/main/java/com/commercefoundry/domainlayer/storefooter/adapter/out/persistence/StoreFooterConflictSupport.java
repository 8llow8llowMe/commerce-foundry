package com.commercefoundry.domainlayer.storefooter.adapter.out.persistence;

import com.commercefoundry.persistence.util.ConstraintViolations;

final class StoreFooterConflictSupport {
    private StoreFooterConflictSupport() {
    }

    static boolean knownIntegrityConflict(Throwable exception, boolean creating) {
        return (creating && ConstraintViolations.violates(exception, "store_footer_pkey"))
            || ConstraintViolations.violates(exception, "uk_store_footer_revision_footer_id_revision_number")
            || ConstraintViolations.violates(exception, "uk_store_footer_link_revision_id_display_order");
    }
}
