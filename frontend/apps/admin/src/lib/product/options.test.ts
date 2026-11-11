import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { emptySku, HS_CODE_INVALID, VALUE_DUPLICATED, ZERO_ADDITIONAL_REQUIRED } from './create';
import {
  ADD_NO_OPTIONS,
  ADD_NONE_SELECTED,
  ADD_RETIRED_GROUP,
  emptySkuAddForm,
  GROUP_NAME_EN_INVALID,
  OPTIONS_VALIDATION,
  VALUE_NAME_EN_INVALID,
  newValueProblem,
  optionNamesFormOf,
  optionsRequest,
  RETIRE_NONE_SELECTED,
  retireBreaksZeroRule,
  RETIRED_ON_SALE,
  retireRequest,
  retiringOnSaleSkus,
  sameOptionNames,
  sameSkus,
  skuAddBlocked,
  skuAddRequest,
  skuCandidates,
  skuEditRowsOf,
  skusRequest,
} from './options';
import { sectionFailure, underAny } from './section';
import { adminProduct } from './test-fixtures';

describe('옵션 이름 · 순서', () => {
  it('은퇴 행까지 전체를 순서대로 보낸다', () => {
    const groups = optionNamesFormOf(adminProduct()).reverse();
    expect(sameOptionNames(groups, optionNamesFormOf(adminProduct()))).toBe(false);
    const built = optionsRequest(groups, 2);
    expect(built.ok && built.request.optionGroups.map((group) => group.optionGroupId)).toEqual([
      '202',
      '201',
    ]);
    expect(built.ok && built.request.optionGroups[0]?.values).toEqual([
      { optionValueId: '311', name: 'M' },
      { optionValueId: '312', name: 'L' },
    ]);
  });

  it('한 그룹 안의 값 이름이 겹치면 막는다(은퇴 값 포함)', () => {
    const groups = optionNamesFormOf(adminProduct());
    groups[1]!.values[0]!.name = 'L';
    expect(optionsRequest(groups, 0)).toEqual({
      ok: false,
      errors: { 'optionGroups.1.values.1.name': [VALUE_DUPLICATED] },
    });
  });
});

