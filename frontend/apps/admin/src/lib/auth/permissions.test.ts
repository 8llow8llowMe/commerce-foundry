import { describe, expect, it } from 'vitest';
import { canAccessPath, permissionsOf, requiredPermissionOf, visibleNav } from './permissions';

type Group = { title: string; items: { label: string; href?: string }[] };

const NAV: Group[] = [
  {
    title: '주문',
    items: [
      { label: '주문 목록', href: '/orders' },
      { label: '결제 확인', href: '/payments' },
    ],
  },
  {
    title: '운영',
    items: [
      { label: '관리자 계정', href: '/admins' },
      { label: '관리자 감사 로그', href: '/audit-logs' },
    ],
  },
  { title: '준비', items: [{ label: '대시보드' }] },
];

describe('permissionsOf', () => {
  it('ADMIN 은 OWNER 와 같이 모든 조회 권한을 갖는다(#236 이행)', () => {
    expect(permissionsOf('ADMIN')).toEqual(permissionsOf('OWNER'));
    expect(permissionsOf('OWNER')).toContain('SETTLEMENT_READ');
    expect(permissionsOf('OWNER')).toContain('STAFF_READ');
  });

  it('MANAGER 는 정산 · 원가 · 직원 · 보안 · 설정 조회 권한이 없다(BE AdminPermission)', () => {
    const manager = permissionsOf('MANAGER');
    for (const missing of [
      'SETTLEMENT_READ',
      'COST_READ',
      'STAFF_READ',
      'SECURITY_READ',
      'SETTINGS_READ',
    ] as const) {
      expect(manager).not.toContain(missing);
    }
    expect(manager).toContain('INVENTORY_READ');
    expect(manager).toContain('REPORT_READ');
  });

  it('STAFF 는 상품 · 주문 · 출고 · CS 조회만 갖는다', () => {
    expect(permissionsOf('STAFF')).toEqual([
      'CATALOG_READ',
      'ORDER_READ',
      'FULFILLMENT_READ',
      'CS_READ',
    ]);
  });

  it('역할을 모르면 null(거르지 않는다)', () => {
    expect(permissionsOf(null)).toBeNull();
  });
});

describe('requiredPermissionOf', () => {
  it('경로가 같거나 그 아래면 그 화면의 권한이다', () => {
    expect(requiredPermissionOf('/payments')).toBe('SETTLEMENT_READ');
    expect(requiredPermissionOf('/admins')).toBe('STAFF_READ');
    expect(requiredPermissionOf('/products/new')).toBe('CATALOG_READ');
    expect(requiredPermissionOf('/store/footer')).toBe('SETTINGS_READ');
  });

  it('비슷한 앞부분만 같은 경로는 맞지 않는다', () => {
    expect(requiredPermissionOf('/size-chart-templates')).toBe('CATALOG_READ');
    expect(requiredPermissionOf('/paymentsx')).toBeNull();
  });

  it('홈 · 내 계정 · 연습은 운영자 전체에게 열려 있다', () => {
    expect(requiredPermissionOf('/')).toBeNull();
    expect(requiredPermissionOf('/account')).toBeNull();
    expect(requiredPermissionOf('/practice')).toBeNull();
  });
});

describe('canAccessPath', () => {
  it('MANAGER 는 결제 확인(정산) · 관리자 계정(직원 관리)에 들어갈 수 없다', () => {
    const manager = permissionsOf('MANAGER');
    expect(canAccessPath(manager, '/payments')).toBe(false);
    expect(canAccessPath(manager, '/admins')).toBe(false);
    expect(canAccessPath(manager, '/orders/123')).toBe(true);
    expect(canAccessPath(manager, '/account')).toBe(true);
  });

  it('권한을 모르면 막지 않는다', () => {
    expect(canAccessPath(null, '/admins')).toBe(true);
  });
});

describe('visibleNav', () => {
  it('MANAGER 는 정산 · 직원 관리 메뉴가 빠지고, 비는 묶음은 제목째 빠진다', () => {
    expect(visibleNav(NAV, permissionsOf('MANAGER'))).toEqual([
      { title: '주문', items: [{ label: '주문 목록', href: '/orders' }] },
      { title: '준비', items: [{ label: '대시보드' }] },
    ]);
  });

  it('OWNER · 역할 모름은 그대로다', () => {
    expect(visibleNav(NAV, permissionsOf('OWNER'))).toEqual(NAV);
    expect(visibleNav(NAV, null)).toEqual(NAV);
  });
});
