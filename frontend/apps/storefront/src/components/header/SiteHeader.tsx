import type { NavItem } from '@cf/ui';
import type { LanguageLink } from '@/components/i18n/LanguageLinks';
import { Link } from '@/components/i18n/Link';
import type { Dictionary } from '@/lib/i18n/dictionaries';
import { BagIcon, SearchIcon } from './HeaderIcons';
import { MenuPanel } from './MenuPanel';

export type SiteHeaderProps = {
  brandName: string;
  navigation: NavItem[];
  labels: Dictionary['header'];
  /** 언어 전환 링크. 하나뿐이면 보이지 않는다 */
  languages: LanguageLink[];
};

const NAV_LINK = 'text-xs tracking-[0.2em] uppercase hover:text-primary';

/**
 * 스토어프론트 공통 헤더. 좌: 전체 메뉴 / 가운데: 워드마크 / 우: 검색 · 장바구니.
 * 아래 줄은 PC 에서 1차 메뉴(primary), 모바일에서 바로가기(shortcut)다. 메뉴는 브랜드 설정(@cf/brand)이 정한다.
 */
export function SiteHeader({ brandName, navigation, labels, languages }: SiteHeaderProps) {
  const primary = navigation.filter((item) => item.primary);
  const shortcuts = navigation.filter((item) => item.shortcut);

  return (
    <header className="sticky top-0 z-40 border-b border-border bg-bg">
      <div className="mx-auto max-w-screen-xl px-4 lg:px-8">
        <div className="grid h-14 grid-cols-3 items-center lg:h-16">
          <div className="flex items-center">
            <MenuPanel
              brandName={brandName}
              items={navigation}
              labels={labels}
              languages={languages}
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
