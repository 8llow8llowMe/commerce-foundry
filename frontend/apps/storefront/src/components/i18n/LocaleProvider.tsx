'use client';

import { formatMoney, type Money } from '@cf/api-client';
import type { Locale } from '@cf/ui';
import { createContext, useContext, type ReactNode } from 'react';
import type { Dictionary } from '@/lib/i18n/dictionaries';
import { ko } from '@/lib/i18n/dictionaries/ko';
import { withLocalePrefix } from '@/lib/i18n/routing';

type LocaleContextValue = {
  locale: Locale;
  /** 이 언어 주소의 경로 접두어('' 또는 '/en') — 서버가 라우팅 설정으로 정해 준다 */
  prefix: string;
  /** 지금 언어의 UI 문구. 몇 KB 라 통째로 넘긴다 — 클라이언트 컴포넌트마다 props 로 내려보내지 않는다 */
  dict: Dictionary;
};

// 레이아웃 밖(단독 테스트 렌더)에서는 국문 · 접두어 없는 기본값이다
const LocaleContext = createContext<LocaleContextValue>({ locale: 'ko', prefix: '', dict: ko });

/** 클라이언트 컴포넌트가 지금 언어 · 링크 접두어 · 문구를 알게 한다. [locale] 레이아웃이 감싼다 */
export function LocaleProvider({
  locale,
  prefix,
  dict,
  children,
}: LocaleContextValue & { children: ReactNode }) {
  return <LocaleContext value={{ locale, prefix, dict }}>{children}</LocaleContext>;
}

export function useDictionary(): Dictionary {
  return useContext(LocaleContext).dict;
}

/** 지금 언어로 금액을 표시하는 함수 — 영문 스토어의 KRW 는 '₩129,000' */
export function useFormatMoney(): (money: Money) => string {
  const { locale } = useContext(LocaleContext);
  return (money) => formatMoney(money, locale);
}

export function useLocale(): Locale {
  return useContext(LocaleContext).locale;
}

/** 앱 경로를 지금 언어의 주소로 바꾸는 함수 */
export function useLocalizedHref(): (path: string) => string {
  const { prefix } = useContext(LocaleContext);
  return (path) => withLocalePrefix(prefix, path);
}
