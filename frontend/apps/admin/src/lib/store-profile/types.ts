/**
 * 쇼핑몰 공개 정보(#566) — operations-service `GET · PUT /api/v1/admin/store/profile`.
 * 정본은 `backend/docs/api-design-guide.md` 쇼핑몰 공개 정보 절. 저장 즉시 스토어프론트 푸터에 공개된다.
 */
export const STORE_PROFILE_PATH = '/api/v1/admin/store/profile';
/** 아직 등록 전 — 빈 폼으로 처음 만든다 */
export const NOT_CONFIGURED = 'STORE_PROFILE_001';
/** 조회한 version 뒤에 다른 곳에서 먼저 저장했다(중복 최초 등록 · 낙관 락 경합 포함) */
export const VERSION_CONFLICT = 'STORE_PROFILE_020';

/** 공개 표시 필드 10개. 선택 필드는 null */
export type StoreProfileFields = {
  storeName: string;
  companyName: string;
  representativeName: string;
  countryCode: string;
  businessRegistrationNumber: string;
  mailOrderRegistrationNumber: string | null;
  address: string;
  customerServiceEmail: string;
  customerServicePhone: string | null;
  customerServiceHours: string | null;
};

/** ADMIN 응답 — 공개 필드 + 낙관적 편집 version(화면에 보이지 않는다) */
export type StoreProfile = StoreProfileFields & { version: number };

/** PUT 본문 — 전체 교체. 최초 생성은 expectedVersion null */
export type StoreProfileRequest = StoreProfileFields & { expectedVersion: number | null };

export type StoreProfileField = keyof StoreProfileFields;

/** 칸 이름 · 최대 글자 수(BE `StoreProfileSaveRequest` 와 같다) · 필수 여부. 화면 순서대로 */
export const FIELDS: { key: StoreProfileField; label: string; max: number; required: boolean }[] = [
  { key: 'storeName', label: '쇼핑몰 이름', max: 100, required: true },
  { key: 'companyName', label: '상호', max: 200, required: true },
  { key: 'representativeName', label: '대표자', max: 100, required: true },
  { key: 'countryCode', label: '국가 코드', max: 2, required: true },
  { key: 'businessRegistrationNumber', label: '사업자 식별 번호', max: 100, required: true },
  { key: 'mailOrderRegistrationNumber', label: '통신판매 신고 번호', max: 100, required: false },
  { key: 'address', label: '공개 주소', max: 500, required: true },
  { key: 'customerServiceEmail', label: '고객센터 이메일', max: 254, required: true },
  { key: 'customerServicePhone', label: '고객센터 전화', max: 50, required: false },
  { key: 'customerServiceHours', label: '고객센터 운영 시간', max: 500, required: false },
];
