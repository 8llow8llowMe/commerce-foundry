import type { Locale } from '@cf/ui';

// 스토어 언어 라우팅. 앱 안의 라우트는 app/[locale]/... 하나이고, 바깥 URL 은 두 방식 중 하나로 언어를 고른다.
// - 경로: 기본 언어는 접두어 없이(/shop), 나머지는 접두어(/en/shop)
// - 호스트: LOCALE_HOSTS 에 적은 호스트로 오면 그 언어(en.example.com/shop)
// next 에 기대지 않는 순수 함수다 — proxy · 서버 컴포넌트 · 클라이언트가 함께 쓴다.

export type LocaleRouting = {
  defaultLocale: Locale;
  locales: readonly Locale[];
  /** 언어 → 전용 호스트(포트 없이). 없는 언어는 경로 방식이다 */
  hosts: Partial<Record<Locale, string>>;
};

export type RouteDecision =
  /** 앱 안의 /[locale]/... 로 바꿔 그린다(주소창은 그대로) */
  | { type: 'rewrite'; pathname: string }
  /** 정식 주소로 보낸다. url 이 '/' 로 시작하면 같은 호스트다 */
  | { type: 'redirect'; url: string };

const hostname = (host: string | null) => (host ?? '').split(':')[0]!.toLowerCase();

/** 'en=en.example.com,ko=example.com' → { en: 'en.example.com', ko: 'example.com' }. 모르는 언어 · 빈 값은 버린다 */
export function parseLocaleHosts(
  value: string | undefined,
  locales: readonly Locale[],
): LocaleRouting['hosts'] {
  const hosts: LocaleRouting['hosts'] = {};
  for (const pair of (value ?? '').split(',')) {
    const [key, host] = pair.split('=').map((part) => part.trim());
    if (key && host && (locales as readonly string[]).includes(key)) {
      hosts[key as Locale] = hostname(host);
    }
  }
  return hosts;
}

/** 경로의 첫 마디가 언어 접두어면 그 언어와 나머지 경로 */
function splitPrefix(routing: LocaleRouting, pathname: string) {
  const [, first, ...rest] = pathname.split('/');
  const locale = routing.locales.find((candidate) => candidate === first);
  return locale ? { locale, rest: `/${rest.join('/')}` } : null;
}

/** 이 언어의 주소 앞에 붙는 경로 접두어. 기본 언어 · 전용 호스트 언어는 없다 */
export function localePrefix(routing: LocaleRouting, locale: Locale): string {
  return locale === routing.defaultLocale || routing.hosts[locale] ? '' : `/${locale}`;
}

/** 앱 경로('/shop?x=1') 앞에 접두어를 붙인다. 외부 · 프로토콜 상대 주소는 그대로 */
export function withLocalePrefix(prefix: string, path: string): string {
  if (!prefix || !path.startsWith('/') || path.startsWith('//')) return path;
  return path === '/' ? prefix : `${prefix}${path}`;
}

/** 앱 경로를 이 언어의 같은 호스트 주소로 */
export function localizePath(routing: LocaleRouting, locale: Locale, path: string): string {
  return withLocalePrefix(localePrefix(routing, locale), path);
}

/**
 * 요청 하나를 앱 경로로. 정식 주소가 아니면(기본 언어 접두어 /ko/..., 전용 호스트가 있는 언어를 경로로) 보낸다.
 * 접두어가 없는 경로는 호스트의 언어, 호스트가 없으면 기본 언어다.
 */
export function routeRequest(
  routing: LocaleRouting,
  host: string | null,
  pathname: string,
  search = '',
): RouteDecision {
  const hostLocale = routing.locales.find((locale) => routing.hosts[locale] === hostname(host));
  const prefixed = splitPrefix(routing, pathname);

  if (prefixed) {
    const { locale, rest } = prefixed;
    const current = hostname(host);
    // 전용 호스트가 있는 언어를 다른 호스트의 경로로 왔으면 그 호스트로
    const dedicated = routing.hosts[locale];
    if (dedicated && dedicated !== current) {
      return { type: 'redirect', url: `https://${dedicated}${rest}${search}` };
    }
    // 접두어가 필요 없는 주소(언어 호스트에 온 /en/..., 기본 호스트의 /ko/...)는 접두어를 뗀다
    if (locale === hostLocale || (!hostLocale && locale === routing.defaultLocale)) {
      return { type: 'redirect', url: `${rest}${search}` };
    }
    return { type: 'rewrite', pathname };
  }

  const locale = hostLocale ?? routing.defaultLocale;
  return { type: 'rewrite', pathname: pathname === '/' ? `/${locale}` : `/${locale}${pathname}` };
}

/**
 * hreflang 용 절대 주소. 전용 호스트 언어는 그 호스트, 나머지는 origin(기본 호스트) + 접두어.
 * origin 은 'https://example.com' 처럼 끝 슬래시 없이 준다.
 */
export function localeUrl(
  routing: LocaleRouting,
  locale: Locale,
  path: string,
  origin: string,
): string {
  const host = routing.hosts[locale];
  return host ? `https://${host}${path}` : `${origin}${localizePath(routing, locale, path)}`;
}
