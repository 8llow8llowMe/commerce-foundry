'use client';

import NextLink from 'next/link';
import type { ComponentProps } from 'react';
import { useLocalizedHref } from './LocaleProvider';

/**
 * next/link 와 같고, 문자열 앱 경로('/shop')에 지금 언어의 접두어를 붙인다.
 * 스토어프론트 화면은 next/link 대신 이것을 쓴다 — 브랜드 설정 · 컴포넌트는 언어 없는 경로만 안다.
 */
export function Link({ href, ...rest }: ComponentProps<typeof NextLink>) {
  const localize = useLocalizedHref();
  return <NextLink href={typeof href === 'string' ? localize(href) : href} {...rest} />;
}
