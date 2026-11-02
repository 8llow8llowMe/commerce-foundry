import { describe, expect, it } from 'vitest';
import { flattenTree } from '@/lib/category/tree';
import type { CategoryNode } from '@/lib/category/types';
import {
  addOptionValue,
  AMOUNT_FORMAT,
  AMOUNT_TOO_LARGE,
  CATEGORY_REQUIRED,
  combinationCount,
  combinations,
  COMBINATIONS_TOO_MANY,
  comboKey,
  createdProductHref,
  emptyProductForm,
  emptySku,
  GROUP_NAME_DUPLICATED,
  GROUP_VALUES_REQUIRED,
  HS_CODE_INVALID,
  LIST_PRICE_NOT_HIGHER,
  localToday,
  MAX_QUANTITY_RANGE,
  NAME_CHARACTER,
  CATEGORY_IDS_TOO_MANY,
  NAME_REQUIRED,
  PRICE_REQUIRED,
  productRequest,
  pruneCategories,
  QUANTITY_INVALID,
  SHIP_DATE_PAST,
  skuLabel,
  skuRows,
  VALUE_DUPLICATED,
  VALUE_INVALID,
  WEIGHT_INVALID,
  ZERO_ADDITIONAL_REQUIRED,
  type OptionGroupForm,
  type ProductCreateForm,
} from './create';
import { adminProduct } from './test-fixtures';

const TODAY = '2026-11-02';
const group = (id: string, name: string, values: string[]): OptionGroupForm => ({
  id,
  name,
  values,
});

function filled(overrides: Partial<ProductCreateForm> = {}): ProductCreateForm {
  return {
    ...emptyProductForm(),
    name: '  린넨 저고리 ',
    categoryId: '12',
    salePrice: '59,000',
    ...overrides,
  };
}

describe('combinations · skuRows', () => {
  it('옵션이 없으면 기본 SKU 한 줄(빈 조합)이다', () => {
    expect(combinations([])).toEqual([[]]);
    expect(skuRows(emptyProductForm())).toEqual([
      { key: comboKey([]), values: [], sku: emptySku() },
    ]);
    expect(skuLabel([], [])).toBe('기본');
  });

  it('그룹 · 값 입력 순서대로 카테시안 곱이고 값이 없는 그룹은 건너뛴다', () => {
    const groups = [
      group('a', '색상', ['쪽빛', '흰색']),
      group('b', '빈', []),
      group('c', '사이즈', ['S', 'M']),
    ];
    expect(combinations(groups)).toEqual([
      ['쪽빛', 'S'],
      ['쪽빛', 'M'],
      ['흰색', 'S'],
      ['흰색', 'M'],
    ]);
    expect(combinationCount(groups)).toBe(4);
    expect(skuLabel(groups, ['흰색', 'M'])).toBe('색상: 흰색 · 사이즈: M');
  });

  it('옵션을 바꿔도 같은 조합의 입력은 남고 새 조합은 기본값이다', () => {
    const kept = { ...emptySku(), additionalPrice: '3000', quantity: '7' };
    const form = filled({
      optionGroups: [group('a', '사이즈', ['S', 'M'])],
      skus: { [comboKey(['M'])]: kept },
    });
    const rows = skuRows(form);
    expect(rows.map((row) => row.values)).toEqual([['S'], ['M']]);
    expect(rows[0]!.sku).toEqual(emptySku());
    expect(rows[1]!.sku).toBe(kept);
  });
});

describe('addOptionValue', () => {
  const groups = [group('a', '사이즈', ['S'])];

  it('앞뒤 공백을 빼고 더한다', () => {
    const result = addOptionValue(groups, 'a', ' M ');
    expect(result).toEqual({ ok: true, groups: [group('a', '사이즈', ['S', 'M'])] });
  });

  it('빈 값 · 제어 문자 · 50자 초과 · 같은 값은 막는다', () => {
    expect(addOptionValue(groups, 'a', '  ')).toEqual({ ok: false, message: VALUE_INVALID });
    expect(addOptionValue(groups, 'a', 'M\tL')).toEqual({ ok: false, message: VALUE_INVALID });
    expect(addOptionValue(groups, 'a', 'x'.repeat(51))).toEqual({
      ok: false,
      message: VALUE_INVALID,
    });
    expect(addOptionValue(groups, 'a', 'S')).toEqual({ ok: false, message: VALUE_DUPLICATED });
  });

  it('조합이 100개를 넘으면 막는다', () => {
    const tens = Array.from({ length: 10 }, (_, i) => `v${i}`);
    const big = [group('a', 'A', tens), group('b', 'B', tens)];
    expect(combinationCount(big)).toBe(100);
    expect(addOptionValue(big, 'b', 'v10')).toEqual({
      ok: false,
      message: COMBINATIONS_TOO_MANY,
    });
  });
});

