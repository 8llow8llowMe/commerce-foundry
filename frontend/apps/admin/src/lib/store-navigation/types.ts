/**
 * 쇼핑몰 설정 > 메뉴(#602) — operations-service `StoreNavigationAdminController`(BE #599).
 * 정본은 `backend/docs/store-navigation-guide.md`. 조회는 `SETTINGS_READ`, 저장 · 게시는 `SETTINGS_MANAGE` 이고 응답은 `no-store` 다.
 * 국문 · 영문 표시명과 설명을 한 리비전으로 함께 저장 · 게시한다. 저장(PUT)은 평평한 목록 전체 교체이며 게시본을 바꾸지 않는다.
 */

export const STORE_NAVIGATION_PATH = '/api/v1/admin/store/navigation';
export const STORE_NAVIGATION_PUBLISH_PATH = `${STORE_NAVIGATION_PATH}/publication`;

/** 대소문자 구분 제어 코드 */
export const NAV_TYPES = ['INTERNAL', 'CATEGORY', 'EXTERNAL', 'NONE'] as const;
export type NavType = (typeof NAV_TYPES)[number];

/** 운영자 말 — 화면에는 API 이름 대신 이 이름을 쓴다 */
export const TYPE_LABELS: Record<NavType, string> = {
  INTERNAL: '쇼핑몰 안 페이지',
  CATEGORY: '카테고리',
  EXTERNAL: '다른 사이트',
  NONE: '링크 없는 묶음 제목',
};

/**
 * 입력 상한 — BE 검증과 같다(Java String 길이 = UTF-16 단위, JS `length` 와 같다).
 * depth 는 단계 수(최상위가 1단계), siblings 는 한 상위 항목 아래 개수, items 는 숨김 포함 전체.
 */
export const LIMITS = {
  items: 60,
  depth: 3,
  siblings: 20,
  label: 40,
  description: 80,
  path: 512,
  url: 2048,
} as const;

/** 아직 등록 전(관리자 조회 404) */
export const NOT_CONFIGURED = 'STORE_NAV_001';
/** 버전 · 최신 리비전 · 동시 생성/수정 충돌 */
export const VERSION_CONFLICT = 'STORE_NAV_020';

export type NavText = { ko: string; en: string };

/** 저장 본문 · 상세 응답의 항목 한 개. `key` 는 리비전 안에서만 쓰는 편집용 식별자다(DB 행 아이디가 아니다) */
export type NavItemPayload = {
  key: string;
  parentKey: string | null;
  type: NavType;
  labels: NavText;
  /** 없으면 null. 있으면 두 언어 모두 */
  descriptions: NavText | null;
  /** INTERNAL 은 `/` 로 시작하는 경로, EXTERNAL 은 https 주소. CATEGORY · NONE 은 null */
  url: string | null;
  /** CATEGORY 만. 문자열이다 */
  categoryId: string | null;
  displayOrder: number;
  visible: boolean;
  /** PC 1차 메뉴 — 최상위만 */
  primary: boolean;
  /** 모바일 바로가기 — 최상위만 */
  shortcut: boolean;
};

/** 불변 리비전. 관리자 식별자는 문자열, 시각은 UTC ISO. items 는 숨김 포함 트리 전위 순서다 */
export type NavRevision = {
  revisionNumber: number;
  items: NavItemPayload[];
  createdBy: string;
  createdAt: string;
  publishedBy: string | null;
  publishedAt: string | null;
};

/** 상세 — revision 은 항상 **최신 저장본**이다(게시본이 아닐 수 있다) */
export type NavDetail = {
  version: number;
  latestRevision: number;
  /** 지금 공개 중인 리비전. 게시한 적 없으면 null */
  publishedRevision: number | null;
  revision: NavRevision;
};

/** PUT — 목록 전체 교체. 처음이면 expectedVersion 은 null. `items: []` 는 유효한 빈 메뉴 */
export type NavDraftRequest = { expectedVersion: number | null; items: NavItemPayload[] };

/** POST `/publication` — 최신 리비전만 게시할 수 있다 */
export type NavPublishRequest = { expectedVersion: number; revisionNumber: number };
