import { ApiError } from '@cf/api-client';
import { describe, expect, it, vi } from 'vitest';
import type { CategoryNode } from '@/lib/category/types';
import {
  categoryLabels,
  customFieldRange,
  formatLocalDate,
  loadProductDetail,
  skuOptionLabels,
  UNKNOWN_CATEGORY_LABEL,
  UNKNOWN_OPTION_LABEL,
  type AdminCustomOrderField,
} from './detail';
import { adminProduct } from './test-fixtures';

const loader = (impl: () => Promise<unknown>) => vi.fn(impl) as <T>(path: string) => Promise<T>;

describe('loadProductDetail', () => {
  it('어드민 상세 경로를 부르고 상품을 돌려준다', async () => {
    const product = adminProduct();
    const load = loader(async () => product);
    await expect(loadProductDetail('4611686018427387904', load)).resolves.toEqual({
      kind: 'ok',
      product,
    });
    expect(load).toHaveBeenCalledWith('/api/v1/admin/products/4611686018427387904');
  });

  it('형식이 틀린 아이디는 부르지 않고 없는 상품이다', async () => {
    const load = loader(async () => adminProduct());
    for (const id of ['abc', '0', '-1', '9223372036854775808']) {
      await expect(loadProductDetail(id, load)).resolves.toEqual({ kind: 'notFound' });
    }
    expect(load).not.toHaveBeenCalled();
  });

  it('404 · 경로 검증 400(PRODUCT_105 · 198)은 없는 상품이다', async () => {
    for (const error of [
      new ApiError('notFound', 404, 'PRODUCT_001', null),
      new ApiError('server', 400, 'PRODUCT_105', null),
      new ApiError('server', 400, 'PRODUCT_198', null),
    ]) {
      await expect(
        loadProductDetail(
          '1',
          loader(async () => Promise.reject(error)),
        ),
      ).resolves.toEqual({ kind: 'notFound' });
    }
  });

  it('401 · 403 은 접근 상태다', async () => {
    await expect(
      loadProductDetail(
        '1',
        loader(async () => Promise.reject(new ApiError('unauthorized', 401, null, null))),
      ),
    ).resolves.toEqual({ kind: 'denied', denied: 'unauthorized' });
    await expect(
      loadProductDetail(
        '1',
        loader(async () => Promise.reject(new ApiError('forbidden', 403, null, null))),
      ),
    ).resolves.toEqual({ kind: 'denied', denied: 'forbidden' });
  });

  it('그 밖의 실패는 다시 불러오기다', async () => {
    for (const error of [
      new ApiError('unavailable', 503, null, null),
      new ApiError('server', 400, 'PRODUCT_999', null),
      new TypeError('boom'),
    ]) {
      await expect(
        loadProductDetail(
          '1',
          loader(async () => Promise.reject(error)),
        ),
      ).resolves.toEqual({ kind: 'failed' });
    }
  });
});

describe('skuOptionLabels', () => {
  it('그룹 진열 순서로 "그룹: 값" 을 잇고 은퇴 값이 섞이면 표시한다', () => {
    const labels = skuOptionLabels(adminProduct());
    expect(labels.get('401')).toEqual({ text: '색상: 쪽빛 · 사이즈: M', retired: false });
    expect(labels.get('402')).toEqual({ text: '색상: 쪽빛 · 사이즈: L', retired: true });
  });

  it('은퇴한 그룹의 값도 은퇴로 본다', () => {
    const base = adminProduct();
    const [color, size] = base.optionGroups;
    const product = adminProduct({
      optionGroups: [{ ...color!, retiredAt: '2026-10-01T00:00:00Z' }, size!],
    });
    expect(skuOptionLabels(product).get('401')?.retired).toBe(true);
  });

  it('옵션 없는 SKU 는 기본, 목록에 없는 값은 아이디 대신 안내 문구다', () => {
    const product = adminProduct({
      skus: [
        { ...adminProduct().skus[0]!, skuId: '1', optionValueIds: [] },
        { ...adminProduct().skus[0]!, skuId: '2', optionValueIds: ['301', '999'] },
      ],
    });
    const labels = skuOptionLabels(product);
    expect(labels.get('1')?.text).toBe('기본');
    expect(labels.get('2')?.text).toBe(`색상: 쪽빛 · ${UNKNOWN_OPTION_LABEL}`);
    expect(labels.get('2')?.text).not.toContain('999');
  });
});

describe('categoryLabels', () => {
  const tree: CategoryNode[] = [
    {
      categoryId: '1',
      parentId: null,
      name: '상의',
      displayOrder: 0,
      visible: true,
      children: [
        {
          categoryId: '101',
          parentId: '1',
          name: '셔츠',
          displayOrder: 0,
          visible: true,
          children: [],
        },
        {
          categoryId: '102',
          parentId: '1',
          name: '저고리',
          displayOrder: 1,
          visible: false,
          children: [],
        },
      ],
    },
  ];

  it('대표 카테고리를 맨 앞에, 경로 이름으로 보인다', () => {
    expect(categoryLabels(adminProduct(), tree)).toEqual([
      { text: '상의 > 저고리', primary: true },
      { text: '상의 > 셔츠', primary: false },
    ]);
  });

  it('트리에 없는 아이디는 안내 문구, 트리를 못 읽었으면 null', () => {
    expect(categoryLabels(adminProduct({ categoryId: '777', categoryIds: ['777'] }), tree)).toEqual(
      [{ text: UNKNOWN_CATEGORY_LABEL, primary: true }],
    );
    expect(categoryLabels(adminProduct(), null)).toBeNull();
  });
});

describe('formatLocalDate', () => {
  it('날짜만 바꾸고 시간대를 타지 않는다', () => {
    expect(formatLocalDate('2026-11-03')).toBe('2026. 11. 3.');
    expect(formatLocalDate('언젠가')).toBe('언젠가');
  });
});

describe('customFieldRange', () => {
  const field = (overrides: Partial<AdminCustomOrderField>): AdminCustomOrderField => ({
    fieldKey: 'chest',
    label: '가슴둘레',
    inputType: { code: 'NUMBER', name: '숫자', description: '' },
    unit: 'cm',
    required: true,
    minValue: null,
    maxValue: null,
    stepValue: null,
    helpText: null,
    displayOrder: 0,
    ...overrides,
  });

  it('숫자 입력은 최소 · 최대 · 증분', () => {
    expect(customFieldRange(field({ minValue: 70, maxValue: 130, stepValue: 0.5 }))).toBe(
      '70 ~ 130 cm, 0.5 cm 단위',
    );
    expect(customFieldRange(field({ minValue: 70 }))).toBe('70 cm 이상');
    expect(customFieldRange(field({ maxValue: 130, unit: null }))).toBe('130 이하');
    expect(customFieldRange(field({}))).toBeNull();
  });

  it('글자 입력은 최대 글자 수만', () => {
    const text = { code: 'TEXT', name: '글자', description: '' };
    expect(customFieldRange(field({ inputType: text, maxValue: 50, minValue: 1 }))).toBe(
      '최대 50자',
    );
    expect(customFieldRange(field({ inputType: text }))).toBeNull();
  });
});