describe('productRequest', () => {
  it('옵션 없는 상품 — 문구는 다듬고 빈 선택 칸은 보내지 않는다 · 처음 재고를 비우면 initialStock 이 없다', () => {
    const built = productRequest(filled({ summary: '   ' }), TODAY);
    expect(built).toEqual({
      ok: true,
      request: {
        name: '린넨 저고리',
        categoryId: '12',
        saleStatus: 'ON_SALE',
        visible: false,
        salePrice: { amount: 59000, currency: 'KRW' },
        preorder: false,
        optionGroups: [],
        skus: [
          {
            optionValues: [],
            additionalPrice: { amount: 0, currency: 'KRW' },
            saleStatus: 'ON_SALE',
          },
        ],
      },
    });
  });

  it('선택 칸 · 옵션 · SKU 칸을 본문으로 옮긴다(USD 는 센트, HS 코드는 구분자를 뺀다, 진열 카테고리는 대표 · 중복 제외)', () => {
    const form = filled({
      currency: 'USD',
      salePrice: '49.5',
      listPrice: '60',
      summary: ' 시원한 ',
      descriptionHtml: '<p>설명</p>',
      categoryIds: ['12', '13', '13'],
      maxQuantityPerOrder: '5',
      preorder: true,
      expectedShipDate: TODAY,
      visible: true,
      optionGroups: [group('a', ' 사이즈 ', ['S', 'M'])],
      skus: {
        [comboKey(['S'])]: {
          ...emptySku(),
          weightGrams: '350',
          hsCode: '6211.42-1000',
          quantity: '30',
        },
        [comboKey(['M'])]: {
          ...emptySku(),
          additionalPrice: '2.5',
          saleStatus: 'STOP_SELLING',
          stockMode: 'unlimited',
        },
      },
    });
    const built = productRequest(form, TODAY);
    expect(built.ok).toBe(true);
    if (!built.ok) return;
    expect(built.request).toMatchObject({
      summary: '시원한',
      descriptionHtml: '<p>설명</p>',
      categoryIds: ['13'],
      salePrice: { amount: 4950, currency: 'USD' },
      listPrice: { amount: 6000, currency: 'USD' },
      maxQuantityPerOrder: 5,
      preorder: true,
      expectedShipDate: TODAY,
      visible: true,
      optionGroups: [{ name: '사이즈', values: ['S', 'M'] }],
      skus: [
        {
          optionValues: ['S'],
          additionalPrice: { amount: 0, currency: 'USD' },
          saleStatus: 'ON_SALE',
          weightGrams: 350,
          hsCode: '6211421000',
          initialStock: { stockManaged: true, quantity: 30 },
        },
        {
          optionValues: ['M'],
          additionalPrice: { amount: 250, currency: 'USD' },
          saleStatus: 'STOP_SELLING',
          initialStock: { stockManaged: false, quantity: 0 },
        },
      ],
    });
  });

  it('프리오더를 끄면 발송 예정일을 보내지 않는다', () => {
    const built = productRequest(filled({ expectedShipDate: '2020-01-01' }), TODAY);
    expect(built.ok).toBe(true);
    expect(built.ok && built.request.expectedShipDate).toBeUndefined();
  });

  it('필수 칸 · 형식 오류를 칸 키로 모은다', () => {
    const built = productRequest(
      {
        ...emptyProductForm(),
        name: '이름​',
        listPrice: '1,0',
        maxQuantityPerOrder: '1000',
        preorder: true,
        expectedShipDate: '2026-11-01',
      },
      TODAY,
    );
    expect(built).toEqual({
      ok: false,
      errors: {
        name: [NAME_CHARACTER],
        categoryId: [CATEGORY_REQUIRED],
        salePrice: [PRICE_REQUIRED],
        listPrice: [AMOUNT_FORMAT],
        maxQuantityPerOrder: [MAX_QUANTITY_RANGE],
        expectedShipDate: [SHIP_DATE_PAST],
      },
    });
    expect(productRequest({ ...filled(), name: ' ' }, TODAY)).toEqual({
      ok: false,
      errors: { name: [NAME_REQUIRED] },
    });
    // 붙여넣은 끝 탭 · 줄바꿈은 다듬어 보내므로 막지 않는다(서버도 다듬은 뒤 본다)
    expect(productRequest(filled({ name: '상품\t\n', summary: '요약\n' }), TODAY).ok).toBe(true);
  });

  it('진열 카테고리 상한은 대표를 빼고 20개다(서버 @Size 와 같다)', () => {
    const ids = (n: number) => Array.from({ length: n }, (_, i) => String(100 + i));
    expect(productRequest(filled({ categoryIds: ids(20) }), TODAY).ok).toBe(true);
    expect(productRequest(filled({ categoryIds: ids(21) }), TODAY)).toMatchObject({
      ok: false,
      errors: { categoryIds: [CATEGORY_IDS_TOO_MANY] },
    });
  });

  it('금액 상한 · 정가는 판매가보다 높아야 한다', () => {
    expect(productRequest(filled({ salePrice: '100000001' }), TODAY)).toEqual({
      ok: false,
      errors: { salePrice: [AMOUNT_TOO_LARGE] },
    });
    expect(productRequest(filled({ listPrice: '59000' }), TODAY)).toEqual({
      ok: false,
      errors: { listPrice: [LIST_PRICE_NOT_HIGHER] },
    });
  });

  it('옵션 그룹 이름 중복 · 값 없는 그룹을 그 그룹 칸에 둔다', () => {
    const built = productRequest(
      filled({
        optionGroups: [
          group('a', '사이즈', ['S']),
          group('b', '사이즈', []),
          group('c', '', ['x']),
        ],
      }),
      TODAY,
    );
    expect(built.ok).toBe(false);
    if (built.ok) return;
    expect(built.errors['optionGroups.1.name']).toEqual([GROUP_NAME_DUPLICATED]);
    expect(built.errors['optionGroups.1.values']).toEqual([GROUP_VALUES_REQUIRED]);
    expect(built.errors['optionGroups.2.name']).toHaveLength(1);
  });

  it('SKU 칸 오류는 줄 번호 키다 · 판매중 SKU 에 추가금 0 이 없으면 표 전체 오류다', () => {
    const form = filled({
      optionGroups: [group('a', '사이즈', ['S', 'M'])],
      skus: {
        [comboKey(['S'])]: { ...emptySku(), additionalPrice: '1000', weightGrams: '0' },
        [comboKey(['M'])]: {
          ...emptySku(),
          additionalPrice: '2000',
          hsCode: '６２１１４２',
          quantity: '-1',
        },
      },
    });
    const built = productRequest(form, TODAY);
    expect(built).toEqual({
      ok: false,
      errors: {
        'skus.0.weightGrams': [WEIGHT_INVALID],
        'skus.1.hsCode': [HS_CODE_INVALID],
        'skus.1.initialStock': [QUANTITY_INVALID],
        skus: [ZERO_ADDITIONAL_REQUIRED],
      },
    });
  });

  it('판매중 SKU 가 없으면 추가금 0 규칙을 보지 않는다', () => {
    const form = filled({
      skus: {
        [comboKey([])]: { ...emptySku(), additionalPrice: '500', saleStatus: 'STOP_SELLING' },
      },
    });
    expect(productRequest(form, TODAY).ok).toBe(true);
  });
});