describe('옵션 영문 이름(#707)', () => {
  const sent = (groups: ReturnType<typeof optionNamesFormOf>) => {
    const built = optionsRequest(groups, 4);
    if (!built.ok) throw new Error(JSON.stringify(built.errors));
    return built.request.optionGroups;
  };

  it('저장된 영문 이름을 칸에 채운다(번역 없으면 빈 칸)', () => {
    const [color, size] = optionNamesFormOf(adminProduct());
    expect([color?.nameEn, color?.savedNameEn, color?.values[0]?.nameEn]).toEqual([
      'Color',
      'Color',
      'Indigo',
    ]);
    expect([size?.nameEn, size?.savedNameEn, size?.values[0]?.nameEn]).toEqual(['', null, '']);
  });

  it('손대지 않은 영문 칸은 본문에서 빼 저장된 번역을 지우지 않는다(한국어만 고쳐도)', () => {
    const groups = optionNamesFormOf(adminProduct());
    groups[0]!.name = '색깔';
    groups[0]!.values[0]!.name = '남색';
    const [color, size] = sent(groups);
    expect(color).toEqual({
      optionGroupId: '201',
      name: '색깔',
      values: [{ optionValueId: '301', name: '남색' }],
    });
    expect(color).not.toHaveProperty('nameEn');
    expect(size?.values.every((value) => !('nameEn' in value))).toBe(true);
  });

  it('순서를 바꿔도 영문 이름은 그 행을 따라가고, 앞뒤 공백만 다르면 손대지 않은 것이다', () => {
    const groups = optionNamesFormOf(adminProduct()).reverse();
    groups[1]!.nameEn = '  Color ';
    const request = sent(groups);
    expect(request.map((group) => [group.optionGroupId, 'nameEn' in group])).toEqual([
      ['202', false],
      ['201', false],
    ]);
  });

  it('새로 넣거나 고친 영문 이름은 다듬어 보낸다', () => {
    const groups = optionNamesFormOf(adminProduct());
    groups[0]!.nameEn = ' Colour ';
    groups[1]!.nameEn = 'Size';
    groups[1]!.values[0]!.nameEn = 'Medium';
    expect(sameOptionNames(groups, optionNamesFormOf(adminProduct()))).toBe(false);
    const [color, size] = sent(groups);
    expect(color?.nameEn).toBe('Colour');
    expect(size?.nameEn).toBe('Size');
    expect(size?.values).toEqual([
      { optionValueId: '311', name: 'M', nameEn: 'Medium' },
      { optionValueId: '312', name: 'L' },
    ]);
  });

  it('일부러 비운 영문 칸은 빈 값으로 보내 지운다(공백만 남겨도)', () => {
    const groups = optionNamesFormOf(adminProduct());
    groups[0]!.nameEn = '';
    groups[0]!.values[0]!.nameEn = '   ';
    expect(sameOptionNames(groups, optionNamesFormOf(adminProduct()))).toBe(false);
    const [color] = sent(groups);
    expect(color?.nameEn).toBe('');
    expect(color?.values[0]).toEqual({ optionValueId: '301', name: '쪽빛', nameEn: '' });
  });

  it('영문 칸만 고쳐도 바뀐 것으로 본다', () => {
    const groups = optionNamesFormOf(adminProduct());
    expect(sameOptionNames(groups, optionNamesFormOf(adminProduct()))).toBe(true);
    groups[1]!.values[1]!.nameEn = 'Large';
    expect(sameOptionNames(groups, optionNamesFormOf(adminProduct()))).toBe(false);
  });

  it('길이(그룹 30 · 값 50)와 제어 · 서식 문자를 서버와 같게 미리 막는다', () => {
    const groups = optionNamesFormOf(adminProduct());
    groups[0]!.nameEn = 'a'.repeat(30);
    groups[0]!.values[0]!.nameEn = 'b'.repeat(50);
    expect(optionsRequest(groups, 0).ok).toBe(true);
    groups[0]!.nameEn = 'a'.repeat(31);
    groups[0]!.values[0]!.nameEn = 'b'.repeat(51);
    groups[1]!.nameEn = 'Si\tze';
    groups[1]!.values[0]!.nameEn = 'M\u200b';
    expect(optionsRequest(groups, 0)).toEqual({
      ok: false,
      errors: {
        'optionGroups.0.nameEn': [GROUP_NAME_EN_INVALID],
        'optionGroups.0.values.0.nameEn': [VALUE_NAME_EN_INVALID],
        'optionGroups.1.nameEn': [GROUP_NAME_EN_INVALID],
        'optionGroups.1.values.0.nameEn': [VALUE_NAME_EN_INVALID],
      },
    });
  });

  it('서버 칸 오류(PRODUCT_EDIT_113 · 114)는 해당 영문 칸에 붙인다', () => {
    const cause = new ApiError('validation', 400, 'COMMON_400', '서버 문구', [
      { field: 'optionGroups[1].nameEn', code: 'PRODUCT_EDIT_113', message: '그룹 서버 문구' },
      {
        field: 'optionGroups[0].values[0].nameEn',
        code: 'PRODUCT_EDIT_114',
        message: '값 서버 문구',
      },
    ]);
    expect(
      sectionFailure(cause, { isField: underAny('optionGroups'), validation: OPTIONS_VALIDATION }),
    ).toEqual({
      kind: 'errors',
      errors: {
        'optionGroups.1.nameEn': [GROUP_NAME_EN_INVALID],
        'optionGroups.0.values.0.nameEn': [VALUE_NAME_EN_INVALID],
      },
      reload: false,
    });
  });
});

describe('기존 SKU', () => {
  it('조합 이름 · 은퇴 여부 · 입력 표기로 채우고 HS 코드는 숫자만 보낸다', () => {
    const rows = skuEditRowsOf(adminProduct());
    expect(rows.map((row) => [row.label, row.retired, row.saleStatus])).toEqual([
      ['색상: 쪽빛 · 사이즈: M', false, 'ON_SALE'],
      ['색상: 쪽빛 · 사이즈: L', true, 'STOP_SELLING'],
    ]);
    const built = skusRequest(rows, 'KRW', 3);
    expect(built.ok && built.request.skus[0]).toEqual({
      skuId: '401',
      additionalPrice: { amount: 0, currency: 'KRW' },
      saleStatus: 'ON_SALE',
      weightGrams: 450,
      hsCode: '621142',
    });
    expect(built.ok && built.request.skus[1]).toMatchObject({ weightGrams: null, hsCode: null });
  });

  it('은퇴 옵션 SKU 의 판매중 · 추가금 0 없는 판매중 · HS 형식을 막는다', () => {
    const rows = skuEditRowsOf(adminProduct());
    rows[0] = { ...rows[0]!, additionalPrice: '1000', hsCode: '12' };
    rows[1] = { ...rows[1]!, saleStatus: 'ON_SALE' };
    expect(sameSkus(rows, skuEditRowsOf(adminProduct()))).toBe(false);
    expect(skusRequest(rows, 'KRW', 0)).toEqual({
      ok: false,
      errors: {
        'skus.0.hsCode': [HS_CODE_INVALID],
        'skus.1.saleStatus': [RETIRED_ON_SALE],
        skus: [ZERO_ADDITIONAL_REQUIRED],
      },
    });
  });
});

