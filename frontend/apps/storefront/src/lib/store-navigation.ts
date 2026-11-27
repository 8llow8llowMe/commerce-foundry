import { ApiError } from '@cf/api-client';
import type { Locale, NavItem, NavLink } from '@cf/ui';
import { unstable_rethrow } from 'next/navigation';
import { cache } from 'react';
import { shopHref } from '@/lib/catalog/shop-query';
import { serverFetch } from '@/lib/server-api';
import { safeFooterUrl } from '@/lib/store-footer';

/**
 * 게시한 헤더 메뉴(#602 · BE #599 `GET /api/v1/store/navigation?locale=`).
 * 정본은 `backend/docs/store-navigation-guide.md`. 요청 언어의 표시명 · 설명과 노출 항목만 트리로 온다(언어 대체 없음).
 * 숨김 항목은 하위째 서버가 뺀다. 게시본이 없거나 읽지 못하면 브랜드 설정 메뉴(`brand.content[locale].navigation`)를 쓴다.
 */

/**
 * - published: 게시본(빈 메뉴도 게시본이다 — 운영자가 비워 게시했다)
 * - unpublished: 미등록 · 미게시(404 `STORE_NAV_001`)
 * - unavailable: 장애 · 시간 초과 · 모양 불일치
 * 게시본이 아니면 브랜드 설정 메뉴로 그린다(초안을 대신 보이지 않는다).
 */
export type StoreNavigationState =
  { kind: 'published'; items: NavItem[] } | { kind: 'unpublished' } | { kind: 'unavailable' };

export const STORE_NAVIGATION_PATH = '/api/v1/store/navigation';
export const STORE_NAVIGATION_NOT_FOUND = 'STORE_NAV_001';
/** 헤더는 모든 화면에 붙는다 — 게이트웨이가 늦어도 화면 전체가 붙잡히지 않게 끊는다(푸터와 같다) */
export const STORE_NAVIGATION_TIMEOUT_MS = 3000;
/** 서버 상한(3단계)보다 깊은 노드는 그리지 않는다 */
const MAX_DEPTH = 3;

/**
 * 쇼핑몰 안 경로 — `/` 로 시작하고 영문 · 숫자 · `- . _ ~ /` 만, `.` · `..` 구간 없음(BE `StoreNavigationUrlPolicy` 와 같은 문자 집합).
 * `//` 는 위치와 관계없이 막는다 — 서버보다 엄격할 수 있다(서버가 받은 값이라도 화면은 그 항목을 그리지 않는다).
 * 서버가 저장 · 게시 때 거르지만 화면도 한 번 더 본다. 맞지 않으면 그 항목을 그리지 않는다.
 */
export function safeInternalPath(url: unknown): string | null {
  if (typeof url !== 'string' || url.length === 0 || url.length > 512) return null;
  if (!/^\/[A-Za-z0-9/._~-]*$/.test(url) || url.includes('//')) return null;
  if (
    url
      .slice(1)
      .split('/')
      .some((segment) => segment === '.' || segment === '..')
  )
    return null;
  return url;
}

const text = (value: unknown): string | null =>
  typeof value === 'string' && value.trim() !== '' ? value.trim() : null;

type Target = { href: string | null; external: boolean };

/** 유형별 링크. 모르는 유형 · 틀린 대상은 null(항목을 그리지 않는다) */
function targetOf(record: Record<string, unknown>): Target | null {
  switch (record.type) {
    case 'INTERNAL': {
      const href = safeInternalPath(record.url);
      return href ? { href, external: false } : null;
    }
    case 'EXTERNAL': {
      // 서버는 허용 호스트의 https 만 저장한다 — 화면은 https · 공개 호스트 · `%` 없음만 다시 본다
      const href =
        typeof record.url === 'string' && !record.url.includes('%')
          ? safeFooterUrl(record.url)
          : null;
      return href ? { href, external: true } : null;
    }
    case 'CATEGORY': {
      const id = typeof record.categoryId === 'string' ? record.categoryId : null;
      return id && /^[1-9]\d{0,18}$/.test(id)
        ? { href: shopHref({ categoryId: id, sort: 'NEWEST' }), external: false }
        : null;
    }
    case 'NONE':
      return { href: null, external: false };
    default:
      return null;
  }
}

