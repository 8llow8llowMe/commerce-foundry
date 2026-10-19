import { brand } from '@cf/brand';
import type { Locale } from '@cf/ui';
import { localeHosts } from '@/lib/env';
import { parseLocaleHosts, type LocaleRouting } from './routing';

/** 이 스토어의 언어 라우팅 — 언어 목록은 브랜드 설정, 전용 호스트는 배포 env(LOCALE_HOSTS) */
export const localeRouting: LocaleRouting = {
  defaultLocale: brand.defaultLocale,
  locales: brand.locales,
  hosts: parseLocaleHosts(localeHosts, brand.locales),
};

export function hasLocale(value: string): value is Locale {
  return (localeRouting.locales as readonly string[]).includes(value);
}
