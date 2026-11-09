/**
 * 쇼핑몰 설정 > 푸터(#600) — operations-service `StoreFooterAdminController`(BE #597).
 * 정본은 `backend/docs/store-footer-guide.md`. 모두 ADMIN 이고 응답은 `no-store` 다.
 * 국문 · 영문 문구를 한 리비전으로 함께 저장 · 게시한다. 저장(PUT)은 전체 교체이며 게시본을 바꾸지 않는다.
 */

export const STORE_FOOTER_PATH = '/api/v1/admin/store/footer';
export const STORE_FOOTER_PUBLISH_PATH = `${STORE_FOOTER_PATH}/publication`;

/** 대소문자 구분 제어 코드 */
export const FOOTER_LINK_TYPES = ['INSTAGRAM', 'KAKAO_CHANNEL', 'CUSTOM'] as const;
export type FooterLinkType = (typeof FOOTER_LINK_TYPES)[number];

export const TYPE_LABELS: Record<FooterLinkType, string> = {
  INSTAGRAM: 'Instagram',
  KAKAO_CHANNEL: '카카오톡 채널',
  CUSTOM: '기타 링크',
};

/** 입력 상한 — BE 검증과 같다(Java String 길이 = UTF-16 단위, JS `length` 와 같다) */
export const LIMITS = { links: 20, label: 80, copyright: 200, url: 2048, order: 999 } as const;

/** 아직 등록 전(관리자 조회 404) */
export const NOT_CONFIGURED = 'STORE_FOOTER_001';
/** 버전 · 최신 리비전 · 동시 생성/수정 충돌 */
export const VERSION_CONFLICT = 'STORE_FOOTER_020';

export type FooterText = { ko: string | null; en: string | null };

export type FooterLink = {
  type: FooterLinkType;
  labels: { ko: string; en: string };
  url: string;
  displayOrder: number;
  visible: boolean;
};

/** 불변 리비전. 관리자 식별자는 문자열, 시각은 UTC ISO */
export type FooterRevision = {
  revisionNumber: number;
  copyrightText: FooterText;
  /** 숨김 포함 전체 링크 */
  links: FooterLink[];
  createdBy: string;
  createdAt: string;
  publishedBy: string | null;
  publishedAt: string | null;
};

/** 상세 — revision 은 항상 **최신 저장본**이다(게시본이 아닐 수 있다) */
export type FooterDetail = {
  version: number;
  latestRevision: number;
  /** 지금 공개 중인 리비전. 게시한 적 없으면 null */
  publishedRevision: number | null;
  revision: FooterRevision;
};

/** PUT — 링크 목록 전체 교체. 처음이면 expectedVersion 은 null. `links: []` 는 유효한 빈 푸터 */
export type FooterDraftRequest = {
  expectedVersion: number | null;
  copyrightText: FooterText;
  links: FooterLink[];
};

/** POST `/publication` — 최신 리비전만 게시할 수 있다 */
export type FooterPublishRequest = { expectedVersion: number; revisionNumber: number };
