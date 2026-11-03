import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { SESSION_EXPIRED_ON_SAVE } from '@/lib/api-errors';
import {
  moveSlot,
  optionRetirementsPath,
  productSectionHref,
  productSkusPath,
  sectionFailure,
  underAny,
} from './section';
import { UPDATE_CONFLICT, UPDATE_UNKNOWN } from './update';

const options = { isField: underAny('skus'), validation: { SKU_ADMIN_106: '무게 문구' } };
const error = (status: number, code: string | null, fieldErrors: ApiError['fieldErrors'] = []) =>
  new ApiError(
    status === 409 ? 'conflict' : status === 404 ? 'notFound' : 'server',
    status,
    code,
    '서버 문구',
    fieldErrors,
  );

describe('경로', () => {
  it('섹션 화면 · API 주소', () => {
    expect(productSectionHref('42', 'custom-order')).toBe('/products/42/edit/custom-order');
    expect(productSkusPath('42')).toBe('/api/v1/admin/products/42/skus');
    expect(optionRetirementsPath('42')).toBe('/api/v1/admin/products/42/option-retirements');
  });
});

describe('sectionFailure', () => {
  it('상품 · SKU 버전 충돌과 잠금 충돌은 다시 불러오기를 낸다', () => {
    for (const code of ['PRODUCT_010', 'SKU_ADMIN_002', 'SKU_ADMIN_005']) {
      expect(sectionFailure(error(409, code), options)).toEqual({
        kind: 'errors',
        errors: { form: [UPDATE_CONFLICT] },
        reload: true,
      });
    }
  });

  it('없거나 삭제된 상품은 gone, 403 은 forbidden, 401 은 폼을 지킨다', () => {
    expect(sectionFailure(error(404, 'SKU_ADMIN_001'), options)).toEqual({ kind: 'gone' });
    expect(sectionFailure(error(400, 'PRODUCT_001'), options)).toEqual({ kind: 'gone' });
    expect(sectionFailure(new ApiError('forbidden', 403, null, ''), options)).toEqual({
      kind: 'forbidden',
    });
    expect(sectionFailure(new ApiError('unauthorized', 401, null, ''), options)).toEqual({
      kind: 'errors',
      errors: { form: [SESSION_EXPIRED_ON_SAVE] },
      reload: false,
    });
  });

  it('칸 오류는 폼 칸 키로, 모르는 칸은 폼 전체로 옮긴다', () => {
    const failure = sectionFailure(
      error(400, 'SKU_ADMIN_106', [
        { field: 'skus[1].weightGrams', code: 'SKU_ADMIN_106', message: '서버 무게' },
        { field: 'version', code: 'SKU_ADMIN_101', message: '버전 문구' },
      ]),
      options,
    );
    expect(failure).toEqual({
      kind: 'errors',
      errors: { 'skus.1.weightGrams': ['무게 문구'], form: ['버전 문구'] },
      reload: false,
    });
  });

  it('업무 규칙 400 은 서버 문구, 결과 모름은 다시 불러오기', () => {
    expect(sectionFailure(error(400, 'SKU_ADMIN_004'), options)).toEqual({
      kind: 'errors',
      errors: { form: ['서버 문구'] },
      reload: false,
    });
    expect(sectionFailure(new ApiError('unknownOutcome', 504, null, ''), options)).toEqual({
      kind: 'errors',
      errors: { form: [UPDATE_UNKNOWN] },
      reload: true,
    });
    expect(sectionFailure(new Error('x'), options)).toMatchObject({ reload: true });
  });
});

describe('moveSlot', () => {
  it('위아래로 바꾸고 끝을 넘으면 그대로다', () => {
    expect(moveSlot(['a', 'b', 'c'], 1, -1)).toEqual(['b', 'a', 'c']);
    expect(moveSlot(['a', 'b', 'c'], 2, 1)).toEqual(['a', 'b', 'c']);
  });
});
