import { ApiError, type ApiErrorKind, type FieldErrorItem } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { SAVE_FAILED, SESSION_EXPIRED_ON_SAVE } from '@/lib/api-errors';
import {
  BUSY,
  CHECK_FIELDS,
  DELETE_FAILED,
  ITEM_NOT_ALLOWED,
  MEDIA_STORAGE_UNAVAILABLE,
  ORDER_CHANGED_NOTICE,
  orderFailure,
  PRODUCT_MISSING,
  rowFailure,
  saveFailure,
  SECTION_GONE_NOTICE,
  SECTION_LIMIT,
  UNKNOWN_OUTCOME_NOTICE,
} from './errors';
import { LINK_RULE, PERIOD_ORDER, productCountMessage, SINGLE_LINE_ONLY, TOO_LONG } from './form';

const apiError = (
  kind: ApiErrorKind,
  status: number | null,
  code: string | null,
  message: string | null = null,
  fieldErrors: FieldErrorItem[] = [],
) => new ApiError(kind, status, code, message, fieldErrors);

const pick = { type: 'PRODUCT_PICK' as const, mediaCount: 0 };
const hero = { type: 'HERO' as const, mediaCount: 2 };

describe('saveFailure', () => {
  it('401 은 폼을 지키고 다시 로그인을 안내한다, 403 은 권한 안내다', () => {
    expect(saveFailure(apiError('unauthorized', 401, 'SECURITY_002'), pick)).toEqual({
      kind: 'errors',
      errors: { form: [SESSION_EXPIRED_ON_SAVE] },
    });
    expect(saveFailure(apiError('forbidden', 403, 'SECURITY_005'), pick)).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
  });

  it('섹션 없음(404) · 결과 모름(502 · 504)은 목록을 다시 읽는다', () => {
    expect(saveFailure(apiError('notFound', 404, 'PRODUCT_046'), pick)).toEqual({
      kind: 'reload',
      notice: SECTION_GONE_NOTICE,
    });
    expect(saveFailure(apiError('unknownOutcome', 504, null), pick)).toEqual({
      kind: 'reload',
      notice: UNKNOWN_OUTCOME_NOTICE,
    });
  });

  it('400 fieldErrors 는 해당 칸에 업무 문구로 붙인다(서버 원문을 보이지 않는다)', () => {
    const failure = saveFailure(
      apiError('validation', 400, 'PRODUCT_141', 'title 은 200자 이하여야 합니다.', [
        { code: 'PRODUCT_141', field: 'title', message: 'title 은 200자 이하여야 합니다.' },
        { code: 'PRODUCT_164', field: 'linkLabel', message: '문구에는 제어 문자를…' },
        { code: 'PRODUCT_154', field: 'items[1].media.altText', message: 'altText 는…' },
        { code: 'PRODUCT_165', field: 'periodInRange', message: 'startAt · endAt 은…' },
        { code: 'PRODUCT_146', field: 'visible', message: 'visible 은 필수입니다.' },
      ]),
      hero,
    );
    expect(failure).toEqual({
      kind: 'errors',
      errors: {
        title: [TOO_LONG.title],
        linkLabel: [SINGLE_LINE_ONLY],
        'media.1.altText': [TOO_LONG.altText],
        period: [expect.stringContaining('0001년')],
        form: [CHECK_FIELDS],
      },
    });
  });

  it('칸 오류만 있으면 요약 문구를 폼 전체 자리에 둔다, 상품 아이템 오류는 상품 칸이다', () => {
    const failure = saveFailure(
      apiError('validation', 400, 'PRODUCT_155', null, [
        { code: 'PRODUCT_155', field: 'items[0].productId', message: 'productId 는 양수여야…' },
      ]),
      pick,
    );
    expect(failure).toEqual({
      kind: 'errors',
      errors: { products: [CHECK_FIELDS], form: [CHECK_FIELDS] },
    });
  });

  it('섹션 규칙 오류는 칸 · 폼 전체에 업무 문구로', () => {
    const errorsFor = (code: string, context: typeof pick | typeof hero = pick) => {
      const failure = saveFailure(apiError('server', 400, code, '서버 원문'), context);
      return failure.kind === 'errors' ? failure.errors : null;
    };
    expect(errorsFor('PRODUCT_047')).toEqual({ form: [ITEM_NOT_ALLOWED] });
    expect(errorsFor('PRODUCT_048')).toEqual({
      products: [productCountMessage('PRODUCT_PICK')],
      form: [CHECK_FIELDS],
    });
    expect(errorsFor('PRODUCT_048', hero)?.media).toHaveLength(1);
    expect(errorsFor('PRODUCT_050')).toEqual({ products: [PRODUCT_MISSING], form: [CHECK_FIELDS] });
    expect(errorsFor('PRODUCT_051')).toEqual({ period: [PERIOD_ORDER], form: [CHECK_FIELDS] });
    expect(errorsFor('PRODUCT_052')).toEqual({ linkUrl: [LINK_RULE], form: [CHECK_FIELDS] });
    expect(errorsFor('PRODUCT_054', hero)).toEqual({ form: [MEDIA_STORAGE_UNAVAILABLE] });
  });

  it('409 섹션 상한 · 잠금 대기, 503 저장소 미설정은 폼 전체 문구다', () => {
    const formOf = (error: ApiError) => {
      const failure = saveFailure(error, hero);
      return failure.kind === 'errors' ? failure.errors.form : null;
    };
    expect(formOf(apiError('conflict', 409, 'PRODUCT_056'))).toEqual([SECTION_LIMIT]);
    expect(formOf(apiError('conflict', 409, 'PRODUCT_058'))).toEqual([BUSY]);
    expect(formOf(apiError('unavailable', 503, 'PRODUCT_055'))).toEqual([
      MEDIA_STORAGE_UNAVAILABLE,
    ]);
  });

  it('요청 제한은 기본 안내, 그 밖 · 연결 실패는 일반 저장 실패 문구다', () => {
    const formOf = (error: unknown) => {
      const failure = saveFailure(error, pick);
      return failure.kind === 'errors' ? failure.errors.form : null;
    };
    expect(formOf(apiError('rateLimited', 429, 'GATEWAY_006'))?.[0]).toContain('요청이 너무');
    expect(formOf(apiError('server', 500, 'PRODUCT_999', '내부 원문'))).toEqual([SAVE_FAILED]);
    expect(formOf(apiError('network', null, null))).toEqual([SAVE_FAILED]);
    expect(formOf(new Error('x'))).toEqual([SAVE_FAILED]);
  });
});

