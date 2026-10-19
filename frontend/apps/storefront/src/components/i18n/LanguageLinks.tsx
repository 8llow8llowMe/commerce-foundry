'use client';

import type { Locale } from '@cf/ui';
import { cx } from '@cf/ui';
import { useLocale } from './LocaleProvider';

export type LanguageLink = {
  locale: Locale;
  /** 그 언어로 쓴 이름 ('English') */
  name: string;
  /** 그 언어의 메인 주소 — 언어 호스트를 쓰면 절대 주소다 */
  href: string;
};

/**
 * 언어 전환 링크. 다른 언어는 루트 레이아웃이 달라 문서를 새로 받으므로 next/link 가 아니라 <a> 다.
 * 언어가 하나뿐이면 그리지 않는다.
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
  if (languages.length < 2) return null;
  return (
    <nav aria-label={label} className={className}>
      <ul className="flex gap-4 text-xs tracking-[0.15em]">
        {languages.map((language) => (
          <li key={language.locale}>
            <a
              href={language.href}
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
