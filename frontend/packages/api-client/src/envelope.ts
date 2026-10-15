// 백엔드 응답 봉투 계약. 정본은 backend/docs/api-design-guide.md §2 · §4 다.

export type FieldErrorItem = {
  code: string;
  field: string;
  message: string;
};

export type DataHeader = {
  success: boolean;
  resultCode: string | null;
  resultMessage: string | null;
  fieldErrors: FieldErrorItem[] | null;
};

export type ApiEnvelope<T> = {
  dataHeader: DataHeader;
  dataBody: T | null;
};

/** 스토어프론트 목록(무한 스크롤) */
export type SliceResponse<T> = {
  contents: T[];
  hasNext: boolean;
};

/** 어드민 목록(페이지 번호). page 는 0 부터 센다. */
export type PageResponse<T> = {
  contents: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export function isApiEnvelope(value: unknown): value is ApiEnvelope<unknown> {
  if (typeof value !== 'object' || value === null) return false;
  const header = (value as { dataHeader?: unknown }).dataHeader;
  return (
    typeof header === 'object' &&
    header !== null &&
    typeof (header as { success?: unknown }).success === 'boolean'
  );
}

export function errorEnvelope(resultCode: string, resultMessage: string): ApiEnvelope<null> {
  return {
    dataHeader: { success: false, resultCode, resultMessage, fieldErrors: null },
    dataBody: null,
  };
}