describe('rowFailure', () => {
  it('401 · 403 은 권한 상태(읽기처럼 로그인 화면으로), 섹션 없음 · 결과 모름은 다시 읽는다', () => {
    expect(rowFailure(apiError('unauthorized', 401, null), DELETE_FAILED)).toEqual({
      kind: 'access',
      denied: 'unauthorized',
    });
    expect(rowFailure(apiError('notFound', 404, 'PRODUCT_046'), DELETE_FAILED)).toEqual({
      kind: 'reload',
      notice: SECTION_GONE_NOTICE,
    });
    expect(rowFailure(apiError('unknownOutcome', 502, null), DELETE_FAILED).kind).toBe('reload');
  });

  it('잠금 대기 · 저장소 미설정은 그 문구, 나머지는 넘겨받은 문구다', () => {
    expect(rowFailure(apiError('conflict', 409, 'PRODUCT_058'), DELETE_FAILED)).toEqual({
      kind: 'message',
      message: BUSY,
    });
    expect(rowFailure(apiError('unavailable', 503, 'PRODUCT_055'), 'x')).toEqual({
      kind: 'message',
      message: MEDIA_STORAGE_UNAVAILABLE,
    });
    expect(rowFailure(apiError('server', 400, 'PRODUCT_052'), 'x')).toEqual({
      kind: 'message',
      message: 'x',
    });
  });
});

describe('orderFailure', () => {
  it('401 은 바꾼 순서를 지키고 다시 로그인을 안내한다', () => {
    expect(orderFailure(apiError('unauthorized', 401, null))).toEqual({
      kind: 'message',
      message: SESSION_EXPIRED_ON_SAVE,
    });
  });

  it('섹션 집합이 바뀌었으면(PRODUCT_057) · 결과 모름이면 다시 읽는다', () => {
    expect(orderFailure(apiError('server', 400, 'PRODUCT_057'))).toEqual({
      kind: 'reload',
      notice: ORDER_CHANGED_NOTICE,
    });
    expect(orderFailure(apiError('unknownOutcome', 504, null))).toEqual({
      kind: 'reload',
      notice: UNKNOWN_OUTCOME_NOTICE,
    });
  });

  it('잠금 대기는 다시 저장하라고, 그 밖은 일반 문구다', () => {
    expect(orderFailure(apiError('conflict', 409, 'PRODUCT_058'))).toEqual({
      kind: 'message',
      message: BUSY,
    });
    expect(orderFailure(apiError('forbidden', 403, null))).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
    expect(orderFailure(apiError('network', null, null))).toEqual({
      kind: 'message',
      message: SAVE_FAILED,
    });
  });
});
