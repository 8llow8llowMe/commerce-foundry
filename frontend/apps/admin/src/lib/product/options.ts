import { moneyToDecimalString, type Currency, type Money } from '@cf/api-client';
import type { FormErrors } from '@/lib/api-errors';
import {
  amountOf,
  AMOUNT_REQUIRED,
  comboKey,
  GROUP_NAME_DUPLICATED,
  GROUP_NAME_INVALID,
  GROUP_NAME_MAX,
  HS_CODE_INVALID,
  hsCodeInput,
  INT_MAX,
  INTEGER,
  isPlainText,
  positiveInt,
  QUANTITY_INVALID,
  SKUS_MAX,
  VALUE_DUPLICATED,
  VALUE_INVALID,
  VALUE_NAME_MAX,
  VALUES_MAX,
  VALUES_TOO_MANY,
  WEIGHT_INVALID,
  ZERO_ADDITIONAL_REQUIRED,
  type SaleStatusCode,
  type SkuForm,
} from './create';
import { skuOptionLabels, STOP_SELLING, type AdminProductDetail } from './detail';
import { addError } from './section';

// 어드민 상품 옵션 · SKU 수정(#29 · BE #9 후속). 한 화면에서 네 가지 쓰기를 따로 저장한다 — 모두 상품 버전 CAS 로 줄을 선다.
// 1. 옵션 이름 · 순서 `PUT .../options`(#576) — 은퇴 행까지 기존 그룹 · 값 전체. 추가 · 누락 · 이동은 400 `PRODUCT_013`.
// 2. 기존 SKU `PUT .../skus`(#573) — 추가금 · 판매 상태 · 무게 · HS 코드 전체 교체. 판매 종료도 여기서 한다.
// 3. 옵션 값 · 새 조합 SKU 추가 `POST .../skus`(#583, 201) — 새 옵션(그룹)은 받지 않는다. 은퇴 옵션이 있는 상품은 `SKU_ADMIN_008`.
// 4. 옵션 은퇴 `POST .../option-retirements`(#585) — 되돌릴 수 없다. 은퇴 값을 쓰는 판매중 SKU 는 함께 판매 종료된다.
// 금액은 계산하지 않는다. "판매중 SKU 중 추가금 0 하나" 는 서버 규칙(`SKU_ADMIN_004`)을 비교로만 미리 본다.

// ---------- 1. 옵션 이름 · 순서 ----------

// 영문 이름 `nameEn`(BE #620, 선택) — 칸이 없거나 null 이면 서버가 저장된 번역을 그대로 두고, 빈 값이면 지운다.
// 화면은 불러온 값(`savedNameEn`)과 다를 때만 싣는다: 손대지 않은 칸은 빼서 그사이 다른 화면이 넣은 번역도 지우지 않고,
// 일부러 비운 칸만 `''` 로 보내 지운다. 등록 · 값 추가 API 는 영문 이름을 받지 않는다(새 행은 번역 없음).

/** BE `ProductOptionsUpdateRequest` — 그룹 30자 · 값 50자(빈 값 허용), 제어 · 서식 문자 금지 */
export const GROUP_NAME_EN_MAX = GROUP_NAME_MAX;
export const VALUE_NAME_EN_MAX = VALUE_NAME_MAX;
export const GROUP_NAME_EN_INVALID = `옵션 영문 이름은 줄바꿈 · 탭 · 보이지 않는 문자 없이 ${GROUP_NAME_EN_MAX}자 이하로 입력해 주세요.`;
export const VALUE_NAME_EN_INVALID = `옵션 값 영문 이름은 줄바꿈 · 탭 · 보이지 않는 문자 없이 ${VALUE_NAME_EN_MAX}자 이하로 입력해 주세요.`;

export type OptionValueNameForm = {
  optionValueId: string;
  name: string;
  /** 입력 칸. 번역이 없으면 '' */
  nameEn: string;
  /** 불러온 저장값 — 보낼지 가르는 기준이다 */
  savedNameEn: string | null;
  retired: boolean;
};
export type OptionGroupNameForm = {
  optionGroupId: string;
  name: string;
  nameEn: string;
  savedNameEn: string | null;
  retired: boolean;
  values: OptionValueNameForm[];
};

