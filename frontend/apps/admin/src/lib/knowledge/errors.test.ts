import { ApiError, type ApiErrorKind, type FieldErrorItem } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { SAVE_FAILED, SESSION_EXPIRED_ON_SAVE } from '@/lib/api-errors';
import {
  CHANGED_NOTICE,
  CHECK_FIELDS,
  CREATE_UNKNOWN_OUTCOME,
  createFailure,
  TRANSITION_NOTICE,
  UNKNOWN_OUTCOME_NOTICE,
  writeFailure,
} from './errors';
import { CATEGORY_REQUIRED, PERIOD_INVERTED } from './form';

const apiError = (
  kind: ApiErrorKind,
  status: number | null,
  code: string | null,
  fieldErrors: FieldErrorItem[] = [],
) => new ApiError(kind, status, code, '서버 원문', fieldErrors);

describe('writeFailure', () => {
  it('401 은 입력을 지키는 폼 오류, 403 은 권한 안내다', () => {
    expect(writeFailure(apiError('unauthorized', 401, null))).toEqual({
      kind: 'errors',
      errors: { form: [SESSION_EXPIRED_ON_SAVE] },
    });
    expect(writeFailure(apiError('forbidden', 403, null))).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
  });

  it('버전 경합 · 상태 전이 거절 · 결과 모름은 다시 읽는다', () => {
    expect(writeFailure(apiError('server', 409, 'SUPPORT_004'))).toEqual({
      kind: 'reload',
      notice: CHANGED_NOTICE,
    });
    expect(writeFailure(apiError('server', 409, 'SUPPORT_003'))).toEqual({
      kind: 'reload',
      notice: TRANSITION_NOTICE,
    });
    expect(writeFailure(apiError('unknownOutcome', 504, null))).toEqual({
      kind: 'reload',
      notice: UNKNOWN_OUTCOME_NOTICE,
    });
    expect(writeFailure(apiError('conflict', 409, null))).toEqual({
      kind: 'reload',
      notice: CHANGED_NOTICE,
    });
  });

  it('없는 문서', () => {
    expect(writeFailure(apiError('notFound', 404, 'SUPPORT_001'))).toEqual({ kind: 'gone' });
  });

  it('필드 오류는 코드로 칸을 찾고 요약을 둔다. 모르는 코드는 요약만', () => {
    const result = writeFailure(
      apiError('validation', 400, 'SUPPORT_100', [
        { field: 'content.category', code: 'SUPPORT_106', message: '원문' },
        { field: 'content.periodValid', code: 'SUPPORT_108', message: '원문' },
        { field: 'expectedVersion', code: 'SUPPORT_110', message: '원문' },
      ]),
    );
    expect(result).toEqual({
      kind: 'errors',
      errors: {
        category: [CATEGORY_REQUIRED],
        validTo: [PERIOD_INVERTED],
        form: [CHECK_FIELDS],
      },
    });
  });

  it('요청 제한은 그 문구, 그 밖은 일반 실패다(서버 원문을 보이지 않는다)', () => {
    const limited = new ApiError('rateLimited', 429, 'GATEWAY_006', '요청이 너무 많습니다.', []);
    expect(writeFailure(limited)).toEqual({
      kind: 'errors',
      errors: { form: ['요청이 너무 많습니다.'] },
    });
    expect(writeFailure(apiError('server', 500, 'X'))).toEqual({
      kind: 'errors',
      errors: { form: [SAVE_FAILED] },
    });
    expect(writeFailure(new Error('x'))).toEqual({
      kind: 'errors',
      errors: { form: [SAVE_FAILED] },
    });
  });
});

describe('createFailure', () => {
  it('결과 모름은 다시 보내지 말고 목록에서 확인하라고 한다', () => {
    expect(createFailure(apiError('unknownOutcome', 502, null))).toEqual({
      kind: 'errors',
      errors: { form: [CREATE_UNKNOWN_OUTCOME] },
    });
  });

  it('다시 읽을 대상이 없는 실패는 일반 실패다', () => {
    expect(createFailure(apiError('server', 409, 'SUPPORT_004'))).toEqual({
      kind: 'errors',
      errors: { form: [SAVE_FAILED] },
    });
  });
});
