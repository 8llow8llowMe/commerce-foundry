import { ApiError, type ApiErrorKind, type FieldErrorItem } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { SAVE_FAILED, SESSION_EXPIRED_ON_SAVE } from '@/lib/api-errors';
import {
  BUSY,
  CHECK_FIELDS,
  DELETE_FAILED,
  IMAGE_URL_NOT_ALLOWED,
  LOOK_GONE_NOTICE,
  LOOK_LIMIT,
  lookOrderFailure,
  lookRowFailure,
  lookSaveFailure,
  MEDIA_STORAGE_UNAVAILABLE,
  ORDER_CHANGED_NOTICE,
  PRODUCT_MISSING,
  TOGGLE_FAILED,
  UNKNOWN_OUTCOME_NOTICE,
} from './errors';
import { LOOK_TOO_LONG, PRODUCT_COUNT, PRODUCT_DUPLICATED, TITLE_REQUIRED } from './form';

const apiError = (
  kind: ApiErrorKind,
  status: number | null,
  code: string | null,
  message: string | null = null,
  fieldErrors: FieldErrorItem[] = [],
) => new ApiError(kind, status, code, message, fieldErrors);

const field = (code: string, path: string): FieldErrorItem => ({
  code,
  field: path,
  message: `${code}:${path} 원문`,
});

describe('lookSaveFailure', () => {
  it('401 은 폼을 지키고 다시 로그인을 안내한다, 403 은 권한 안내다', () => {
    expect(lookSaveFailure(apiError('unauthorized', 401, 'SECURITY_002'))).toEqual({
      kind: 'errors',
      errors: { form: [SESSION_EXPIRED_ON_SAVE] },
    });
    expect(lookSaveFailure(apiError('forbidden', 403, 'SECURITY_005'))).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
  });

  it('없는 룩(404) · 결과 모름은 목록을 다시 읽는다', () => {
    expect(lookSaveFailure(apiError('notFound', 404, 'LOOK_001'))).toEqual({
      kind: 'reload',
      notice: LOOK_GONE_NOTICE,
    });
    expect(lookSaveFailure(apiError('unknownOutcome', 504, null))).toEqual({
      kind: 'reload',
      notice: UNKNOWN_OUTCOME_NOTICE,
    });
  });

  it('칸 오류는 코드로 칸 · 업무 문구를 찾고, 모르는 코드는 경로로 칸을 찾는다(서버 원문을 쓰지 않는다)', () => {
    const failure = lookSaveFailure(
      apiError('validation', 400, 'PRODUCT_100', null, [
        field('LOOK_101', 'title'),
        field('LOOK_102', 'title'),
        field('LOOK_115', 'productIds'),
        field('LOOK_117', 'productIds[2]'),
        field('LOOK_109', 'image.width'),
      ]),
    );
    expect(failure).toEqual({
      kind: 'errors',
      errors: {
        title: [TITLE_REQUIRED, LOOK_TOO_LONG.title],
        products: [PRODUCT_COUNT, CHECK_FIELDS],
        imageUrl: [CHECK_FIELDS],
        form: [CHECK_FIELDS],
      },
    });
    expect(JSON.stringify(failure)).not.toContain('원문');
  });

  it('룩 규칙 오류는 그 칸 · 폼 전체에 업무 문구로 붙인다(없는 상품 아이디를 보이지 않는다)', () => {
    const errorsOf = (code: string, status = 400) =>
      lookSaveFailure(apiError('validation', status, code, `${code} 4611686018427387904`));
    expect(errorsOf('LOOK_003')).toEqual({
      kind: 'errors',
      errors: { products: [PRODUCT_DUPLICATED], form: [CHECK_FIELDS] },
    });
    expect(errorsOf('LOOK_004')).toEqual({
      kind: 'errors',
      errors: { products: [PRODUCT_MISSING], form: [CHECK_FIELDS] },
    });
    expect(errorsOf('LOOK_005')).toEqual({
      kind: 'errors',
      errors: { imageUrl: [IMAGE_URL_NOT_ALLOWED], form: [CHECK_FIELDS] },
    });
    expect(lookSaveFailure(apiError('unavailable', 503, 'LOOK_006'))).toEqual({
      kind: 'errors',
      errors: { form: [MEDIA_STORAGE_UNAVAILABLE] },
    });
  });

  it('409 는 룩 상한 · 잠금 대기다', () => {
    expect(lookSaveFailure(apiError('conflict', 409, 'LOOK_002'))).toEqual({
      kind: 'errors',
      errors: { form: [LOOK_LIMIT] },
    });
    expect(lookSaveFailure(apiError('conflict', 409, 'LOOK_008'))).toEqual({
      kind: 'errors',
      errors: { form: [BUSY] },
    });
  });

  it('429 는 기본 문구, 그 밖은 일반 실패 문구다', () => {
    const limited = apiError('rateLimited', 429, null);
    expect(lookSaveFailure(limited)).toEqual({
      kind: 'errors',
      errors: { form: [limited.message] },
    });
    expect(lookSaveFailure(apiError('server', 500, 'X', '서버 원문'))).toEqual({
      kind: 'errors',
      errors: { form: [SAVE_FAILED] },
    });
    expect(lookSaveFailure(new Error('x'))).toEqual({
      kind: 'errors',
      errors: { form: [SAVE_FAILED] },
    });
  });
});

