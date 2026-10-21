import { brand } from '@cf/brand';
import Link from 'next/link';
import type { ReactNode } from 'react';
import { NavLinks, type NavGroup } from './NavLinks';

/** 메뉴. 화면이 생길 때마다 여기에 더한다(상품 · 카테고리 · 주문은 #29 · #30) */
const NAV: NavGroup[] = [
  {
    title: '상품',
    items: [{ label: '재고', href: '/stocks' }],
  },
  {
    title: '배송',
    items: [
      { label: '국내 배송비 정책', href: '/shipping-policies/domestic' },
      { label: '해외 배송비 정책', href: '/shipping-policies/international' },
    ],
  },
];

/**
 * 어드민 콘솔 화면 틀 — 상단 바 · 왼쪽 메뉴 · 본문. 좁은 화면에서는 메뉴가 본문 위로 온다.
 * 로그인 · 로그아웃은 어드민 로그인(#29)에서 상단 바에 붙인다.
 */
export function AdminShell({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-dvh flex-col">
      <header className="flex h-14 items-center bg-primary px-4 text-primary-fg">
        <Link href="/" className="text-sm font-semibold">
          {brand.name} 어드민
        </Link>
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