describe('pruneCategories · localToday · createdProductHref', () => {
  const tree: CategoryNode[] = [
    {
      categoryId: '12',
      parentId: null,
      name: '상의',
      displayOrder: 0,
      visible: true,
      children: [],
    },
  ];

  it('트리에 없는 고른 카테고리를 뺀다 · 그대로면 같은 폼', () => {
    const rows = flattenTree(tree);
    const same = filled({ categoryIds: ['12'] });
    expect(pruneCategories(same, rows)).toBe(same);
    expect(
      pruneCategories(filled({ categoryId: '99', categoryIds: ['12', '98'] }), rows),
    ).toMatchObject({ categoryId: '', categoryIds: ['12'] });
  });

  it('오늘은 이 컴퓨터 날짜 yyyy-MM-dd', () => {
    expect(localToday(new Date(2026, 0, 5))).toBe('2026-01-05');
  });

  it('등록 뒤 상세 주소 — 정제됐으면 표시를 붙인다', () => {
    const product = adminProduct();
    expect(createdProductHref({ product, descriptionHtmlSanitized: false })).toBe(
      `/products/${product.productId}`,
    );
    expect(createdProductHref({ product, descriptionHtmlSanitized: true })).toBe(
      `/products/${product.productId}?sanitized=1`,
    );
  });
});