describe('lookRowFailure', () => {
  it('401 · 403 은 화면 상태로, 404 · 결과 모름은 다시 읽기로 보낸다', () => {
    expect(lookRowFailure(apiError('unauthorized', 401, null), DELETE_FAILED)).toEqual({
      kind: 'access',
      denied: 'unauthorized',
    });
    expect(lookRowFailure(apiError('notFound', 404, 'LOOK_001'), DELETE_FAILED)).toEqual({
      kind: 'reload',
      notice: LOOK_GONE_NOTICE,
    });
    expect(lookRowFailure(apiError('unknownOutcome', 502, null), TOGGLE_FAILED)).toEqual({
      kind: 'reload',
      notice: UNKNOWN_OUTCOME_NOTICE,
    });
  });

  it('잠금 대기 · 저장소 설정은 안내하고, 그 밖은 넘겨받은 문구다', () => {
    expect(lookRowFailure(apiError('conflict', 409, 'LOOK_008'), DELETE_FAILED)).toEqual({
      kind: 'message',
      message: BUSY,
    });
    expect(lookRowFailure(apiError('unavailable', 503, 'LOOK_006'), TOGGLE_FAILED)).toEqual({
      kind: 'message',
      message: MEDIA_STORAGE_UNAVAILABLE,
    });
    expect(lookRowFailure(apiError('validation', 400, 'LOOK_005'), TOGGLE_FAILED)).toEqual({
      kind: 'message',
      message: TOGGLE_FAILED,
    });
  });
});

describe('lookOrderFailure', () => {
  it('401 은 바꾼 순서를 두고 안내한다, 403 은 권한 안내다', () => {
    expect(lookOrderFailure(apiError('unauthorized', 401, null))).toEqual({
      kind: 'message',
      message: SESSION_EXPIRED_ON_SAVE,
    });
    expect(lookOrderFailure(apiError('forbidden', 403, null))).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
  });

  it('집합이 바뀌었으면(LOOK_007) 다시 읽고, 잠금 대기는 다시 시도를 안내한다', () => {
    expect(lookOrderFailure(apiError('validation', 400, 'LOOK_007'))).toEqual({
      kind: 'reload',
      notice: ORDER_CHANGED_NOTICE,
    });
    expect(lookOrderFailure(apiError('conflict', 409, 'LOOK_008'))).toEqual({
      kind: 'message',
      message: BUSY,
    });
    expect(lookOrderFailure(apiError('server', 500, null))).toEqual({
      kind: 'message',
      message: SAVE_FAILED,
    });
  });
});