describe('옵션 값 · 새 조합 추가', () => {
  it('옵션 없는 상품 · 은퇴 옵션이 있는 상품은 막는다', () => {
    expect(skuAddBlocked(adminProduct())).toBeNull();
    expect(skuAddBlocked(adminProduct({ optionGroups: [] }))).toBe(ADD_NO_OPTIONS);
    const product = adminProduct();
    product.optionGroups[0]!.retiredAt = '2026-10-01T00:00:00Z';
    expect(skuAddBlocked(product)).toBe(ADD_RETIRED_GROUP);
  });

  it('은퇴 값과 같은 이름은 더할 수 없다', () => {
    expect(newValueProblem(adminProduct(), emptySkuAddForm(), '202', 'L')).toBe(VALUE_DUPLICATED);
    expect(newValueProblem(adminProduct(), emptySkuAddForm(), '202', 'S')).toBeNull();
  });

  it('이미 있는 조합을 빼고, 고른 조합과 그 조합이 쓰는 새 값만 보낸다', () => {
    const form = {
      newValues: { '202': ['S', 'XL'] },
      selected: {} as Record<string, ReturnType<typeof emptySku>>,
    };
    const candidates = skuCandidates(adminProduct(), form);
    expect(candidates?.map((item) => item.label)).toEqual([
      '색상: 쪽빛 · 사이즈: S',
      '색상: 쪽빛 · 사이즈: XL',
    ]);
    expect(skuAddRequest(adminProduct(), form, 1)).toEqual({
      ok: false,
      errors: { skus: [ADD_NONE_SELECTED] },
    });
    form.selected[candidates![0]!.key] = { ...emptySku(), quantity: '5' };
    expect(skuAddRequest(adminProduct(), form, 1)).toEqual({
      ok: true,
      request: {
        version: 1,
        newOptionValues: [{ optionGroupId: '202', name: 'S' }],
        skus: [
          {
            optionValues: ['쪽빛', 'S'],
            additionalPrice: { amount: 0, currency: 'KRW' },
            saleStatus: 'ON_SALE',
            initialStock: { stockManaged: true, quantity: 5 },
          },
        ],
      },
    });
  });
});

describe('옵션 은퇴', () => {
  it('함께 판매 종료될 판매중 SKU 를 센다', () => {
    expect(
      retiringOnSaleSkus(adminProduct(), { optionGroupIds: [], optionValueIds: ['301'] }),
    ).toBe(1);
    expect(
      retiringOnSaleSkus(adminProduct(), { optionGroupIds: ['202'], optionValueIds: [] }),
    ).toBe(1);
  });

  it('남는 판매중 SKU 에 추가금 0 이 없으면 미리 알린다', () => {
    const product = adminProduct();
    product.skus[1] = {
      ...product.skus[1]!,
      optionValueIds: ['301', '311'],
      saleStatus: { code: 'ON_SALE', name: '판매중', description: '' },
    };
    product.skus[0] = { ...product.skus[0]!, optionValueIds: ['301', '312'] };
    expect(retireBreaksZeroRule(product, { optionGroupIds: [], optionValueIds: ['312'] })).toBe(
      true,
    );
    expect(retireBreaksZeroRule(product, { optionGroupIds: [], optionValueIds: ['301'] })).toBe(
      false,
    );
  });

  it('대상이 없으면 보내지 않는다', () => {
    expect(retireRequest({ optionGroupIds: [], optionValueIds: [] }, 0)).toEqual({
      ok: false,
      errors: { form: [RETIRE_NONE_SELECTED] },
    });
  });
});
