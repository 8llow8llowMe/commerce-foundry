import { ApiError } from '@cf/api-client';
import type { FooterChannelType, Locale } from '@cf/ui';
import { unstable_rethrow } from 'next/navigation';
import { cache } from 'react';
import { serverFetch } from '@/lib/server-api';

/**
 * 게시한 푸터 채널 · 링크 · 저작권 문구(#600 · BE #597 `GET /api/v1/store/footer?locale=`).
 * 정본은 `backend/docs/store-footer-guide.md`. 요청 언어의 문구 · 노출 링크만 순서대로 온다(언어 대체 없음).
 * 회사 · 고객센터는 `store-profile.ts`(#566), 약관 링크는 브랜드 `footerLinks`(#569 공개 페이지)가 맡는다.
 */
export type StoreFooterLink = { type: FooterChannelType; label: string; url: string };
export type StoreFooterContent = { copyrightText: string | null; links: StoreFooterLink[] };

/**
 * - published: 게시본
 * - unpublished: 미등록 · 미게시(404 `STORE_FOOTER_001`)
 * - unavailable: 장애 · 시간 초과 · 모양 불일치
 * 게시본이 아니면 채널 · 저작권 부분만 숨기고 나머지 푸터는 그대로 둔다(초안을 대신 보이지 않는다).
 */
export type StoreFooterState =
  | { kind: 'published'; footer: StoreFooterContent }
  | { kind: 'unpublished' }
  | { kind: 'unavailable' };

export const STORE_FOOTER_PATH = '/api/v1/store/footer';
export const STORE_FOOTER_NOT_FOUND = 'STORE_FOOTER_001';
/** 푸터는 모든 화면에 붙는다 — 게이트웨이가 늦어도 화면 전체가 붙잡히지 않게 끊는다 */
export const STORE_FOOTER_TIMEOUT_MS = 3000;

const TYPES: readonly FooterChannelType[] = ['INSTAGRAM', 'KAKAO_CHANNEL', 'CUSTOM'];

/**
 * 렌더링 전 안전 URL 확인 — https · 공개 DNS 호스트 · 포트 · 계정 정보 · query · fragment 없음 · ASCII 만.
 * 서버가 저장 · 게시 때 같은 규칙(+유형별 호스트)으로 거르지만 화면도 한 번 더 본다. 맞지 않는 링크는 그리지 않는다.
 */
export function safeFooterUrl(url: unknown): string | null {
  if (typeof url !== 'string' || url.length > 2048 || /[^\x21-\x7e]|\\/.test(url)) return null;
  const match =
    /^https:\/\/([A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+)(\/[A-Za-z0-9/._~!$&'()*+,;=:@%-]*)?$/i.exec(
      url,
    );
  if (!match) return null;
  const tld = (match[1] ?? '').split('.').at(-1) ?? '';
  if (!/^[a-z]{2,63}$/i.test(tld) || /^(localhost|local|internal|test|invalid|onion)$/i.test(tld))
    return null;
  if ((match[2] ?? '').split('/').some((segment) => segment === '.' || segment === '..'))
    return null;
  return url;
}

const text = (value: unknown): string | null =>
  typeof value === 'string' && value.trim() !== '' ? value.trim() : null;

/**
 * 응답 data → 공개 푸터. 봉투 모양이 다르면 null(장애). 링크 하나가 틀리면(모르는 유형 · 빈 표시명 · 안전하지 않은 주소)
 * 그 링크만 뺀다 — 나머지 채널은 보인다.
 */
export function readStoreFooter(body: unknown): StoreFooterContent | null {
  if (typeof body !== 'object' || body === null) return null;
  const record = body as Record<string, unknown>;
  if (!Array.isArray(record.links)) return null;
  if (record.copyrightText != null && typeof record.copyrightText !== 'string') return null;
  const links: StoreFooterLink[] = [];
  for (const item of record.links as unknown[]) {
    if (typeof item !== 'object' || item === null) continue;
    const link = item as Record<string, unknown>;
    const type = TYPES.find((candidate) => candidate === link.type);
    const label = text(link.label);
    const url = safeFooterUrl(link.url);
    if (type && label && url) links.push({ type, label, url });
  }
  return { copyrightText: text(record.copyrightText), links };
}

/** 조회 실패를 상태로 — 미등록 · 미게시 404 만 unpublished, 나머지는 unavailable */
export function storeFooterFailure(cause: unknown): StoreFooterState {
  return cause instanceof ApiError &&
    cause.kind === 'notFound' &&
    cause.code === STORE_FOOTER_NOT_FOUND
    ? { kind: 'unpublished' }
    : { kind: 'unavailable' };
}

/**
 * 지금 요청 언어의 게시 푸터. 공개 · 사용자 무관이라 토큰 없이 부른다.
 * 캐시하지 않는다(BE 계약 no-store) — 게시 직후 다음 요청부터 반영되고, 초안이 캐시에 남지 않는다.
 */
export const loadStoreFooter = cache(async (locale: Locale): Promise<StoreFooterState> => {
  try {
    const body = await serverFetch<unknown>(
      `${STORE_FOOTER_PATH}?locale=${locale}`,
      { cache: 'no-store', signal: AbortSignal.timeout(STORE_FOOTER_TIMEOUT_MS) },
      { anonymous: true },
    );
    const footer = readStoreFooter(body);
    return footer ? { kind: 'published', footer } : { kind: 'unavailable' };
  } catch (cause) {
    unstable_rethrow(cause);
    return storeFooterFailure(cause);
  }
});
