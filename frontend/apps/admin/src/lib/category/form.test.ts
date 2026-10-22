import { ApiError, type FieldErrorItem } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { SAVE_FAILED, SESSION_EXPIRED_ON_SAVE } from '@/lib/api-errors';
import {
  CATEGORY_GONE_NOTICE,
  CATEGORY_UNKNOWN_NOTICE,
  categoryDeleteFailure,
  categoryRequest,
  categorySaveFailure,
  createForm,
  DELETE_FAILED,
  editForm,
} from './form';

const apiError = (
  kind: ConstructorParameters<typeof ApiError>[0],
  status: number | null,
  code: string | null,
  message: string | null,
  fieldErrors: FieldErrorItem[] = [],
) => new ApiError(kind, status, code, message, fieldErrors);

describe('categoryRequest', () => {
  it('최상위는 parentId null, 노출 순서는 숫자로 보낸다', () => {
    expect(categoryRequest(createForm(null, 3))).toEqual({
      ok: true,
      request: { parentId: null, name: '', displayOrder: 3, visible: true },
    });
  });

  it('수정 폼은 받은 값을 그대로 다시 보낸다(전체 교체)', () => {
    const form = editForm({
      categoryId: '7',
      parentId: '1',
      name: '롱 원피스',
      displayOrder: 2,
      visible: false,
    });
    expect(categoryRequest(form)).toEqual({
      ok: true,
      request: { parentId: '1', name: '롱 원피스', displayOrder: 2, visible: false },
    });
  });

  it('Integer 최대값까지는 보낸다', () => {
    expect(categoryRequest({ ...createForm(null, 0), displayOrder: '2147483647' })).toMatchObject({
      ok: true,
      request: { displayOrder: 2147483647 },
    });
  });

  it('빈 노출 순서는 null 로 보내 서버 검증에 맡긴다', () => {
    expect(categoryRequest({ ...createForm(null, 0), displayOrder: ' ' })).toMatchObject({
      ok: true,
      request: { displayOrder: null },
    });
  });

  it.each(['-1', '1.5', 'abc', '1e3', '2147483648', '12345678901'])(
    '노출 순서 %s 는 보내지 않고 칸 오류다',
    (displayOrder) => {
      expect(categoryRequest({ ...createForm(null, 0), displayOrder })).toEqual({
        ok: false,
        errors: { displayOrder: ['0 이상의 정수를 넣어 주세요.'] },
      });
    },
  );
});

describe('categorySaveFailure', () => {
  it('401 은 폼을 지키고 다시 로그인을 안내하고, 403 은 권한 안내다', () => {
    expect(categorySaveFailure(apiError('unauthorized', 401, 'GATEWAY_001', null))).toEqual({
      kind: 'errors',
      errors: { form: [SESSION_EXPIRED_ON_SAVE] },
    });
    expect(categorySaveFailure(apiError('forbidden', 403, 'GATEWAY_002', null))).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
  });

  it('fieldErrors 는 칸에, 모르는 칸은 폼 전체에 서버 문구로 붙인다', () => {
    const failure = categorySaveFailure(
      apiError('validation', 400, 'PRODUCT_132', 'name 은 필수입니다.', [
        { field: 'name', code: 'PRODUCT_132', message: 'name 은 필수입니다.' },
        {
          field: 'displayOrder',
          code: 'PRODUCT_135',
          message: 'displayOrder 는 0 이상이어야 합니다.',
        },
        { field: 'request', code: 'PRODUCT_100', message: '요청 값이 올바르지 않습니다.' },
      ]),
    );
    expect(failure).toEqual({
      kind: 'errors',
      errors: {
        name: ['name 은 필수입니다.'],
        displayOrder: ['displayOrder 는 0 이상이어야 합니다.'],
        form: ['요청 값이 올바르지 않습니다.'],
      },
    });
  });

  it.each(['PRODUCT_040', 'PRODUCT_041', 'PRODUCT_044'])('%s 는 상위 칸 오류다', (code) => {
    expect(categorySaveFailure(apiError('validation', 400, code, '서버 문구'))).toEqual({
      kind: 'errors',
      errors: { parentId: ['서버 문구'] },
    });
  });

  it('404 PRODUCT_004 · 502/504 는 다시 읽고 안내한다', () => {
    expect(
      categorySaveFailure(
        apiError('notFound', 404, 'PRODUCT_004', '존재하지 않는 카테고리입니다.'),
      ),
    ).toEqual({ kind: 'reload', notice: CATEGORY_GONE_NOTICE });
    expect(categorySaveFailure(apiError('unknownOutcome', 504, null, null))).toEqual({
      kind: 'reload',
      notice: CATEGORY_UNKNOWN_NOTICE,
    });
  });

  it('409 PRODUCT_045 는 폼 전체 서버 문구, 5xx · 예상 밖은 한 문구다', () => {
    expect(
      categorySaveFailure(apiError('conflict', 409, 'PRODUCT_045', '다시 시도해 주세요.')),
    ).toEqual({ kind: 'errors', errors: { form: ['다시 시도해 주세요.'] } });
    expect(categorySaveFailure(apiError('server', 500, 'PRODUCT_999', '서버 오류'))).toEqual({
      kind: 'errors',
      errors: { form: [SAVE_FAILED] },
    });
    expect(categorySaveFailure(new Error('boom'))).toEqual({
      kind: 'errors',
      errors: { form: [SAVE_FAILED] },
    });
  });
});

describe('categoryDeleteFailure', () => {
  it('하위 · 연결 상품 · 처리 중은 서버 문구다', () => {
    for (const code of ['PRODUCT_042', 'PRODUCT_043', 'PRODUCT_045']) {
      expect(categoryDeleteFailure(apiError('conflict', 409, code, `${code} 문구`))).toEqual({
        kind: 'message',
        message: `${code} 문구`,
      });
    }
  });

  it('401 · 403 은 접근 안내, 404 · 결과 모름은 다시 읽기, 그 밖은 한 문구다', () => {
    expect(categoryDeleteFailure(apiError('unauthorized', 401, 'GATEWAY_001', null))).toEqual({
      kind: 'access',
      denied: 'unauthorized',
    });
    expect(categoryDeleteFailure(apiError('notFound', 404, 'PRODUCT_004', 'x'))).toEqual({
      kind: 'reload',
      notice: CATEGORY_GONE_NOTICE,
    });
    expect(categoryDeleteFailure(apiError('unknownOutcome', 502, null, null))).toEqual({
      kind: 'reload',
      notice: CATEGORY_UNKNOWN_NOTICE,
    });
    expect(categoryDeleteFailure(new Error('boom'))).toEqual({
      kind: 'message',
      message: DELETE_FAILED,
    });
  });
});
