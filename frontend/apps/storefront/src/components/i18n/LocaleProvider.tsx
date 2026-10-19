'use client';

import type { Locale } from '@cf/ui';
import { createContext, useContext, type ReactNode } from 'react';
import { withLocalePrefix } from '@/lib/i18n/routing';

type LocaleContextValue = {
  locale: Locale;
  /** 이 언어 주소의 경로 접두어('' 또는 '/en') — 서버가 라우팅 설정으로 정해 준다 */
  prefix: string;
};

// 레이아웃 밖(단독 테스트 렌더)에서는 접두어 없는 기본값이다
const LocaleContext = createContext<LocaleContextValue>({ locale: 'ko', prefix: '' });

/** 클라이언트 컴포넌트가 지금 언어와 링크 접두어를 알게 한다. [locale] 레이아웃이 감싼다 */
export function LocaleProvider({
  locale,
  prefix,
  children,
}: LocaleContextValue & { children: ReactNode }) {
  return <LocaleContext value={{ locale, prefix }}>{children}</LocaleContext>;
}

export function useLocale(): Locale {
  return useContext(LocaleContext).locale;
}

/** 앱 경로를 지금 언어의 주소로 바꾸는 함수 */
export function useLocalizedHref(): (path: string) => string {
  const { prefix } = useContext(LocaleContext);
  return (path) => withLocalePrefix(prefix, path);
}
