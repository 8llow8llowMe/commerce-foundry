package com.commercefoundry.security.principal;

/**
 * 어드민 핸들러의 {@code @PreAuthorize} 식 상수 (#236). 식 문자열을 컨트롤러에 직접 쓰지 않고 이 상수를 참조한다 —
 * 오타가 컴파일 오류가 되고, 정적 규칙 · 인가 스모크({@code AuthorizationExpressions#requiresOperatorAuthority})가 받는 모양과 같다.
 *
 * <pre>{@code
 * @PreAuthorize(AdminAuthority.ORDER_MANAGE)
 * }</pre>
 *
 * <ul>
 *   <li>기능 상수는 {@link AdminPermission#authority()} 와 1:1 이다 ({@code AdminAuthorityTest} 가 대조한다).
 *       {@code MANAGE} 가 {@code READ} 를 포함하지 않는다 — 조회 핸들러는 {@code *_READ}, 변경 핸들러는 {@code *_MANAGE} 를 쓴다.</li>
 *   <li>{@link #OPERATOR} 는 기능과 무관하게 운영자 본인에게 열린 API(내 비밀번호 · 내 OTP · 내 알림 · 내 온보딩 진행)에만 쓴다.
 *       역할 목록은 {@link MemberRole#OPERATOR_ROLES} 와 같다.</li>
 *   <li>어드민 핸들러의 식은 이 상수만 쓴다. 정적 규칙 · 인가 스모크는 #236 이전 표기 {@code hasRole('ADMIN')} 을 받지 않는다 (#588).</li>
 * </ul>
 */
public final class AdminAuthority {

    /** 운영자 역할 전체 — {@link MemberRole#OPERATOR_ROLES}. 기능 권한이 필요 없는 본인 API 용. */
    public static final String OPERATOR = "hasAnyRole('ADMIN','OWNER','MANAGER','STAFF')";

    public static final String CATALOG_READ = "hasAuthority('ADMIN_CATALOG_READ')";
    public static final String CATALOG_MANAGE = "hasAuthority('ADMIN_CATALOG_MANAGE')";
    public static final String INVENTORY_READ = "hasAuthority('ADMIN_INVENTORY_READ')";
    public static final String INVENTORY_MANAGE = "hasAuthority('ADMIN_INVENTORY_MANAGE')";
    public static final String ORDER_READ = "hasAuthority('ADMIN_ORDER_READ')";
    public static final String ORDER_MANAGE = "hasAuthority('ADMIN_ORDER_MANAGE')";
    public static final String FULFILLMENT_READ = "hasAuthority('ADMIN_FULFILLMENT_READ')";
    public static final String FULFILLMENT_MANAGE = "hasAuthority('ADMIN_FULFILLMENT_MANAGE')";
    public static final String CS_READ = "hasAuthority('ADMIN_CS_READ')";
    public static final String CS_MANAGE = "hasAuthority('ADMIN_CS_MANAGE')";
    public static final String REPORT_READ = "hasAuthority('ADMIN_REPORT_READ')";
    public static final String REPORT_MANAGE = "hasAuthority('ADMIN_REPORT_MANAGE')";
    public static final String SETTLEMENT_READ = "hasAuthority('ADMIN_SETTLEMENT_READ')";
    public static final String SETTLEMENT_MANAGE = "hasAuthority('ADMIN_SETTLEMENT_MANAGE')";
    public static final String COST_READ = "hasAuthority('ADMIN_COST_READ')";
    public static final String COST_MANAGE = "hasAuthority('ADMIN_COST_MANAGE')";
    public static final String STAFF_READ = "hasAuthority('ADMIN_STAFF_READ')";
    public static final String STAFF_MANAGE = "hasAuthority('ADMIN_STAFF_MANAGE')";
    public static final String SECURITY_READ = "hasAuthority('ADMIN_SECURITY_READ')";
    public static final String SECURITY_MANAGE = "hasAuthority('ADMIN_SECURITY_MANAGE')";
    public static final String SETTINGS_READ = "hasAuthority('ADMIN_SETTINGS_READ')";
    public static final String SETTINGS_MANAGE = "hasAuthority('ADMIN_SETTINGS_MANAGE')";

    private AdminAuthority() {
    }
}
