import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import {
  DELETE_CONFLICT_MESSAGE,
  DELETE_FAILED,
  DELETE_UNKNOWN_MESSAGE,
  parseDeletedResult,
  productDeletedHref,
  productDeleteFailure,
  SESSION_EXPIRED_ON_DELETE,
} from './delete';

describe('productDeleteFailure', () => {
  it('403 은 삭제 자리의 권한 안내, 401 은 화면을 두고 다시 로그인 안내', () => {
    expect(productDeleteFailure(new ApiError('forbidden', 403, 'AUTH_003', null))).toEqual({
      kind: 'forbidden',
    });
    expect(productDeleteFailure(new ApiError('unauthorized', 401, 'AUTH_001', null))).toEqual({
      kind: 'message',
      message: SESSION_EXPIRED_ON_DELETE,
    });
  });

  it('없는 상품(404 PRODUCT_001)은 목록으로', () => {
    expect(
      productDeleteFailure(new ApiError('notFound', 404, 'PRODUCT_001', '상품이 없습니다.')),
    ).toEqual({ kind: 'gone' });
  });

  it('동시 변경 충돌(409 PRODUCT_010)은 다시 읽고 안내한다', () => {
    expect(productDeleteFailure(new ApiError('conflict', 409, 'PRODUCT_010', '서버 문구'))).toEqual(
      { kind: 'reload', message: DELETE_CONFLICT_MESSAGE },
    );
  });

  it('결과 모름(502 · 504)은 다시 읽어 확인한다 — 자동으로 다시 지우지 않는다', () => {
    expect(productDeleteFailure(new ApiError('unknownOutcome', 504, null, null))).toEqual({
      kind: 'reload',
      message: DELETE_UNKNOWN_MESSAGE,
    });
  });

  it('그 밖의 4xx 는 서버 문구, 5xx · 연결 실패 · 모르는 값은 일반 문구', () => {
    expect(
      productDeleteFailure(new ApiError('rateLimited', 429, 'GATEWAY_006', '잠시 후 다시')),
    ).toEqual({ kind: 'message', message: '잠시 후 다시' });
    expect(productDeleteFailure(new ApiError('server', 500, 'PRODUCT_999', '서버 내부'))).toEqual({
      kind: 'message',
      message: DELETE_FAILED,
    });
    expect(productDeleteFailure(new ApiError('network', null, null, null))).toEqual({
      kind: 'message',
      message: DELETE_FAILED,
    });
    expect(productDeleteFailure(new Error('boom'))).toEqual({
      kind: 'message',
      message: DELETE_FAILED,
    });
  });
});

describe('parseDeletedResult', () => {
  it('아는 값만 받는다', () => {
    expect(parseDeletedResult('done')).toBe('done');
    expect(parseDeletedResult('gone')).toBe('gone');
    expect(parseDeletedResult('1')).toBeNull();
    expect(parseDeletedResult(['done'])).toBeNull();
    expect(parseDeletedResult(undefined)).toBeNull();
  });

  it('목록 주소와 짝이 맞는다', () => {
    expect(productDeletedHref('done')).toBe('/products?deleted=done');
  });
});
