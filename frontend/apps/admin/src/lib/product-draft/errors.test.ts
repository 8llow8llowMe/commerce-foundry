import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { FORBIDDEN_INLINE, SAVE_FAILED, SESSION_EXPIRED_ON_SAVE } from '@/lib/api-errors';
import {
  BODY_UNREADABLE,
  CHECK_FIELDS,
  draftCreateFailure,
  draftFieldKey,
  IMAGE_FORMAT,
  IMAGE_NOT_ALLOWED,
  IMAGE_NOT_FOUND,
  IMAGE_STORAGE_UNAVAILABLE,
  MEDIA_NOT_CONFIGURED,
  REJECT_APPLIED,
  REJECT_BUSY,
  REJECT_EXPIRED,
  REJECT_NOT_FOUND,
  REJECT_SESSION_EXPIRED,
  REJECT_UNKNOWN,
  rejectFailure,
  THUMBNAIL_DUPLICATED,
  UNKNOWN_OUTCOME,
} from './errors';
import { IMAGE_DUPLICATED, INVALID_CHARACTER, LIST_ITEM_INVALID, tooLong } from './form';

const validation = (items: { code: string; field: string }[]) =>
  new ApiError(
    'validation',
    400,
    items[0]?.code ?? 'PRODUCT_100',
    'nameHint 는 100자 이하여야 합니다.',
    items.map((item) => ({ ...item, message: '서버 문구' })),
  );
const business = (status: number, code: string, kind: ApiError['kind'] = 'server') =>
  new ApiError(kind, status, code, '서버 문구');

describe('draftFieldKey', () => {
  it.each([
    ['nameHint', 'nameHint'],
    ['materials[2]', 'materials'],
    ['materials[2].<list element>', 'materials'],
    ['careNotes', 'careNotes'],
    ['images[0].url', 'images'],
    ['images', 'images'],
    ['unknownField', 'form'],
  ])('%s → %s', (path, key) => {
    expect(draftFieldKey(path)).toBe(key);
  });
});