export type OptionsRequest = {
  version: number;
  optionGroups: {
    optionGroupId: string;
    name: string;
    /** 빠지면 저장된 번역 유지, '' 면 삭제 */
    nameEn?: string;
    values: { optionValueId: string; name: string; nameEn?: string }[];
  }[];
};

/** 상세 → 폼. 상세는 이미 진열 순서다 */
export function optionNamesFormOf(product: AdminProductDetail): OptionGroupNameForm[] {
  return product.optionGroups.map((group) => ({
    optionGroupId: group.optionGroupId,
    name: group.name,
    nameEn: group.nameEn ?? '',
    savedNameEn: group.nameEn,
    retired: group.retiredAt !== null,
    values: group.values.map((value) => ({
      optionValueId: value.optionValueId,
      name: value.name,
      nameEn: value.nameEn ?? '',
      savedNameEn: value.nameEn,
      retired: value.retiredAt !== null,
    })),
  }));
}

/**
 * 영문 이름 칸 → 본문 조각. 불러온 값과 같으면(손대지 않음) 칸을 빼 보존하고, 다르면 다듬은 값을 싣는다 — 비웠으면 `''`(삭제).
 * 형식이 틀리면 칸 오류를 더한다.
 */
function nameEnPart(
  row: { nameEn: string; savedNameEn: string | null },
  max: number,
  key: string,
  message: string,
  errors: FormErrors,
): { nameEn?: string } {
  const typed = row.nameEn.trim();
  if (typed.length > max || !isPlainText(typed)) addError(errors, key, message);
  return typed === (row.savedNameEn ?? '').trim() ? {} : { nameEn: typed };
}

export type BuildOptions =
  { ok: true; request: OptionsRequest } | { ok: false; errors: FormErrors };

/** 폼 → PUT 본문. 칸 오류 키는 `optionGroups.0.name` · `optionGroups.0.nameEn` · `optionGroups.0.values.1.name` */
export function optionsRequest(groups: OptionGroupNameForm[], version: number): BuildOptions {
  const errors: FormErrors = {};
  const groupNames = new Set<string>();
  const optionGroups = groups.map((group, index) => {
    const name = group.name.trim();
    if (name === '' || name.length > GROUP_NAME_MAX || !isPlainText(name)) {
      addError(errors, `optionGroups.${index}.name`, GROUP_NAME_INVALID);
    } else if (groupNames.has(name)) {
      addError(errors, `optionGroups.${index}.name`, GROUP_NAME_DUPLICATED);
    }
    groupNames.add(name);
    const groupEn = nameEnPart(
      group,
      GROUP_NAME_EN_MAX,
      `optionGroups.${index}.nameEn`,
      GROUP_NAME_EN_INVALID,
      errors,
    );
    const valueNames = new Set<string>();
    const values = group.values.map((value, at) => {
      const valueName = value.name.trim();
      const key = `optionGroups.${index}.values.${at}`;
      if (valueName === '' || valueName.length > VALUE_NAME_MAX || !isPlainText(valueName)) {
        addError(errors, `${key}.name`, VALUE_INVALID);
      } else if (valueNames.has(valueName)) addError(errors, `${key}.name`, VALUE_DUPLICATED);
      valueNames.add(valueName);
      const valueEn = nameEnPart(
        value,
        VALUE_NAME_EN_MAX,
        `${key}.nameEn`,
        VALUE_NAME_EN_INVALID,
        errors,
      );
      return { optionValueId: value.optionValueId, name: valueName, ...valueEn };
    });
    return { optionGroupId: group.optionGroupId, name, ...groupEn, values };
  });
  if (Object.keys(errors).length > 0) return { ok: false, errors };
  return { ok: true, request: { version, optionGroups } };
}

export function sameOptionNames(a: OptionGroupNameForm[], b: OptionGroupNameForm[]): boolean {
  const body = (groups: OptionGroupNameForm[]) =>
    JSON.stringify(
      groups.map((group) => [
        group.optionGroupId,
        group.name.trim(),
        group.nameEn.trim(),
        group.values.map((value) => [value.optionValueId, value.name.trim(), value.nameEn.trim()]),
      ]),
    );
  return body(a) === body(b);
}

