import { brand } from '@cf/brand';
import type { BrandContent, Locale } from '@cf/ui';
import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { locale as localeParam } from 'next/root-params';
import { hasLocale, localeRouting } from './config';
import { getDictionary, type Dictionary } from './dictionaries';
import { siteUrl } from '@/lib/env';
import { localeUrl, localizePath } from './routing';

/**
 * 지금 요청의 언어(app/[locale] 루트 파라미터). 서버 컴포넌트 · 서버 유틸에서만 쓴다
 * (root-params 는 클라이언트 · 라우트 핸들러에서 쓸 수 없다). 모르는 언어면 404.
 */
export async function getLocale(): Promise<Locale> {
  const value = await localeParam();
  if (!hasLocale(value)) notFound();
  return value;
}

/** 지금 언어의 앱 문구 · 브랜드 문구 · 경로 변환을 한 번에 */
export async function getI18n(): Promise<{
  locale: Locale;
  dict: Dictionary;
  content: BrandContent;
  href: (path: string) => string;
}> {
  const locale = await getLocale();
  return {
    locale,
    dict: getDictionary(locale),
    content: brand.content[locale],
    href: (path) => localizePath(localeRouting, locale, path),
  };
}

/** 언어 호스트를 쓰면 다른 언어는 다른 호스트라 절대 주소, 아니면 같은 호스트의 경로 */
function crossLocaleHref(locale: Locale, path: string): string {
  return Object.keys(localeRouting.hosts).length > 0
    ? localeUrl(localeRouting, locale, path, siteUrl)
    : localizePath(localeRouting, locale, path);
}

/** 언어 전환 링크 — 그 언어의 메인으로 보낸다 */
export function localeHomeHref(locale: Locale): string {
  return crossLocaleHref(locale, '/');
}

/**
 * 같은 화면의 언어별 주소(hreflang)와 정식 주소(canonical). path 는 언어 없는 앱 경로다.
 * x-default 는 기본 언어 주소다.
 */
export async function localeAlternates(path: string): Promise<Metadata['alternates']> {
  const locale = await getLocale();
  const url = (code: Locale) => localeUrl(localeRouting, code, path, siteUrl);
  if (localeRouting.locales.length < 2) return { canonical: url(locale) };
  return {
    canonical: url(locale),
    languages: {
      ...Object.fromEntries(localeRouting.locales.map((code) => [code, url(code)])),
      'x-default': url(localeRouting.defaultLocale),
    },
  };
}