describe('draftCreateFailure', () => {
  it('401 은 폼을 지키고 다시 로그인을 안내한다, 403 은 접근 상태다', () => {
    expect(draftCreateFailure(new ApiError('unauthorized', 401, 'AUTH_001', null))).toEqual({
      kind: 'errors',
      errors: { form: [SESSION_EXPIRED_ON_SAVE] },
      uncertain: false,
    });
    expect(draftCreateFailure(new ApiError('forbidden', 403, 'SECURITY_005', null))).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
  });

  it('검증 코드는 서버 경로의 칸에 운영자 문구로 단다(영문 칸 이름이 든 서버 문구를 쓰지 않는다)', () => {
    const failure = draftCreateFailure(
      validation([
        { code: 'PRODUCT_DRAFT_101', field: 'nameHint' },
        { code: 'PRODUCT_DRAFT_102', field: 'nameHint' },
        { code: 'PRODUCT_DRAFT_105', field: 'materials[1].<list element>' },
        { code: 'PRODUCT_DRAFT_122', field: 'images[0].url' },
        { code: 'PRODUCT_DRAFT_199', field: 'tone' },
      ]),
    );
    expect(failure).toEqual({
      kind: 'errors',
      uncertain: false,
      errors: {
        nameHint: [tooLong(100), INVALID_CHARACTER],
        materials: [LIST_ITEM_INVALID],
        images: [IMAGE_NOT_ALLOWED],
        tone: [CHECK_FIELDS],
      },
    });
  });

  it.each([
    ['PRODUCT_DRAFT_030', 400, { images: [IMAGE_NOT_ALLOWED] }],
    ['PRODUCT_DRAFT_031', 400, { images: [IMAGE_NOT_ALLOWED] }],
    ['PRODUCT_DRAFT_033', 400, { images: [IMAGE_NOT_FOUND] }],
    ['PRODUCT_DRAFT_034', 400, { images: [THUMBNAIL_DUPLICATED] }],
    ['PRODUCT_DRAFT_035', 400, { images: [IMAGE_DUPLICATED] }],
    ['PRODUCT_DRAFT_036', 400, { images: [IMAGE_FORMAT] }],
    ['PRODUCT_DRAFT_032', 503, { form: [MEDIA_NOT_CONFIGURED] }],
    ['PRODUCT_DRAFT_501', 503, { form: [IMAGE_STORAGE_UNAVAILABLE] }],
  ])('이미지 업무 오류 %s 는 저장 전이라 결과 모름이 아니다', (code, status, errors) => {
    const kind = status === 503 ? 'unavailable' : 'server';
    expect(draftCreateFailure(business(status, code, kind))).toEqual({
      kind: 'errors',
      errors,
      uncertain: false,
    });
  });

  it('칸 없는 깨진 본문(PRODUCT_100)은 폼 전체 문구다', () => {
    expect(draftCreateFailure(business(400, 'PRODUCT_100'))).toEqual({
      kind: 'errors',
      errors: { form: [BODY_UNREADABLE] },
      uncertain: false,
    });
  });

  it.each([
    ['연결 실패', new ApiError('network', null, null, null)],
    ['봉투 아님', new ApiError('invalidResponse', 200, null, null)],
    ['504', new ApiError('unknownOutcome', 504, null, null)],
    ['게이트웨이 503', new ApiError('unavailable', 503, 'BFF_002', null)],
    ['500', new ApiError('server', 500, 'COMMON_500', null)],
    ['예상 못 한 예외', new Error('boom')],
  ])('%s 는 결과 모름이다 — 초안이 생겼을 수 있다', (_, cause) => {
    expect(draftCreateFailure(cause)).toEqual({
      kind: 'errors',
      errors: { form: [UNKNOWN_OUTCOME] },
      uncertain: true,
    });
  });

  it('429 는 서버 문구, 그 밖의 4xx 는 저장 실패 문구다', () => {
    expect(draftCreateFailure(new ApiError('rateLimited', 429, 'GATEWAY_006', '잠시 후'))).toEqual({
      kind: 'errors',
      errors: { form: ['잠시 후'] },
      uncertain: false,
    });
    expect(draftCreateFailure(business(400, 'PRODUCT_DRAFT_999'))).toEqual({
      kind: 'errors',
      errors: { form: [SAVE_FAILED] },
      uncertain: false,
    });
  });
});

describe('rejectFailure', () => {
  it('상태가 바뀐 409 는 문구와 함께 화면을 다시 읽는다', () => {
    expect(rejectFailure(business(409, 'PRODUCT_DRAFT_020', 'conflict'))).toEqual({
      message: REJECT_APPLIED,
      refresh: true,
    });
    expect(rejectFailure(business(409, 'PRODUCT_DRAFT_022', 'conflict'))).toEqual({
      message: REJECT_EXPIRED,
      refresh: true,
    });
    expect(rejectFailure(business(409, 'PRODUCT_DRAFT_025', 'conflict'))).toEqual({
      message: REJECT_BUSY,
      refresh: true,
    });
  });

  it('없는 초안 · 세션 만료 · 권한 없음 · 결과 모름', () => {
    expect(rejectFailure(business(404, 'PRODUCT_DRAFT_001', 'notFound'))).toEqual({
      message: REJECT_NOT_FOUND,
      refresh: false,
    });
    expect(rejectFailure(new ApiError('unauthorized', 401, null, null))).toEqual({
      message: REJECT_SESSION_EXPIRED,
      refresh: false,
    });
    expect(rejectFailure(new ApiError('forbidden', 403, null, null))).toEqual({
      message: FORBIDDEN_INLINE,
      refresh: false,
    });
    expect(rejectFailure(new ApiError('unknownOutcome', 504, null, null))).toEqual({
      message: REJECT_UNKNOWN,
      refresh: false,
    });
  });
});
