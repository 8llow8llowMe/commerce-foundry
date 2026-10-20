import type { Locale } from '@cf/ui';
import { DOMESTIC_COUNTRY } from './address-form';

export type CountryOption = { code: string; name: string };

/** 언어별 새 배송지의 기본 국가. 선택지에 없으면 첫 선택지(국내)다 */
const DEFAULT_COUNTRY: Record<Locale, string> = { ko: DOMESTIC_COUNTRY, en: 'US' };

/**
 * 배송지 국가 선택지. 국내는 언제나 맨 앞이고, 나머지는 그 언어의 국가 이름 순이다.
 * `codes` 는 해외 배송 정책의 국가(배송할 수 있는 곳)와 이미 저장된 주소의 국가 — 정책에서 빠진 국가의 옛 주소도 고칠 수 있게 남긴다.
 */
export function countryOptions(codes: Iterable<string>, locale: Locale): CountryOption[] {
  const names = new Intl.DisplayNames([locale], { type: 'region' });
  const nameOf = (code: string) => {
    try {
      return names.of(code) ?? code;
    } catch {
      return code;
    }
  };
  const others = new Set<string>();
  for (const code of codes) {
    if (/^[A-Z]{2}$/.test(code) && code !== DOMESTIC_COUNTRY) others.add(code);
  }
  return [
    { code: DOMESTIC_COUNTRY, name: nameOf(DOMESTIC_COUNTRY) },
    ...[...others]
      .map((code) => ({ code, name: nameOf(code) }))
      .sort((a, b) => a.name.localeCompare(b.name, locale)),
  ];
}

export function defaultCountry(options: readonly CountryOption[], locale: Locale): string {
  const preferred = DEFAULT_COUNTRY[locale];
  return options.some((option) => option.code === preferred)
    ? preferred
    : (options[0]?.code ?? DOMESTIC_COUNTRY);
}

/**
 * 해외 배송 정책(`GET /api/v1/shipping-fees/international-policy`)의 국가 코드. 해외 배송을 끄면 dataBody 가 null 이라 빈 목록이다.
 * 모양이 다르면 빈 목록 — 국내 배송지는 그대로 쓸 수 있다.
 */
export function policyCountryCodes(policy: unknown): string[] {
  if (typeof policy !== 'object' || policy === null) return [];
  const zones = (policy as { zones?: unknown }).zones;
  if (!Array.isArray(zones)) return [];
  return zones.flatMap((zone: unknown) => {
    const codes = (zone as { countryCodes?: unknown } | null)?.countryCodes;
    return Array.isArray(codes) ? codes.filter((code) => typeof code === 'string') : [];
  });
}
