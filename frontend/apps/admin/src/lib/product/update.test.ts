import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { SAVE_FAILED, SESSION_EXPIRED_ON_SAVE } from '@/lib/api-errors';
import { NAME_CHARACTER, NAME_REQUIRED, SUMMARY_INVALID } from './create';
import { adminProduct } from './test-fixtures';
import {
  headerFormOf,
  headerRequest,
  productEditHref,
  productHeaderPath,
  sameHeader,
  UPDATE_CONFLICT,
  UPDATE_UNKNOWN,
  updatedProductHref,
  updateFailure,
} from './update';

const field = (code: string, path: string) => ({ field: path, code, message: '서버 문구' });

describe('경로', () => {
  it('수정 API · 수정 화면 주소', () => {
    expect(productHeaderPath('42')).toBe('/api/v1/admin/products/42/header');
    expect(productEditHref('42')).toBe('/products/42/edit');
  });
});

describe('headerFormOf · sameHeader', () => {
  it('상세의 null 은 빈 칸, 판매 상태는 코드로 채운다', () => {
    const form = headerFormOf(
      adminProduct({
        summary: null,
        descriptionHtml: null,
        saleStatus: { code: 'STOP_SELLING', name: '판매 종료', description: '' },
        visible: true,
      }),
    );
    expect(form).toEqual({
      name: '테스트 저고리',
      summary: '',
      descriptionHtml: '',
      saleStatus: 'STOP_SELLING',
      visible: true,
    });
    expect(sameHeader(form, { ...form })).toBe(true);
    expect(sameHeader(form, { ...form, visible: false })).toBe(false);
  });

  it('모르는 판매 상태 코드는 판매 종료로 둔다', () => {
    const form = headerFormOf(
      adminProduct({ saleStatus: { code: 'SOMETHING', name: '?', description: '' } }),
    );
    expect(form.saleStatus).toBe('STOP_SELLING');
  });
});

describe('headerRequest', () => {
  const base = headerFormOf(adminProduct());

  it('버전을 되돌려 보내고 문구는 다듬는다 · 빈 요약 · 상세 설명은 null 로 지운다', () => {
    expect(
      headerRequest({ ...base, name: '  새 이름 ', summary: '   ', descriptionHtml: ' ' }, 3),
    ).toEqual({
      ok: true,
      request: {
        version: 3,
        name: '새 이름',
        summary: null,
        descriptionHtml: null,
        saleStatus: 'ON_SALE',
        visible: false,
      },
    });
  });

  it('상세 설명은 공백을 다듬지 않고 그대로 보낸다', () => {
    const built = headerRequest({ ...base, descriptionHtml: ' <p>a</p>\n' }, 0);
    expect(built.ok && built.request.descriptionHtml).toBe(' <p>a</p>\n');
  });

  it('등록과 같은 칸 규칙으로 막는다', () => {
    expect(headerRequest({ ...base, name: ' ' }, 1)).toEqual({
      ok: false,
      errors: { name: [NAME_REQUIRED] },
    });
    expect(headerRequest({ ...base, name: '탭\t이름', summary: 'a'.repeat(201) }, 1)).toEqual({
      ok: false,
      errors: { name: [NAME_CHARACTER], summary: [SUMMARY_INVALID] },
    });
  });
});

describe('updatedProductHref', () => {
  it('정제됐으면 상세가 한 번 알리게 표시를 붙인다', () => {
    const product = adminProduct();
    expect(updatedProductHref({ product, descriptionHtmlSanitized: false })).toBe(
      `/products/${product.productId}`,
    );
    expect(updatedProductHref({ product, descriptionHtmlSanitized: true })).toBe(
      `/products/${product.productId}?sanitized=1`,
    );
  });
});

describe('updateFailure', () => {
  it('401 은 폼을 지키고 다시 로그인을 안내한다 · 403 은 접근 상태다', () => {
    expect(updateFailure(new ApiError('unauthorized', 401, 'AUTH_001', null))).toEqual({
      kind: 'errors',
      errors: { form: [SESSION_EXPIRED_ON_SAVE] },
      reload: false,
    });
    expect(updateFailure(new ApiError('forbidden', 403, 'AUTH_002', null))).toEqual({
      kind: 'forbidden',
    });
  });

  it('409 PRODUCT_010 은 최신 내용 불러오기를 낸다', () => {
    expect(updateFailure(new ApiError('conflict', 409, 'PRODUCT_010', '서버 문구'))).toEqual({
      kind: 'errors',
      errors: { form: [UPDATE_CONFLICT] },
      reload: true,
    });
  });

  it('404 PRODUCT_001 은 없어진 상품이다', () => {
    expect(updateFailure(new ApiError('notFound', 404, 'PRODUCT_001', null))).toEqual({
      kind: 'gone',
    });
  });

  it('칸 검증은 그 칸, 모르는 칸(version 등)은 폼 전체다', () => {
    const cause = new ApiError('validation', 400, 'PRODUCT_100', null, [
      field('PRODUCT_074', 'name'),
      field('PRODUCT_077', 'summary'),
      field('PRODUCT_100', 'version'),
    ]);
    expect(updateFailure(cause)).toEqual({
      kind: 'errors',
      errors: {
        name: [NAME_REQUIRED],
        summary: [SUMMARY_INVALID],
        form: ['입력값을 확인해 주세요.'],
      },
      reload: false,
    });
  });

  it('결과를 모르는 실패(502 · 504 · 5xx · 연결)는 최신 내용 불러오기를 낸다', () => {
    for (const cause of [
      new ApiError('unknownOutcome', 504, null, null),
      new ApiError('server', 500, 'COMMON_500', null),
      new ApiError('network', null, null, null),
      new Error('boom'),
    ]) {
      expect(updateFailure(cause)).toEqual({
        kind: 'errors',
        errors: { form: [UPDATE_UNKNOWN] },
        reload: true,
      });
    }
  });

  it('그 밖의 4xx 는 서버 문구, 코드 없는 4xx 는 일반 문구다', () => {
    expect(updateFailure(new ApiError('rateLimited', 429, 'COMMON_429', '잠시 뒤에'))).toEqual({
      kind: 'errors',
      errors: { form: ['잠시 뒤에'] },
      reload: false,
    });
    expect(updateFailure(new ApiError('server', 400, null, null))).toEqual({
      kind: 'errors',
      errors: { form: [SAVE_FAILED] },
      reload: false,
    });
  });
});
