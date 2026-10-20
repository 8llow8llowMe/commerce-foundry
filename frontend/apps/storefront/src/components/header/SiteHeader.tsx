import type { NavItem } from '@cf/ui';
import type { LanguageLink } from '@/components/i18n/LanguageLinks';
import { Link } from '@/components/i18n/Link';
import type { Dictionary } from '@/lib/i18n/dictionaries';
import { AccountIcon, BagIcon, SearchIcon } from './HeaderIcons';
import { MenuPanel } from './MenuPanel';

export type SiteHeaderProps = {
  brandName: string;
  navigation: NavItem[];
  labels: Dictionary['header'];
  /** 언어 전환 링크. 하나뿐이면 보이지 않는다 */
  languages: LanguageLink[];
  /** 인증 쿠키가 있는가 — 계정 진입점(로그인 ↔ 마이페이지)만 바꾼다 */
  signedIn: boolean;
};

const NAV_LINK = 'text-xs tracking-[0.2em] uppercase hover:text-primary';

/**
 * 스토어프론트 공통 헤더. 좌: 전체 메뉴 / 가운데: 워드마크 / 우: 검색 · 계정 · 장바구니.
 * 계정 아이콘은 로그아웃 상태면 로그인, 로그인 상태면 마이페이지로 간다. 로그아웃 · 회원가입은 전체 메뉴에 있다.
 * 아래 줄은 PC 에서 1차 메뉴(primary), 모바일에서 바로가기(shortcut)다. 메뉴는 브랜드 설정(@cf/brand)이 정한다.
 */
export function SiteHeader({
  brandName,
  navigation,
  labels,
  languages,
  signedIn,
}: SiteHeaderProps) {
  const primary = navigation.filter((item) => item.primary);
  const shortcuts = navigation.filter((item) => item.shortcut);

  return (
    <header className="sticky top-0 z-40 border-b border-border bg-bg">
      <div className="mx-auto max-w-screen-xl px-4 lg:px-8">
        {/* 양옆 칸은 내용보다 좁아지지 않는다 — 좁은 화면에서 로고가 가운데 칸을 넘치면 아이콘과 겹치지 않게 로고가 조금 비켜난다.
            공간이 있으면 양옆이 같은 폭이라 로고는 정가운데다 */}
        <div className="grid h-14 grid-cols-[minmax(max-content,1fr)_auto_minmax(max-content,1fr)] items-center lg:h-16">
          <div className="flex items-center">
            <MenuPanel
              brandName={brandName}
              items={navigation}
              labels={labels}
              languages={languages}
              signedIn={signedIn}
            />
          </div>

          <Link
            href="/"
            className="justify-self-center font-display text-xl tracking-[0.25em] whitespace-nowrap uppercase sm:text-2xl lg:text-3xl"
          >
            {brandName}
          </Link>

          <div className="-mr-2 flex items-center justify-end">
            <Link
              href="/search"
              aria-label={labels.search}
              className="inline-flex size-10 items-center justify-center"
            >
              <SearchIcon />
            </Link>
            <Link
              href={signedIn ? '/mypage' : '/login'}
              aria-label={signedIn ? labels.account : labels.login}
              className="inline-flex size-10 items-center justify-center"
            >
              <AccountIcon />
            </Link>
            <Link
              href="/cart"
              aria-label={labels.cart}
              className="inline-flex size-10 items-center justify-center"
            >
              <BagIcon />
            </Link>
          </div>
        </div>

        {primary.length > 0 && (
          <nav
            aria-label={labels.primaryNav}
            className="hidden h-10 items-center justify-center lg:flex"
          >
            <ul className="flex justify-center gap-12">
              {primary.map((item) => (
                <li key={item.href}>
                  <Link href={item.href} className={NAV_LINK}>
                    {item.label}
                  </Link>
                </li>
              ))}
            </ul>
          </nav>
        )}

        {shortcuts.length > 0 && (
          <nav
            aria-label={labels.shortcuts}
            className="flex h-9 items-center justify-center lg:hidden"
          >
            <ul className="flex justify-center gap-8">
              {shortcuts.map((item) => (
                <li key={item.href}>
                  <Link href={item.href} className={NAV_LINK}>
                    {item.label}
                  </Link>
                </li>
              ))}
            </ul>
          </nav>
        )}
      </div>
    </header>
  );
}
