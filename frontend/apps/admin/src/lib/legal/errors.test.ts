import { ApiError, type ApiErrorKind, type FieldErrorItem } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { SAVE_FAILED, SESSION_EXPIRED_ON_SAVE } from '@/lib/api-errors';
import {
  CHECK_FIELDS,
  CONFLICT_NOTICE,
  REVIEW_REQUIRED,
  UNKNOWN_OUTCOME_NOTICE,
  writeFailure,
} from './errors';
import { BODY_INVALID, PLACEHOLDER_LEFT, TITLE_INVALID } from './form';

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

  it('409 LEGAL_DOCUMENT_020 · 모르는 409 는 경합 — 다시 읽고 비교한다', () => {
    const expected = { kind: 'reload', notice: CONFLICT_NOTICE, conflict: true };
    expect(writeFailure(apiError('server', 409, 'LEGAL_DOCUMENT_020'))).toEqual(expected);
    expect(writeFailure(apiError('conflict', 409, 'OTHER_1'))).toEqual(expected);
    expect(writeFailure(apiError('server', 400, 'LEGAL_DOCUMENT_101'))).toEqual(expected);
  });

  it('결과 모름(502 · 504)은 다시 보내지 않고 다시 읽는다', () => {
    expect(writeFailure(apiError('unknownOutcome', 504, null))).toEqual({
      kind: 'reload',
      notice: UNKNOWN_OUTCOME_NOTICE,
      conflict: false,
    });
  });

  it('게시 검토 확인 누락 · 빈칸 남음은 업무 문구', () => {
    expect(writeFailure(apiError('server', 400, 'LEGAL_DOCUMENT_021'))).toEqual({
      kind: 'errors',
      errors: { review: [REVIEW_REQUIRED] },
    });
    expect(writeFailure(apiError('server', 400, 'LEGAL_DOCUMENT_022'))).toEqual({
      kind: 'errors',
      errors: { form: [PLACEHOLDER_LEFT] },
    });
  });

  it('필드 오류는 코드로 칸을 찾고 요약 문구를 둔다', () => {
    const result = writeFailure(
      apiError('validation', 400, 'LEGAL_DOCUMENT_102', [
        { code: 'LEGAL_DOCUMENT_102', field: 'title', message: '원문' },
        { code: 'LEGAL_DOCUMENT_103', field: 'body', message: '원문' },
        { code: 'LEGAL_DOCUMENT_999', field: 'x', message: '원문' },
      ]),
    );
    expect(result).toEqual({
      kind: 'errors',
      errors: { title: [TITLE_INVALID], body: [BODY_INVALID], form: [CHECK_FIELDS] },
    });
  });

  it('그 밖은 저장 실패, 429 는 서버 문구', () => {
    expect(writeFailure(apiError('server', 500, null))).toEqual({
      kind: 'errors',
      errors: { form: [SAVE_FAILED] },
    });
    expect(writeFailure(new Error('x'))).toEqual({
      kind: 'errors',
      errors: { form: [SAVE_FAILED] },
    });
    expect(writeFailure(apiError('rateLimited', 429, 'GATEWAY_006'))).toEqual({
      kind: 'errors',
      errors: { form: ['서버 원문'] },
    });
  });
});
