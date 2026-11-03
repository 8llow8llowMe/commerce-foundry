package com.commercefoundry.security.principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdminPermissionTest {

    @Test
    @DisplayName("기존 ADMIN 과 OWNER 는 같은 기능 권한을 받고 CUSTOMER 는 하나도 받지 않는다")
    void ownerAndLegacyAdminHaveTheSamePermissions() {
        assertThat(AdminPermission.grantedTo(MemberRole.ADMIN)).containsExactlyInAnyOrder(AdminPermission.values());
        assertThat(AdminPermission.grantedTo(MemberRole.OWNER)).isEqualTo(AdminPermission.grantedTo(MemberRole.ADMIN));
        assertThat(AdminPermission.grantedTo(MemberRole.CUSTOMER)).isEmpty();
    }

    @Test
    @DisplayName("MANAGER 는 상품·재고 조회와 주문·출고·CS 처리, 리포트 조회만 받는다")
    void managerCanOperateWithoutFinanceOrStaffPrivileges() {
        assertThat(AdminPermission.grantedTo(MemberRole.MANAGER)).containsExactlyInAnyOrder(
            AdminPermission.CATALOG_READ, AdminPermission.INVENTORY_READ,
            AdminPermission.ORDER_READ, AdminPermission.ORDER_MANAGE,
            AdminPermission.FULFILLMENT_READ, AdminPermission.FULFILLMENT_MANAGE,
            AdminPermission.CS_READ, AdminPermission.CS_MANAGE,
            AdminPermission.REPORT_READ
        );
    }

    @Test
    @DisplayName("STAFF 는 상품·주문·출고·CS 조회만 받고 쓰기 권한이 없다")
    void staffCanOnlyReadOperationalData() {
        assertThat(AdminPermission.grantedTo(MemberRole.STAFF)).containsExactlyInAnyOrder(
            AdminPermission.CATALOG_READ, AdminPermission.ORDER_READ,
            AdminPermission.FULFILLMENT_READ, AdminPermission.CS_READ
        );
        assertThat(AdminPermission.grantedTo(MemberRole.STAFF)).noneMatch(permission -> permission.name().endsWith("_MANAGE"));
    }

    @Test
    @DisplayName("권한 이름은 ROLE_·SERVICE_ 와 겹치지 않고 반환한 권한표를 호출자가 고칠 수 없다")
    void permissionAuthoritiesAreDistinctAndImmutable() {
        assertThat(AdminPermission.values()).extracting(AdminPermission::authority)
            .allMatch(authority -> authority.startsWith("ADMIN_"));
        assertThatThrownBy(() -> AdminPermission.grantedTo(MemberRole.MANAGER).add(AdminPermission.STAFF_MANAGE))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
