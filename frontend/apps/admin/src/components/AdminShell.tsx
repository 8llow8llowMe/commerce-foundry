import { brand } from '@cf/brand';
import Link from 'next/link';
import type { ReactNode } from 'react';
import { LogoutButton } from './LogoutButton';
import { NavLinks, type NavGroup } from './NavLinks';

/** 메뉴. 화면이 생길 때마다 여기에 더한다(상품 등록 · 수정은 #9 PR-D/E 뒤, 주문은 #30) */
const NAV: NavGroup[] = [
  {
    title: '상품',
    items: [
      { label: '상품 목록', href: '/products' },
      { label: '카테고리', href: '/categories' },
      { label: '재고', href: '/stocks' },
      { label: '사이즈표', href: '/size-charts' },
      { label: '사이즈표 템플릿', href: '/size-chart-templates' },
    ],
  },
  {
    title: '전시 관리',
    items: [{ label: '메인', href: '/displays/main' }],
  },
  {
    title: '배송',
    items: [
      { label: '국내 배송비 정책', href: '/shipping-policies/domestic' },
      { label: '해외 배송비 정책', href: '/shipping-policies/international' },
    ],
  },
  {
    title: '회원',
    items: [{ label: '회원 목록', href: '/members' }],
  },
  {
    title: '운영',
    items: [
      { label: '백그라운드 작업', href: '/jobs' },
      { label: '관리자 감사 로그', href: '/audit-logs' },
    ],
  },
];

/**
 * 어드민 콘솔 화면 틀 — 상단 바(홈 · 로그아웃) · 왼쪽 메뉴 · 본문. 좁은 화면에서는 메뉴가 본문 위로 온다.
 * 로그인 화면은 이 틀 밖이다(`app/login`).
 */
export function AdminShell({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-dvh flex-col">
      <header className="flex h-14 items-center justify-between gap-4 bg-primary px-4 text-primary-fg">
        <Link href="/" className="text-sm font-semibold">
          {brand.name} 어드민
        </Link>
        <LogoutButton />
      </header>
      <div className="flex flex-1 flex-col md:flex-row">
        <aside className="bg-muted px-3 py-6 md:w-56 md:shrink-0">
          <NavLinks groups={NAV} />
        </aside>
        <main className="min-w-0 flex-1 px-4 py-8 md:px-10">{children}</main>
      </div>
    </div>
  );
}
