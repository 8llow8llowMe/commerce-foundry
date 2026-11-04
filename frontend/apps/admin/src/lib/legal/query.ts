import { LEGAL_LOCALES, LEGAL_TYPES, type LegalLocale, type LegalType } from './types';

export const LEGAL_HREF = '/store/legal-documents';

export type LegalQuery = { type: LegalType; locale: LegalLocale };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

/** 주소 → 편집할 문서. 허용 값만 받고 나머지는 이용약관 · 국문이다(손으로 고친 주소가 400 이 되지 않게) */
export function parseLegalQuery(params: SearchParams): LegalQuery {
  const type = first(params.type);
  const locale = first(params.locale);
  return {
    type: LEGAL_TYPES.find((item) => item === type) ?? 'TERMS',
    locale: LEGAL_LOCALES.find((item) => item === locale) ?? 'ko',
  };
}

export function legalHref({ type, locale }: LegalQuery): string {
  return `${LEGAL_HREF}?type=${type}&locale=${locale}`;
}
