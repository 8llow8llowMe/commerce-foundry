import { brand } from '@cf/brand';
import Link from 'next/link';
import type { ReactNode } from 'react';
import { AccountMenu } from './AccountMenu';
import { JobTray } from './job/JobTray';
import { NavLinks, type NavGroup } from './NavLinks';
import { NotificationCenter } from './notification/NotificationCenter';
import { HelpMenu } from './tour/HelpMenu';
import { TourRunner } from './tour/TourRunner';

/** 메뉴. 화면이 생길 때마다 여기에 더한다(상품 등록 · 수정은 #9 PR-D/E 뒤, 주문은 #30) */
const NAV: NavGroup[] = [
  {
    title: '상품',
    items: [
      { label: '상품 목록', href: '/products' },
      { label: '카테고리', href: '/categories' },
      { label: '재고', href: '/stocks' },
      { label: '원가', href: '/sku-costs' },
      { label: '사이즈표', href: '/size-charts' },
      { label: '사이즈표 템플릿', href: '/size-chart-templates' },
    ],
  },
  {
    title: '전시 관리',
    items: [
      { label: '메인', href: '/displays/main' },
      { label: '룩', href: '/displays/looks' },
    ],
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
      { label: '관리자 계정', href: '/admins' },
      { label: '관리자 감사 로그', href: '/audit-logs' },
    ],
  },
];

/**
 * 어드민 콘솔 화면 틀 — 상단 바(홈 · 작업 진행 · 알림 · 도움말 · 계정 메뉴) · 왼쪽 메뉴 · 본문. 좁은 화면에서는 메뉴가 본문 위로 온다.
 * 작업 진행(`JobTray`)과 알림(`NotificationCenter`)은 이 틀에 붙어 화면을 옮겨도 이어진다(#214).
 * 도움말(`HelpMenu`)과 온보딩 투어(`TourRunner`, #228)도 여기 붙는다 — 투어가 가리키는 요소에는 `data-tour` 를 단다.
 * 로그인 화면은 이 틀 밖이다(`app/login`).
 */
export function AdminShell({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-dvh flex-col">
      <header className="flex h-14 items-center justify-between gap-4 bg-primary px-4 text-primary-fg">
        <Link href="/" className="text-sm font-semibold">
          {brand.name} 어드민
        </Link>
        <div className="flex items-center gap-1 sm:gap-2">
          <JobTray />
          <div data-tour="notifications">
            <NotificationCenter />
          </div>
          <div data-tour="help">
            <HelpMenu />
          </div>
          <AccountMenu />
        </div>
      </header>
      <div className="flex flex-1 flex-col md:flex-row">
        <aside data-tour="nav" className="bg-muted px-3 py-6 md:w-56 md:shrink-0">
          <NavLinks groups={NAV} />
        </aside>
        <main className="min-w-0 flex-1 px-4 py-8 md:px-10">{children}</main>
      </div>
      <TourRunner />
    </div>
  );
}