/** 옵션 이름 요청 검증 코드 → 문구 */
export const OPTIONS_VALIDATION: Record<string, string> = {
  PRODUCT_013: '옵션 구성이 바뀌었습니다. 최신 내용을 불러온 뒤 다시 고쳐 주세요.',
  PRODUCT_EDIT_113: GROUP_NAME_EN_INVALID,
  PRODUCT_EDIT_114: VALUE_NAME_EN_INVALID,
};

// ---------- 2. 기존 SKU ----------

export type SkuEditRow = {
  skuId: string;
  /** "색상: 쪽빛 · 사이즈: M" */
  label: string;
  /** 은퇴한 옵션을 쓴다 — 판매중으로 바꿀 수 없다(`SKU_ADMIN_006`) */
  retired: boolean;
  additionalPrice: string;
  saleStatus: SaleStatusCode;
  weightGrams: string;
  hsCode: string;
};

export type SkusRequest = {
  version: number;
  skus: {
    skuId: string;
    additionalPrice: Money;
    saleStatus: SaleStatusCode;
    weightGrams: number | null;
    hsCode: string | null;
  }[];
};

export function skuEditRowsOf(product: AdminProductDetail): SkuEditRow[] {
  const labels = skuOptionLabels(product);
  return product.skus.map((sku) => ({
    skuId: sku.skuId,
    label: labels.get(sku.skuId)?.text ?? '',
    retired: labels.get(sku.skuId)?.retired ?? false,
    additionalPrice: moneyToDecimalString(sku.additionalPrice),
    saleStatus: sku.saleStatus.code === STOP_SELLING ? 'STOP_SELLING' : 'ON_SALE',
    weightGrams: sku.weightGrams === null ? '' : String(sku.weightGrams),
    hsCode: sku.hsCode ?? '',
  }));
}

export const RETIRED_ON_SALE = '은퇴한 옵션을 쓰는 SKU 는 판매중으로 바꿀 수 없습니다.';

/** 줄 하나의 추가금 · 무게 · HS 코드 검사. 등록 · 추가와 같은 규칙이다 */
function skuFields(
  row: Pick<SkuForm, 'additionalPrice' | 'weightGrams' | 'hsCode'>,
  currency: Currency,
  at: (field: string) => string,
  errors: FormErrors,
) {
  const additional = amountOf(row.additionalPrice, currency);
  if (additional.ok === null) addError(errors, at('additionalPrice'), AMOUNT_REQUIRED);
  else if (!additional.ok) addError(errors, at('additionalPrice'), additional.message);
  const weight = positiveInt(row.weightGrams, INT_MAX);
  if (Number.isNaN(weight)) addError(errors, at('weightGrams'), WEIGHT_INVALID);
  const hsCode = hsCodeInput(row.hsCode);
  if (hsCode === false) addError(errors, at('hsCode'), HS_CODE_INVALID);
  return {
    additionalPrice: additional.ok ? { amount: additional.amount, currency } : null,
    weightGrams: typeof weight === 'number' && !Number.isNaN(weight) ? weight : null,
    hsCode: hsCode === false ? null : hsCode,
  };
}

/** 판매중 줄이 있으면 그중 하나는 추가금 0 이어야 한다(서버 `SKU_ADMIN_004` 를 미리 본다). 금액을 못 읽은 줄이 있으면 따지지 않는다 */
function zeroRuleBroken(
  rows: readonly { additionalPrice: string; saleStatus: SaleStatusCode }[],
  currency: Currency,
): boolean {
  const amounts = rows.map((row) => ({
    onSale: row.saleStatus === 'ON_SALE',
    amount: amountOf(row.additionalPrice, currency),
  }));
  if (amounts.some(({ amount }) => amount.ok !== true)) return false;
  const onSale = amounts.filter((row) => row.onSale);
  return (
    onSale.length > 0 && !onSale.some(({ amount }) => amount.ok === true && amount.amount === 0)
  );
}

