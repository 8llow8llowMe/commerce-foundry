package com.commercefoundry.security.principal;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 운영자 기능 권한 (#236). 역할은 JWT 의 단일 {@code role} claim 으로 받고, 검증한 뒤 서버가 이 권한 목록을 펼친다.
 * {@code MANAGE} 가 {@code READ} 를 자동 포함하지 않는다 — 역할표에 둘 다 적어야 한다.
 */
public enum AdminPermission {

    CATALOG_READ,
    CATALOG_MANAGE,
    INVENTORY_READ,
    INVENTORY_MANAGE,
    ORDER_READ,
    ORDER_MANAGE,
    FULFILLMENT_READ,
    FULFILLMENT_MANAGE,
    CS_READ,
    CS_MANAGE,
    REPORT_READ,
    REPORT_MANAGE,
    SETTLEMENT_READ,
    SETTLEMENT_MANAGE,
    COST_READ,
    COST_MANAGE,
    STAFF_READ,
    STAFF_MANAGE,
    SECURITY_READ,
    SECURITY_MANAGE,
    SETTINGS_READ,
    SETTINGS_MANAGE;

    private static final Set<AdminPermission> ALL = Collections.unmodifiableSet(EnumSet.allOf(AdminPermission.class));
    private static final Set<AdminPermission> MANAGER = Collections.unmodifiableSet(EnumSet.of(
        CATALOG_READ,
        INVENTORY_READ,
        ORDER_READ, ORDER_MANAGE,
        FULFILLMENT_READ, FULFILLMENT_MANAGE,
        CS_READ, CS_MANAGE,
        REPORT_READ
    ));
    private static final Set<AdminPermission> STAFF = Collections.unmodifiableSet(EnumSet.of(
        CATALOG_READ, ORDER_READ, FULFILLMENT_READ, CS_READ
    ));

    /** 기능 권한 이름. 역할 권한 {@code ROLE_*} 및 서비스 토큰 {@code SERVICE_*} 와 겹치지 않는다. */
    public String authority() {
        return "ADMIN_" + name();
    }

    /** 기존 ADMIN 은 OWNER 와 같다. CUSTOMER 에게 운영자 기능 권한을 주지 않는다. */
    public static Set<AdminPermission> grantedTo(MemberRole role) {
        return switch (role) {
            case CUSTOMER -> Set.of();
            case ADMIN, OWNER -> ALL;
            case MANAGER -> MANAGER;
            case STAFF -> STAFF;
        };
    }
}
