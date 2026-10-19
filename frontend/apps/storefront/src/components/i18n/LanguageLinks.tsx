'use client';

import type { Locale } from '@cf/ui';
import { cx } from '@cf/ui';
import type { MouseEvent, SyntheticEvent } from 'react';
import { switchLocaleHref, type LocaleTarget } from '@/lib/i18n/routing';
import { useLocale, useLocalePrefix } from './LocaleProvider';

export type LanguageLink = {
  locale: Locale;
  /** 그 언어로 쓴 이름 ('English') */
  name: string;
  /** 그 언어의 메인 주소 — 언어 호스트를 쓰면 절대 주소다. JS 전 · 실패 시의 기본 링크 */
  href: string;
  /** 같은 화면으로 갈 때 쓰는 그 언어의 호스트 · 접두어 */
  target: LocaleTarget;
};

/**
 * 언어 전환 링크. 다른 언어는 루트 레이아웃이 달라 문서를 새로 받으므로 next/link 가 아니라 <a> 다.
 * 언어가 하나뿐이면 그리지 않는다.
 *
 * 서버는 각 언어의 메인 주소로 그리고, 링크를 가리키거나(포커스 · 포인터) 누를 때 브라우저 주소로
 * 같은 화면의 주소로 바꾼다. rewrite 뒤 usePathname() 은 브라우저 주소와 다를 수 있고, 렌더 중에
 * window 를 읽으면 hydration 이 어긋나서다. 쿼리(정렬 · 카테고리)도 그때의 값을 그대로 가져간다.
 */
export function LanguageLinks({
  languages,
  label,
  className,
}: {
  languages: LanguageLink[];
  label: string;
  className?: string;
}) {
  const current = useLocale();
  const currentPrefix = useLocalePrefix();
  if (languages.length < 2) return null;

  const sameScreen = (target: LocaleTarget) =>
    switchLocaleHref(
      { pathname: window.location.pathname, search: window.location.search },
      currentPrefix,
      target,
    );
  // 새 탭 열기 · 주소 복사도 같은 화면으로 가도록 가리킬 때 href 를 바꿔 둔다
  const retarget = (target: LocaleTarget) => (event: SyntheticEvent<HTMLAnchorElement>) => {
    event.currentTarget.href = sameScreen(target);
  };
  const go = (target: LocaleTarget) => (event: MouseEvent<HTMLAnchorElement>) => {
    // 수정 키 클릭(새 탭 · 새 창)은 브라우저에 맡긴다 — href 는 retarget 이 이미 바꿔 두었다
    if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey || event.button !== 0) {
      event.currentTarget.href = sameScreen(target);
      return;
    }
    event.preventDefault();
    window.location.assign(sameScreen(target));
  };
  return (
    <nav aria-label={label} className={className}>
      <ul className="flex gap-4 text-xs tracking-[0.15em]">
        {languages.map((language) => (
          <li key={language.locale}>
            <a
              href={language.href}
              onPointerEnter={retarget(language.target)}
              onFocus={retarget(language.target)}
              onClick={go(language.target)}
              hrefLang={language.locale}
              lang={language.locale}
              aria-current={language.locale === current ? 'true' : undefined}
              className={cx(
                'hover:text-fg',
                language.locale === current
                  ? 'text-fg underline underline-offset-4'
                  : 'text-muted-fg',
              )}
            >
              {language.name}
            </a>
          </li>
        ))}
      </ul>
    </nav>
  );
}