export type BuildSkus = { ok: true; request: SkusRequest } | { ok: false; errors: FormErrors };

/** 폼 → PUT 본문. 기존 SKU 전부를 보낸다. 칸 오류 키는 `skus.0.additionalPrice` */
export function skusRequest(rows: SkuEditRow[], currency: Currency, version: number): BuildSkus {
  const errors: FormErrors = {};
  const skus = rows.map((row, index) => {
    const at = (field: string) => `skus.${index}.${field}`;
    if (row.retired && row.saleStatus === 'ON_SALE')
      addError(errors, at('saleStatus'), RETIRED_ON_SALE);
    const fields = skuFields(row, currency, at, errors);
    return { skuId: row.skuId, saleStatus: row.saleStatus, ...fields };
  });
  if (zeroRuleBroken(rows, currency)) addError(errors, 'skus', ZERO_ADDITIONAL_REQUIRED);
  if (Object.keys(errors).length > 0) return { ok: false, errors };
  return {
    ok: true,
    request: {
      version,
      skus: skus.map(({ additionalPrice, ...rest }) => ({
        ...rest,
        additionalPrice: additionalPrice as Money,
      })),
    },
  };
}

export function sameSkus(a: SkuEditRow[], b: SkuEditRow[]): boolean {
  const body = (rows: SkuEditRow[]) =>
    JSON.stringify(
      rows.map((row) => [
        row.skuId,
        row.additionalPrice.trim(),
        row.saleStatus,
        row.weightGrams.trim(),
        row.hsCode.trim(),
      ]),
    );
  return body(a) === body(b);
}

/** SKU 저장 · 추가 요청 검증 코드 → 문구(서버 문구가 더 자세한 업무 규칙 오류는 그대로 둔다) */
export const SKUS_VALIDATION: Record<string, string> = {
  SKU_ADMIN_003: 'SKU 목록이 바뀌었습니다. 최신 내용을 불러온 뒤 다시 고쳐 주세요.',
  SKU_ADMIN_006: RETIRED_ON_SALE,
  SKU_ADMIN_106: WEIGHT_INVALID,
  SKU_ADMIN_107: HS_CODE_INVALID,
  SKU_ADMIN_115: QUANTITY_INVALID,
};

// ---------- 3. 옵션 값 · 새 조합 SKU 추가 ----------

/** 새 조합 SKU 한 줄 — 등록의 SKU 칸과 같다(처음 재고 포함) */
export type NewSkuForm = SkuForm;

export type SkuAddForm = {
  /** 그룹 아이디 → 이번에 더할 값 이름(입력 순서 = 그룹 끝에 붙는 순서) */
  newValues: Record<string, string[]>;
  /** 고른 조합(`comboKey`) → SKU 칸 */
  selected: Record<string, NewSkuForm>;
};

export const emptySkuAddForm = (): SkuAddForm => ({ newValues: {}, selected: {} });

export type SkuAddRequest = {
  version: number;
  newOptionValues: { optionGroupId: string; name: string }[];
  skus: {
    optionValues: string[];
    additionalPrice: Money;
    saleStatus: SaleStatusCode;
    weightGrams?: number;
    hsCode?: string;
    initialStock?: { stockManaged: boolean; quantity: number };
  }[];
};

export const ADD_NO_OPTIONS = '옵션이 없는 상품에는 SKU 를 더할 수 없습니다.';
export const ADD_RETIRED_GROUP =
  '은퇴한 옵션이 있는 상품에는 SKU 를 더할 수 없습니다. 옵션 구조를 바꾸려면 새 상품으로 등록해 주세요.';
export const ADD_FULL = `SKU 는 상품당 ${SKUS_MAX}개까지입니다.`;
export const ADD_NONE_SELECTED = '더할 조합을 하나 이상 골라 주세요.';
/** 화면에 늘어놓을 조합 수 상한 — 넘으면 값을 줄이라고 안내한다 */
export const CANDIDATES_MAX = 300;
export const CANDIDATES_TOO_MANY = `만들 수 있는 조합이 ${CANDIDATES_MAX}개를 넘어 늘어놓지 않습니다. 더할 값을 줄여 주세요.`;