function readLinks(value: unknown, depth: number): NavLink[] {
  if (depth > MAX_DEPTH || !Array.isArray(value)) return [];
  const links: NavLink[] = [];
  for (const raw of value as unknown[]) {
    if (typeof raw !== 'object' || raw === null) continue;
    const record = raw as Record<string, unknown>;
    const label = text(record.label);
    const target = targetOf(record);
    if (!label || !target) continue;
    const children = readLinks(record.children, depth + 1);
    links.push({
      label,
      href: target.href,
      ...(target.external && { external: true }),
      ...(children.length > 0 && { children }),
    });
  }
  return links;
}

/**
 * 응답 data → 헤더 메뉴(`NavItem[]`). 봉투 모양이 다르면 null(장애). 항목 하나가 틀리면(모르는 유형 · 빈 표시명 · 안전하지 않은 주소)
 * 그 항목만 하위째 뺀다 — 나머지 메뉴는 보인다. 최상위의 설명이 없으면 빈 문자열이다(하위 설명은 헤더가 쓰지 않는다).
 */
export function readStoreNavigation(body: unknown): NavItem[] | null {
  if (typeof body !== 'object' || body === null) return null;
  const record = body as Record<string, unknown>;
  if (!Array.isArray(record.items)) return null;
  const items: NavItem[] = [];
  for (const raw of record.items as unknown[]) {
    if (typeof raw !== 'object' || raw === null) continue;
    const item = raw as Record<string, unknown>;
    const label = text(item.label);
    const target = targetOf(item);
    if (!label || !target) continue;
    const children = readLinks(item.children, 2);
    items.push({
      label,
      description: text(item.description) ?? '',
      href: target.href,
      ...(target.external && { external: true }),
      ...(item.primary === true && { primary: true }),
      ...(item.shortcut === true && { shortcut: true }),
      ...(children.length > 0 && { children }),
    });
  }
  return items;
}

/** 조회 실패를 상태로 — 미등록 · 미게시 404 만 unpublished, 나머지는 unavailable */
export function storeNavigationFailure(cause: unknown): StoreNavigationState {
  return cause instanceof ApiError &&
    cause.kind === 'notFound' &&
    cause.code === STORE_NAVIGATION_NOT_FOUND
    ? { kind: 'unpublished' }
    : { kind: 'unavailable' };
}

/** 헤더에 넘길 메뉴 — 게시본이면 그것, 아니면 브랜드 설정 메뉴 */
export function navigationFor(state: StoreNavigationState, fallback: NavItem[]): NavItem[] {
  return state.kind === 'published' ? state.items : fallback;
}

/**
 * 지금 요청 언어의 게시 메뉴. 공개 · 사용자 무관이라 토큰 없이 부른다.
 * 캐시하지 않는다(BE 계약 no-store) — 게시 직후 다음 요청부터 반영되고, 초안이 캐시에 남지 않는다.
 */
export const loadStoreNavigation = cache(async (locale: Locale): Promise<StoreNavigationState> => {
  try {
    const body = await serverFetch<unknown>(
      `${STORE_NAVIGATION_PATH}?locale=${locale}`,
      { cache: 'no-store', signal: AbortSignal.timeout(STORE_NAVIGATION_TIMEOUT_MS) },
      { anonymous: true },
    );
    const items = readStoreNavigation(body);
    return items ? { kind: 'published', items } : { kind: 'unavailable' };
  } catch (cause) {
    unstable_rethrow(cause);
    return storeNavigationFailure(cause);
  }
});
