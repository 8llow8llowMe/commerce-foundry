/**
 * 운영자 역할 → 기능 권한 → 화면 (#237). BE 정본은 `AdminPermission.grantedTo(role)`(security-core)와
 * `backend/docs/security-guide.md` 의 "어드민 엔드포인트 → 권한" 표다. 이 파일은 그 표를 **메뉴 · 화면 진입 안내용으로만** 옮긴 사본이다.
 * - 인가의 정본은 서버다. 여기서 숨겨도 API 는 서버가 403 으로 막고, 여기서 보여도 서버가 403 이면 `AccessNotice` 가 안내한다.
 * - BE 표가 바뀌면 이 파일도 같이 바꾼다. 역할 · 권한을 내려주는 API(`GET /api/v1/admin/me` 류)가 생기면 이 사본을 지우고 그 응답을 쓴다.
 */

/** 운영자 역할 — BE `MemberRole.OPERATOR_ROLES`. `ADMIN` 은 #236 이행용이며 권한은 `OWNER` 와 같다 */
export const OPERATOR_ROLES = ['ADMIN', 'OWNER', 'MANAGER', 'STAFF'] as const;
export type OperatorRole = (typeof OPERATOR_ROLES)[number];

/** 화면 진입을 가르는 조회 권한만 둔다(BE `AdminPermission` 의 `*_READ`). 버튼 단위(`*_MANAGE`)는 아래 `canManageSettlement` 하나뿐이다 */
export type AdminReadPermission =
  | 'CATALOG_READ'
  | 'INVENTORY_READ'
  | 'ORDER_READ'
  | 'FULFILLMENT_READ'
  | 'CS_READ'
  | 'REPORT_READ'
  | 'SETTLEMENT_READ'
  | 'COST_READ'
  | 'STAFF_READ'
  | 'SECURITY_READ'
  | 'SETTINGS_READ';

const ALL_READ: readonly AdminReadPermission[] = [
  'CATALOG_READ',
  'INVENTORY_READ',
  'ORDER_READ',
  'FULFILLMENT_READ',
  'CS_READ',
  'REPORT_READ',
  'SETTLEMENT_READ',
  'COST_READ',
  'STAFF_READ',
  'SECURITY_READ',
  'SETTINGS_READ',
];

/** BE `AdminPermission.grantedTo` 의 조회 권한 부분 */
const GRANTED: Record<OperatorRole, readonly AdminReadPermission[]> = {
  ADMIN: ALL_READ,
  OWNER: ALL_READ,
  MANAGER: [
    'CATALOG_READ',
    'INVENTORY_READ',
    'ORDER_READ',
    'FULFILLMENT_READ',
    'CS_READ',
    'REPORT_READ',
  ],
  STAFF: ['CATALOG_READ', 'ORDER_READ', 'FULFILLMENT_READ', 'CS_READ'],
};

/**
 * 역할의 조회 권한. 역할을 모르면(null) null — "거르지 않는다" 는 뜻이다.
 * 토큰을 읽지 못했다고 메뉴를 모두 숨기면 쓸 수 있는 화면까지 막으므로, 그때는 서버 403 안내에 맡긴다.
 */
export function permissionsOf(role: OperatorRole | null): readonly AdminReadPermission[] | null {
  return role === null ? null : GRANTED[role];
}

/**
 * 정산 쓰기(`SETTLEMENT_MANAGE`) — 클레임 수동 환불 확정 · 재고 복원 없이 종결(#17 A3) 버튼을 거른다. BE `grantedTo` 에서 대표(ADMIN · OWNER)만 갖는다.
 * 역할을 모르면(null) null — 버튼을 숨기지 않고 서버 403 안내에 맡긴다(`permissionsOf` 와 같은 규칙).
 */
export function canManageSettlement(role: OperatorRole | null): boolean | null {
  if (role === null) return null;
  return role === 'ADMIN' || role === 'OWNER';
}

/**
 * 화면 경로(앞부분) → 그 화면이 처음 부르는 조회 API 의 권한. 여기 없는 경로(홈 · 내 계정 · 연습)는 운영자 전체에게 열려 있다.
 * 결제 확인(`/payments`)은 정산 업무라 `SETTLEMENT_READ`, 관리자 계정(`/admins`)은 직원 관리라 `STAFF_READ` 다.
 */
const ROUTE_PERMISSIONS: readonly (readonly [prefix: string, permission: AdminReadPermission])[] = [
  ['/products', 'CATALOG_READ'],
  ['/categories', 'CATALOG_READ'],
  ['/size-charts', 'CATALOG_READ'],
  ['/size-chart-templates', 'CATALOG_READ'],
  ['/displays', 'CATALOG_READ'],
  ['/stocks', 'INVENTORY_READ'],
  ['/sku-costs', 'COST_READ'],
  ['/orders', 'ORDER_READ'],
  ['/claims', 'ORDER_READ'],
  ['/payments', 'SETTLEMENT_READ'],
  ['/shipping-policies', 'FULFILLMENT_READ'],
  ['/members', 'CS_READ'],
  ['/support', 'CS_READ'],
  ['/store', 'SETTINGS_READ'],
  ['/jobs', 'REPORT_READ'],
  ['/admins', 'STAFF_READ'],
  ['/audit-logs', 'SECURITY_READ'],
];

/** 경로에 필요한 조회 권한. 경로가 같거나 그 아래면 맞는다(`/products/new` → `/products`). 없으면 null */
export function requiredPermissionOf(pathname: string): AdminReadPermission | null {
  const match = ROUTE_PERMISSIONS.find(
    ([prefix]) => pathname === prefix || pathname.startsWith(`${prefix}/`),
  );
  return match ? match[1] : null;
}

/** 이 권한 목록으로 경로에 들어갈 수 있는가. 권한을 모르면(null) 막지 않는다 */
export function canAccessPath(
  permissions: readonly AdminReadPermission[] | null,
  pathname: string,
): boolean {
  if (permissions === null) return true;
  const required = requiredPermissionOf(pathname);
  return required === null || permissions.includes(required);
}

/**
 * 메뉴에서 권한 없는 화면을 뺀다. 항목이 모두 빠진 묶음은 제목째 뺀다. 준비 중(href 없음) 항목은 그대로 둔다.
 * 권한을 모르면(null) 그대로 돌려준다.
 */
export function visibleNav<G extends { items: readonly { href?: string }[] }>(
  groups: readonly G[],
  permissions: readonly AdminReadPermission[] | null,
): G[] {
  if (permissions === null) return [...groups];
  return groups
    .map((group) => ({
      ...group,
      items: group.items.filter((item) => !item.href || canAccessPath(permissions, item.href)),
    }))
    .filter((group) => group.items.length > 0);
}
