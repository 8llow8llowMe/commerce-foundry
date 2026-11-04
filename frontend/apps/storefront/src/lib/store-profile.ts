import { ApiError } from '@cf/api-client';
import { unstable_rethrow } from 'next/navigation';
import { cache } from 'react';
import { serverFetch } from '@/lib/server-api';

/**
 * 쇼핑몰 공개 정보(#566) — 푸터 법정 표시 · 고객센터 · 표시 이름. 정본은 어드민이 저장하는 런타임 DB 다
 * (`backend/docs/api-design-guide.md` 쇼핑몰 공개 정보). 실정보 기본값을 번들 · env 에 두지 않는다.
 */
export type StoreProfile = {
  storeName: string;
  companyName: string;
  representativeName: string;
  /** ISO 3166-1 alpha-2 대문자. 등록번호 표기 · 확인 링크를 이것으로 고른다(`business-registry.ts`) */
  countryCode: string;
  businessRegistrationNumber: string;
  mailOrderRegistrationNumber: string | null;
  address: string;
  customerServiceEmail: string;
  customerServicePhone: string | null;
  customerServiceHours: string | null;
};

/**
 * - registered: 등록된 공개 정보
 * - unregistered: 아직 등록 전(404 `STORE_PROFILE_001`) — 사업자 정보 블록을 그리지 않는다
 * - unavailable: 장애 · 모양 불일치 — 지난 값이나 다른 브랜드 값을 대신 보이지 않고 안내만 둔다
 */
export type StoreProfileState =
  | { kind: 'registered'; profile: StoreProfile }
  | { kind: 'unregistered' }
  | { kind: 'unavailable' };

export const STORE_PROFILE_PATH = '/api/v1/store/profile';
export const STORE_PROFILE_NOT_CONFIGURED = 'STORE_PROFILE_001';
/** 푸터는 모든 화면에 붙는다 — 게이트웨이가 늦어도 화면 전체가 붙잡히지 않게 끊는다 */
export const STORE_PROFILE_TIMEOUT_MS = 3000;

const REQUIRED = [
  'storeName',
  'companyName',
  'representativeName',
  'countryCode',
  'businessRegistrationNumber',
  'address',
  'customerServiceEmail',
] as const;
const OPTIONAL = [
  'mailOrderRegistrationNumber',
  'customerServicePhone',
  'customerServiceHours',
] as const;

const text = (value: unknown): string | null =>
  typeof value === 'string' && value.trim() !== '' ? value.trim() : null;

/** 응답 data 를 공개 정보로. 필수 칸이 비었거나 모양이 다르면 null(장애로 다룬다). 빈 선택 칸은 null */
export function readStoreProfile(body: unknown): StoreProfile | null {
  if (typeof body !== 'object' || body === null) return null;
  const record = body as Record<string, unknown>;
  const profile: Record<string, string | null> = {};
  for (const key of REQUIRED) {
    const value = text(record[key]);
    if (value === null) return null;
    profile[key] = value;
  }
  for (const key of OPTIONAL) {
    if (record[key] != null && typeof record[key] !== 'string') return null;
    profile[key] = text(record[key]);
  }
  return profile as StoreProfile;
}

/** 조회 실패를 상태로 — 미등록 404 만 unregistered, 나머지(장애 · 시간 초과 · 봉투 아님)는 unavailable */
export function storeProfileFailure(cause: unknown): StoreProfileState {
  return cause instanceof ApiError &&
    cause.kind === 'notFound' &&
    cause.code === STORE_PROFILE_NOT_CONFIGURED
    ? { kind: 'unregistered' }
    : { kind: 'unavailable' };
}

/**
 * 지금 요청의 공개 정보 상태. 공개 · 사용자 무관이라 토큰 없이 부른다.
 * 캐시하지 않는다(BE 계약 no-store) — 저장 즉시 공개되고, 장애 때 지난 실정보가 무기한 남지 않는다.
 * 같은 요청 안의 레이아웃 · 메타데이터는 React `cache` 로 한 번만 부른다.
 */
export const loadStoreProfile = cache(async (): Promise<StoreProfileState> => {
  try {
    const body = await serverFetch<unknown>(
      STORE_PROFILE_PATH,
      { cache: 'no-store', signal: AbortSignal.timeout(STORE_PROFILE_TIMEOUT_MS) },
      { anonymous: true },
    );
    const profile = readStoreProfile(body);
    return profile ? { kind: 'registered', profile } : { kind: 'unavailable' };
  } catch (cause) {
    // 동적 렌더 전환 같은 Next 내부 신호는 삼키지 않는다
    unstable_rethrow(cause);
    return storeProfileFailure(cause);
  }
});
