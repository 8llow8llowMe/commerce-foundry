import type { DataHeader, FieldErrorItem } from './envelope';

export type ApiErrorKind =
  | 'network'
  | 'invalidResponse'
  | 'validation'
  | 'unauthorized'
  | 'forbidden'
  | 'notFound'
  | 'conflict'
  | 'unknownOutcome'
  | 'unavailable'
  | 'server';

const DEFAULT_MESSAGES: Record<ApiErrorKind, string> = {
  network: '네트워크 연결을 확인해 주세요.',
  invalidResponse: '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
  validation: '입력값을 확인해 주세요.',
  unauthorized: '로그인이 필요합니다.',
  forbidden: '접근 권한이 없습니다.',
  notFound: '요청한 정보를 찾을 수 없습니다.',
  conflict: '요청을 처리하고 있습니다. 잠시 후 결과를 확인해 주세요.',
  unknownOutcome: '처리 결과를 확인하지 못했습니다. 잠시 후 결과를 확인해 주세요.',
  unavailable: '서비스가 잠시 응답하지 않습니다. 잠시 후 다시 시도해 주세요.',
  server: '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
};

export class ApiError extends Error {
  override readonly name = 'ApiError';
  readonly kind: ApiErrorKind;
  readonly status: number | null;
  readonly code: string | null;
  readonly fieldErrors: FieldErrorItem[];

  constructor(
    kind: ApiErrorKind,
    status: number | null,
    code: string | null,
    message: string | null,
    fieldErrors: FieldErrorItem[] = [],
  ) {
    super(message ?? DEFAULT_MESSAGES[kind]);
    this.kind = kind;
    this.status = status;
    this.code = code;
    this.fieldErrors = fieldErrors;
  }
}

/** 실패 응답의 kind 를 정한다. 판정 순서는 frontend/docs/api-client.md 표와 같다. */
export function classifyFailure(status: number, header: DataHeader): ApiErrorKind {
  if (header.fieldErrors && header.fieldErrors.length > 0) return 'validation';
  switch (status) {
    case 401:
      return 'unauthorized';
    case 403:
      return 'forbidden';
    case 404:
      return 'notFound';
    case 409:
      return 'conflict';
    case 502:
    case 504:
      return 'unknownOutcome';
    case 503:
      return 'unavailable';
    default:
      return 'server';
  }
}

export function messagesFor(error: ApiError, field: string): string[] {
  return error.fieldErrors.filter((item) => item.field === field).map((item) => item.message);
}