/** 추가를 막는 이유. 없으면 null */
export function skuAddBlocked(product: AdminProductDetail): string | null {
  if (product.optionGroups.length === 0) return ADD_NO_OPTIONS;
  if (product.optionGroups.some((group) => group.retiredAt !== null)) return ADD_RETIRED_GROUP;
  if (product.skus.length >= SKUS_MAX) return ADD_FULL;
  return null;
}

/** 그룹에 새 값 이름을 더할 수 있나 — 막히면 문구. 은퇴 값과도 이름이 겹치면 안 된다(서버 고유 제약) */
export function newValueProblem(
  product: AdminProductDetail,
  form: SkuAddForm,
  optionGroupId: string,
  raw: string,
): string | null {
  const name = raw.trim();
  const group = product.optionGroups.find((item) => item.optionGroupId === optionGroupId);
  if (!group || name === '' || name.length > VALUE_NAME_MAX || !isPlainText(name)) {
    return VALUE_INVALID;
  }
  const added = form.newValues[optionGroupId] ?? [];
  if (group.values.some((value) => value.name === name) || added.includes(name)) {
    return VALUE_DUPLICATED;
  }
  if (group.values.length + added.length >= VALUES_MAX) return VALUES_TOO_MANY;
  return null;
}

export type SkuCandidate = { key: string; values: string[]; label: string; usesNew: boolean };

/**
 * 더할 수 있는 조합 — 그룹 진열 순서로 (쓰는 중인 값 + 새 값)의 곱에서 이미 있는 조합(판매 종료 포함)을 뺀다.
 * 너무 많으면 null(화면이 안내한다).
 */
export function skuCandidates(
  product: AdminProductDetail,
  form: SkuAddForm,
): SkuCandidate[] | null {
  const groups = product.optionGroups.map((group) => {
    const active = group.values.filter((value) => value.retiredAt === null).map((v) => v.name);
    const added = form.newValues[group.optionGroupId] ?? [];
    return { name: group.name, values: [...active, ...added], added: new Set(added) };
  });
  const total = groups.reduce((count, group) => count * group.values.length, 1);
  if (total > CANDIDATES_MAX + product.skus.length) return null;

  const names = new Map(
    product.optionGroups.flatMap((group) =>
      group.values.map((value) => [value.optionValueId, { group, name: value.name }] as const),
    ),
  );
  const existing = new Set(
    product.skus.map((sku) =>
      comboKey(
        product.optionGroups.map(
          (group) =>
            sku.optionValueIds.map((id) => names.get(id)).find((entry) => entry?.group === group)
              ?.name ?? '',
        ),
      ),
    ),
  );

  let combos: string[][] = [[]];
  for (const group of groups) {
    combos = combos.flatMap((prefix) => group.values.map((value) => [...prefix, value]));
  }
  const candidates = combos
    .filter((values) => !existing.has(comboKey(values)))
    .map((values) => ({
      key: comboKey(values),
      values,
      label: values.map((value, index) => `${groups[index]?.name}: ${value}`).join(' · '),
      usesNew: values.some((value, index) => groups[index]?.added.has(value) ?? false),
    }));
  return candidates.length > CANDIDATES_MAX ? null : candidates;
}

export type BuildSkuAdd = { ok: true; request: SkuAddRequest } | { ok: false; errors: FormErrors };

/**
 * 폼 → POST 본문. 보내는 SKU 는 후보 순서로 고른 것만이고, 칸 오류 키(`skus.0.additionalPrice`)는 보내는 순서의 번호다.
 * 아무 조합도 쓰지 않는 새 값은 보내지 않는다(값만 따로 더하는 API 가 아니다).
 */
export function skuAddRequest(
  product: AdminProductDetail,
  form: SkuAddForm,
  version: number,
): BuildSkuAdd {
  const errors: FormErrors = {};
  const currency = product.salePrice.currency;
  const picked = (skuCandidates(product, form) ?? []).filter((item) => form.selected[item.key]);
  if (picked.length === 0) addError(errors, 'skus', ADD_NONE_SELECTED);
  if (product.skus.length + picked.length > SKUS_MAX) addError(errors, 'skus', ADD_FULL);

  const skus: SkuAddRequest['skus'] = picked.map((candidate, index) => {
    const sku = form.selected[candidate.key] as NewSkuForm;
    const at = (field: string) => `skus.${index}.${field}`;
    const fields = skuFields(sku, currency, at, errors);
    const item: SkuAddRequest['skus'][number] = {
      optionValues: candidate.values,
      additionalPrice: fields.additionalPrice ?? { amount: 0, currency },
      saleStatus: sku.saleStatus,
    };
    if (fields.weightGrams !== null) item.weightGrams = fields.weightGrams;
    if (fields.hsCode !== null) item.hsCode = fields.hsCode;
    if (sku.stockMode === 'unlimited') item.initialStock = { stockManaged: false, quantity: 0 };
    else if (sku.quantity.trim() !== '') {
      const raw = sku.quantity.trim();
      const quantity = Number(raw);
      if (!INTEGER.test(raw) || !Number.isSafeInteger(quantity)) {
        addError(errors, at('initialStock'), QUANTITY_INVALID);
      } else item.initialStock = { stockManaged: true, quantity };
    }
    return item;
  });

  const used = new Set(
    picked.flatMap((candidate) => candidate.values.map((v, i) => `${i}\u0000${v}`)),
  );
  const newOptionValues = product.optionGroups.flatMap((group, index) =>
    (form.newValues[group.optionGroupId] ?? [])
      .filter((name) => used.has(`${index}\u0000${name}`))
      .map((name) => ({ optionGroupId: group.optionGroupId, name })),
  );

  if (Object.keys(errors).length > 0) return { ok: false, errors };
  return { ok: true, request: { version, newOptionValues, skus } };
}

// ---------- 4. 옵션 은퇴 ----------

export type RetireForm = { optionGroupIds: string[]; optionValueIds: string[] };

export type RetireRequest = { version: number; optionGroupIds: string[]; optionValueIds: string[] };

export const RETIRE_NONE_SELECTED = '은퇴할 옵션이나 값을 하나 이상 골라 주세요.';

export function retireRequest(
  form: RetireForm,
  version: number,
): { ok: true; request: RetireRequest } | { ok: false; errors: FormErrors } {
  if (form.optionGroupIds.length === 0 && form.optionValueIds.length === 0) {
    return { ok: false, errors: { form: [RETIRE_NONE_SELECTED] } };
  }
  return { ok: true, request: { version, ...form } };
}

/** 은퇴하면 함께 판매 종료되는 판매중 SKU 수 — 화면이 미리 알린다 */
export function retiringOnSaleSkus(product: AdminProductDetail, form: RetireForm): number {
  const groups = new Set(form.optionGroupIds);
  const values = new Set(form.optionValueIds);
  for (const group of product.optionGroups) {
    if (groups.has(group.optionGroupId)) group.values.forEach((v) => values.add(v.optionValueId));
  }
  return product.skus.filter(
    (sku) =>
      sku.saleStatus.code !== STOP_SELLING && sku.optionValueIds.some((id) => values.has(id)),
  ).length;
}

/**
 * 은퇴 뒤 남는 판매중 SKU 에 추가금 0 이 없어지나 — 서버가 `SKU_ADMIN_004` 로 막는다(남는 판매중 SKU 가 없으면 따지지 않는다).
 * 화면은 미리 알리고 가격 · SKU 를 먼저 맞추게 한다.
 */
export function retireBreaksZeroRule(product: AdminProductDetail, form: RetireForm): boolean {
  const groups = new Set(form.optionGroupIds);
  const values = new Set(form.optionValueIds);
  for (const group of product.optionGroups) {
    if (groups.has(group.optionGroupId)) group.values.forEach((v) => values.add(v.optionValueId));
  }
  const remaining = product.skus.filter(
    (sku) =>
      sku.saleStatus.code !== STOP_SELLING && !sku.optionValueIds.some((id) => values.has(id)),
  );
  return remaining.length > 0 && !remaining.some((sku) => sku.additionalPrice.amount === 0);
}
