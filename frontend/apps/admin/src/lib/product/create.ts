import { parseMoneyInput, type Currency, type Money } from '@cf/api-client';
import type { FormErrors } from '@/lib/api-errors';
import type { CategoryRow } from '@/lib/category/tree';
import type { AdminProductDetail } from './detail';
import { ADMIN_PRODUCTS_PATH } from './query';

// 어드민 상품 등록(#29 · BE #9 E1 · 이미지 첨부 F2) — 폼 → `POST /api/v1/admin/products` 본문.
// 계약 정본은 `backend/docs/api-design-guide.md` "어드민 상품 등록", 칸 규칙 정본은 BE `ProductCreateRequest` · `ProductWriteProcessor` 다.
// 화면은 같은 규칙으로 미리 막아 저장을 누른 뒤에야 알게 되는 일을 줄일 뿐이고, 판정은 서버다.
// 금액은 계산하지 않는다 — 글자를 최소 단위 정수로 바꾸고(`parseMoneyInput`) 서버 규칙(정가 > 판매가 · 추가금 0)을 비교로만 본다.

export const CREATE_PRODUCT_PATH = ADMIN_PRODUCTS_PATH;

export const CURRENCIES: readonly Currency[] = ['KRW', 'USD'];
export type SaleStatusCode = 'ON_SALE' | 'STOP_SELLING';
export const SALE_STATUS_LABELS: Record<SaleStatusCode, string> = {
  ON_SALE: '판매중',
  STOP_SELLING: '판매 종료',
};

/** BE `ProductCreateRequest` 상한 */
export const NAME_MAX = 100;
export const SUMMARY_MAX = 200;
export const DESCRIPTION_MAX = 200_000;
export const CATEGORY_IDS_MAX = 20;
export const MAX_QUANTITY_MAX = 999;
export const GROUPS_MAX = 5;
export const GROUP_NAME_MAX = 30;
export const VALUES_MAX = 50;
export const VALUE_NAME_MAX = 50;
/** SKU 1~100개(BE `PRODUCT_095`) — 옵션 조합 수가 곧 SKU 수라 조합 상한이다 */
export const SKUS_MAX = 100;
/** 이미지 최대 8장(대표 포함, BE `PRODUCT_019`) · 대체 텍스트 200자 */
export const IMAGES_MAX = 8;
export const ALT_TEXT_MAX = 200;
/** 금액 상한(최소 화폐 단위, BE `PRODUCT_037`) */
export const AMOUNT_MAX = 100_000_000;
export const INT_MAX = 2_147_483_647;

/** 옵션 그룹 한 줄. `id` 는 화면 key 용(서버로 보내지 않는다) */
export type OptionGroupForm = { id: string; name: string; values: string[] };

/** SKU 한 줄(옵션 조합 하나). 처음 재고 수량을 비우면 재고를 등록하지 않는다 — 품절로 생긴다 */
export type SkuForm = {
  additionalPrice: string;
  saleStatus: SaleStatusCode;
  weightGrams: string;
  hsCode: string;
  /** quantity — 수량 관리, unlimited — 재고 관리 안 함(무제한 판매) */
  stockMode: 'quantity' | 'unlimited';
  quantity: string;
};

/** BE `ImageType` 중 등록이 받는 정지 이미지 둘(영상은 후속) */
export type ProductImageType = 'THUMBNAIL' | 'GALLERY';

/**
 * 이미지 칸 하나. `upload` 는 업로드 칸이 준 값이다 — 서버로는 `uploadKey` 만 가고 `file` 은 미리보기용이다.
 * 올리기 전 · 뺀 칸은 `upload` 가 null 이고 보내지 않는다. `id` 는 화면 key 용.
 */
export type ImageSlot = {
  id: string;
  upload: { uploadKey: string; file: File } | null;
  altText: string;
};

export const emptyImageSlot = (id: string): ImageSlot => ({ id, upload: null, altText: '' });

export type ProductCreateForm = {
  name: string;
  summary: string;
  descriptionHtml: string;
  /** 대표 카테고리 아이디. 고르기 전에는 '' */
  categoryId: string;
  /** 진열 카테고리(대표 제외) */
  categoryIds: string[];
  saleStatus: SaleStatusCode;
  visible: boolean;
  currency: Currency;
  salePrice: string;
  listPrice: string;
  maxQuantityPerOrder: string;
  preorder: boolean;
  /** `yyyy-MM-dd` */
  expectedShipDate: string;
  optionGroups: OptionGroupForm[];
  /** 조합 키(`comboKey`) → SKU 칸. 옵션을 바꿔도 같은 조합이면 입력이 남는다 */
  skus: Record<string, SkuForm>;
  /** 대표 이미지(THUMBNAIL) 한 칸 */
  thumbnail: ImageSlot;
  /** 추가 이미지(GALLERY). 배열 순서 = 노출 순서, 대표와 합쳐 `IMAGES_MAX` 장까지 */
  gallery: ImageSlot[];
};

export type MoneyBody = Money;

/** BE `ProductCreateRequest` */
export type ProductCreateRequest = {
  name: string;
  summary?: string;
  descriptionHtml?: string;
  categoryId: string;
  categoryIds?: string[];
  saleStatus: SaleStatusCode;
  visible: boolean;
  salePrice: MoneyBody;
  listPrice?: MoneyBody;
  maxQuantityPerOrder?: number;
  preorder: boolean;
  expectedShipDate?: string;
  optionGroups: { name: string; values: string[] }[];
  skus: {
    optionValues: string[];
    additionalPrice: MoneyBody;
    saleStatus: SaleStatusCode;
    weightGrams?: number;
    hsCode?: string;
    initialStock?: { stockManaged: boolean; quantity: number };
  }[];
  images?: { uploadKey: string; imageType: ProductImageType; altText?: string }[];
};

/** 201 `ProductCreateAdminResponse` */
export type ProductCreateResponse = {
  product: AdminProductDetail;
  descriptionHtmlSanitized: boolean;
};

export const emptySku = (): SkuForm => ({
  additionalPrice: '0',
  saleStatus: 'ON_SALE',
  weightGrams: '',
  hsCode: '',
  stockMode: 'quantity',
  quantity: '',
});

export function emptyProductForm(): ProductCreateForm {
  return {
    name: '',
    summary: '',
    descriptionHtml: '',
    categoryId: '',
    categoryIds: [],
    saleStatus: 'ON_SALE',
    // 등록하자마자 고객에게 보이지 않게 숨김으로 시작한다 — 확인한 뒤 노출로 바꾼다
    visible: false,
    currency: 'KRW',
    salePrice: '',
    listPrice: '',
    maxQuantityPerOrder: '',
    preorder: false,
    expectedShipDate: '',
    optionGroups: [],
    skus: {},
    thumbnail: emptyImageSlot('thumbnail'),
    gallery: [],
  };
}

// ---------- 이미지 ----------

export type ImageEntry = {
  slot: ImageSlot;
  uploadKey: string;
  imageType: ProductImageType;
  index: number;
};

/**
 * 보낼 이미지 — 올린 칸만, 대표 → 추가 이미지 순서. `index` 는 본문 `images` 의 번호라
 * 칸 오류 키(`images.{index}.altText`)와 서버 `fieldErrors`(`images[1].altText`)가 같은 번호를 쓴다.
 */
export function imageEntries(form: Pick<ProductCreateForm, 'thumbnail' | 'gallery'>): ImageEntry[] {
  const slots: { slot: ImageSlot; imageType: ProductImageType }[] = [
    { slot: form.thumbnail, imageType: 'THUMBNAIL' },
    ...form.gallery.map((slot) => ({ slot, imageType: 'GALLERY' as const })),
  ];
  return slots
    .flatMap(({ slot, imageType }) =>
      slot.upload ? [{ slot, uploadKey: slot.upload.uploadKey, imageType }] : [],
    )
    .map((entry, index) => ({ ...entry, index }));
}

// ---------- 옵션 조합 ----------

/** 조합 키 — 값 이름 배열을 그대로 문자열로(값 이름에 어떤 글자가 와도 겹치지 않게 JSON) */
export const comboKey = (values: readonly string[]) => JSON.stringify(values);

/** 값이 있는 그룹 기준 조합 수. 그룹이 없으면 1(기본 SKU 하나) */
export function combinationCount(groups: readonly OptionGroupForm[]): number {
  return groups.reduce((count, group) => count * Math.max(group.values.length, 1), 1);
}

/**
 * 옵션 그룹 → SKU 조합(카테시안 곱), 그룹 순서 · 값 입력 순서대로. 옵션이 없으면 `[[]]`(기본 SKU 하나).
 * 값이 아직 없는 그룹은 건너뛴다 — 그 그룹은 등록 전 검증에서 막힌다.
 */
export function combinations(groups: readonly OptionGroupForm[]): string[][] {
  let result: string[][] = [[]];
  for (const group of groups) {
    if (group.values.length === 0) continue;
    result = result.flatMap((prefix) => group.values.map((value) => [...prefix, value]));
  }
  return result;
}

export type SkuRow = { key: string; values: string[]; sku: SkuForm };

/** 지금 옵션의 SKU 줄. 같은 조합의 입력이 있으면 그것, 없으면 기본값이다 */
export function skuRows(form: ProductCreateForm): SkuRow[] {
  return combinations(form.optionGroups).map((values) => {
    const key = comboKey(values);
    return { key, values, sku: form.skus[key] ?? emptySku() };
  });
}

/** SKU 줄 이름 — "색상: 쪽빛 · 사이즈: M", 옵션이 없으면 "기본" */
export function skuLabel(groups: readonly OptionGroupForm[], values: readonly string[]): string {
  if (values.length === 0) return '기본';
  const named = groups.filter((group) => group.values.length > 0);
  return values
    .map((value, index) => {
      const name = named[index]?.name.trim();
      return name ? `${name}: ${value}` : value;
    })
    .join(' · ');
}

export const COMBINATIONS_TOO_MANY = `옵션 조합(SKU)은 ${SKUS_MAX}개까지입니다. 값이나 그룹을 줄여 주세요.`;
export const GROUPS_TOO_MANY = `옵션 그룹은 ${GROUPS_MAX}개까지입니다.`;
export const VALUES_TOO_MANY = `옵션 값은 그룹마다 ${VALUES_MAX}개까지입니다.`;
export const VALUE_DUPLICATED = '이 그룹에 이미 있는 값입니다.';
export const VALUE_INVALID = `옵션 값은 줄바꿈 · 탭 · 보이지 않는 문자 없이 1~${VALUE_NAME_MAX}자로 입력해 주세요.`;

/**
 * 그룹에 값을 더한다. 막히면 이유 문구, 되면 새 그룹 목록이다.
 * 조합 수가 상한을 넘으면 막는다 — 등록 뒤 SKU 를 줄일 수 없어(수정 API 전) 미리 막는 편이 낫다.
 */
export function addOptionValue(
  groups: readonly OptionGroupForm[],
  groupId: string,
  raw: string,
): { ok: true; groups: OptionGroupForm[] } | { ok: false; message: string } {
  const value = raw.trim();
  const group = groups.find((item) => item.id === groupId);
  if (!group) return { ok: false, message: VALUE_INVALID };
  if (value === '' || value.length > VALUE_NAME_MAX || !isPlainText(value)) {
    return { ok: false, message: VALUE_INVALID };
  }
  if (group.values.includes(value)) return { ok: false, message: VALUE_DUPLICATED };
  if (group.values.length >= VALUES_MAX) return { ok: false, message: VALUES_TOO_MANY };
  const next = groups.map((item) =>
    item.id === groupId ? { ...item, values: [...item.values, value] } : item,
  );
  if (combinationCount(next) > SKUS_MAX) return { ok: false, message: COMBINATIONS_TOO_MANY };
  return { ok: true, groups: next };
}

// ---------- 검증 · 본문 ----------

// BE `ProductValidationMessage.PLAIN_TEXT` — 제어 · 서식 · 대리 · 줄/문단 구분 문자 금지
const NOT_PLAIN = /[\p{Cc}\p{Cf}\p{Cs}\p{Zl}\p{Zp}]/u;
export const isPlainText = (text: string) => !NOT_PLAIN.test(text);
export const INTEGER = /^\d+$/;
const HS_SEPARATORS = /[.\- ]/g;
const HS_DIGITS = /^[0-9]{6,10}$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

export const CHECK_FIELDS = '입력값을 확인해 주세요.';
export const NAME_REQUIRED = '상품명을 입력해 주세요.';
export const NAME_TOO_LONG = `상품명은 ${NAME_MAX}자 이하로 입력해 주세요.`;
export const NAME_CHARACTER = '상품명에는 줄바꿈 · 탭 · 보이지 않는 문자를 쓸 수 없습니다.';
export const SUMMARY_INVALID = `요약은 줄바꿈 · 탭 · 보이지 않는 문자 없이 ${SUMMARY_MAX}자 이하로 입력해 주세요.`;
export const DESCRIPTION_TOO_LONG = `상세 설명은 ${DESCRIPTION_MAX.toLocaleString('ko-KR')}자 이하여야 합니다.`;
export const CATEGORY_REQUIRED = '대표 카테고리를 골라 주세요.';
export const CATEGORY_IDS_TOO_MANY = `진열 카테고리는 대표 외에 ${CATEGORY_IDS_MAX}개까지입니다.`;
export const CATEGORY_INVALID = '카테고리를 다시 골라 주세요.';
export const SALE_STATUS_REQUIRED = '판매 상태를 골라 주세요.';
export const VISIBLE_REQUIRED = '노출 여부를 골라 주세요.';
export const PRICE_REQUIRED = '판매가를 입력해 주세요. 0원이면 0 을 입력합니다.';
export const AMOUNT_REQUIRED = '금액을 입력해 주세요. 0원이면 0 을 입력합니다.';
export const AMOUNT_FORMAT = '금액 형식이 올바르지 않습니다. 0 이상의 숫자로 입력해 주세요.';
export const AMOUNT_TOO_LARGE = '금액이 너무 큽니다. 원(센트) 기준 1억 이하로 입력해 주세요.';
export const CURRENCY_REQUIRED = '통화를 골라 주세요.';
export const LIST_PRICE_NOT_HIGHER = '정가는 판매가보다 높아야 합니다. 할인이 없으면 비워 두세요.';
export const MAX_QUANTITY_RANGE = `1회 최대 주문 수량은 1~${MAX_QUANTITY_MAX} 사이의 정수입니다.`;
export const SHIP_DATE_FORMAT = '발송 예정일을 날짜로 입력해 주세요.';
export const SHIP_DATE_PAST = '발송 예정일은 오늘 이후여야 합니다.';
export const SHIP_DATE_WITHOUT_PREORDER = '발송 예정일은 프리오더 상품에만 둘 수 있습니다.';
export const GROUP_NAME_INVALID = `옵션 이름은 줄바꿈 · 탭 · 보이지 않는 문자 없이 1~${GROUP_NAME_MAX}자로 입력해 주세요.`;
export const GROUP_NAME_DUPLICATED = '옵션 이름이 겹칩니다.';
export const GROUP_VALUES_REQUIRED = `옵션 값을 1~${VALUES_MAX}개 넣어 주세요.`;
export const SKUS_SIZE = `SKU 는 1~${SKUS_MAX}개여야 합니다.`;
export const SKU_OPTION_INVALID =
  'SKU 의 옵션 조합이 올바르지 않습니다. 옵션을 다시 확인해 주세요.';
export const WEIGHT_INVALID = '무게는 1 이상의 정수(g)로 입력해 주세요.';
export const HS_CODE_INVALID = 'HS 코드는 점 · 하이픈 · 공백을 뺀 숫자 6~10자리입니다.';
export const QUANTITY_INVALID = '처음 재고는 0 이상의 정수로 입력해 주세요.';
export const INITIAL_STOCK_INVALID = '처음 재고를 다시 입력해 주세요.';
export const IMAGES_TOO_MANY = `이미지는 대표를 포함해 ${IMAGES_MAX}장까지입니다.`;
export const ALT_TEXT_INVALID = `대체 텍스트는 줄바꿈 · 탭 · 보이지 않는 문자 없이 ${ALT_TEXT_MAX}자 이하로 입력해 주세요.`;
export const ZERO_ADDITIONAL_REQUIRED =
  '판매중 SKU 중 하나는 추가금이 0 이어야 합니다. 판매가가 판매중 SKU 의 가장 낮은 가격입니다.';

/** `yyyy-MM-dd` 오늘(이 컴퓨터 시간대). 서버는 자기 날짜로 다시 본다 */
export function localToday(now: Date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

export type Amount = { ok: true; amount: number } | { ok: false; message: string } | { ok: null };

export function amountOf(text: string, currency: Currency): Amount {
  const amount = parseMoneyInput(text, currency);
  if (amount === null) return { ok: null };
  if (Number.isNaN(amount)) return { ok: false, message: AMOUNT_FORMAT };
  if (amount > AMOUNT_MAX) return { ok: false, message: AMOUNT_TOO_LARGE };
  return { ok: true, amount };
}

export function positiveInt(text: string, max: number): number | null {
  const raw = text.trim();
  if (raw === '') return null;
  if (!INTEGER.test(raw)) return Number.NaN;
  const value = Number(raw);
  return value >= 1 && value <= max ? value : Number.NaN;
}

export type BuildProduct =
  { ok: true; request: ProductCreateRequest } | { ok: false; errors: FormErrors };

/**
 * 폼 → POST 본문. 칸 오류 키는 서버 `fieldErrors` 를 옮긴 키(`createFailure`)와 같다 — `skus.0.additionalPrice` 처럼
 * SKU 는 지금 조합 순서(=보내는 순서)의 번호다. `today` 는 발송 예정일 미리 보기용(서버 날짜와 다를 수 있다).
 */
export function productRequest(
  form: ProductCreateForm,
  today: string = localToday(),
): BuildProduct {
  const errors: FormErrors = {};
  const add = (key: string, message: string) => {
    errors[key] = [...new Set([...(errors[key] ?? []), message])];
  };
  const currency = form.currency;

  const name = form.name.trim();
  if (name === '') add('name', NAME_REQUIRED);
  // 문자 검사는 보내는 값(앞뒤 공백을 뺀 값)으로 한다 — 서버도 다듬은 뒤 본다
  else if (!isPlainText(name)) add('name', NAME_CHARACTER);
  else if (name.length > NAME_MAX) add('name', NAME_TOO_LONG);

  const summary = form.summary.trim();
  if (!isPlainText(summary) || summary.length > SUMMARY_MAX) add('summary', SUMMARY_INVALID);
  if (form.descriptionHtml.length > DESCRIPTION_MAX) add('descriptionHtml', DESCRIPTION_TOO_LONG);

  if (form.categoryId === '') add('categoryId', CATEGORY_REQUIRED);
  const categoryIds = [
    ...new Set(form.categoryIds.filter((id) => id !== '' && id !== form.categoryId)),
  ];
  // 서버 `categoryIds` @Size(max=20) 는 대표를 빼고 센다(대표는 서버가 따로 더한다)
  if (categoryIds.length > CATEGORY_IDS_MAX) add('categoryIds', CATEGORY_IDS_TOO_MANY);

  const salePrice = amountOf(form.salePrice, currency);
  if (salePrice.ok === null) add('salePrice', PRICE_REQUIRED);
  else if (!salePrice.ok) add('salePrice', salePrice.message);

  const listPrice = amountOf(form.listPrice, currency);
  if (listPrice.ok === false) add('listPrice', listPrice.message);
  else if (listPrice.ok && salePrice.ok && listPrice.amount <= salePrice.amount) {
    add('listPrice', LIST_PRICE_NOT_HIGHER);
  }

  const maxQuantity = positiveInt(form.maxQuantityPerOrder, MAX_QUANTITY_MAX);
  if (Number.isNaN(maxQuantity)) add('maxQuantityPerOrder', MAX_QUANTITY_RANGE);

  let expectedShipDate: string | undefined;
  if (form.preorder && form.expectedShipDate.trim() !== '') {
    const date = form.expectedShipDate.trim();
    if (!DATE.test(date)) add('expectedShipDate', SHIP_DATE_FORMAT);
    else if (date < today) add('expectedShipDate', SHIP_DATE_PAST);
    else expectedShipDate = date;
  }

  const groupNames = new Set<string>();
  form.optionGroups.forEach((group, index) => {
    const groupName = group.name.trim();
    if (groupName === '' || groupName.length > GROUP_NAME_MAX || !isPlainText(groupName)) {
      add(`optionGroups.${index}.name`, GROUP_NAME_INVALID);
    } else if (groupNames.has(groupName)) {
      add(`optionGroups.${index}.name`, GROUP_NAME_DUPLICATED);
    }
    groupNames.add(groupName);
    if (group.values.length === 0 || group.values.length > VALUES_MAX) {
      add(`optionGroups.${index}.values`, GROUP_VALUES_REQUIRED);
    }
  });
  if (form.optionGroups.length > GROUPS_MAX) add('optionGroups', GROUPS_TOO_MANY);

  const rows = skuRows(form);
  if (rows.length > SKUS_MAX) add('skus', COMBINATIONS_TOO_MANY);
  const skus: ProductCreateRequest['skus'] = [];
  rows.forEach(({ values, sku }, index) => {
    const at = (field: string) => `skus.${index}.${field}`;
    const additional = amountOf(sku.additionalPrice, currency);
    if (additional.ok === null) add(at('additionalPrice'), AMOUNT_REQUIRED);
    else if (!additional.ok) add(at('additionalPrice'), additional.message);

    const weight = positiveInt(sku.weightGrams, INT_MAX);
    if (Number.isNaN(weight)) add(at('weightGrams'), WEIGHT_INVALID);

    const hsRaw = sku.hsCode.trim();
    const hsCode = hsRaw === '' ? null : hsRaw.replace(HS_SEPARATORS, '');
    if (hsCode !== null && (!HS_DIGITS.test(hsCode) || hsRaw.length > 20)) {
      add(at('hsCode'), HS_CODE_INVALID);
    }

    let initialStock: { stockManaged: boolean; quantity: number } | undefined;
    if (sku.stockMode === 'unlimited') initialStock = { stockManaged: false, quantity: 0 };
    else if (sku.quantity.trim() !== '') {
      const raw = sku.quantity.trim();
      const quantity = Number(raw);
      if (!INTEGER.test(raw) || !Number.isSafeInteger(quantity)) {
        add(at('initialStock'), QUANTITY_INVALID);
      } else initialStock = { stockManaged: true, quantity };
    }

    if (additional.ok) {
      const item: ProductCreateRequest['skus'][number] = {
        optionValues: values,
        additionalPrice: { amount: additional.amount, currency },
        saleStatus: sku.saleStatus,
      };
      if (typeof weight === 'number' && !Number.isNaN(weight)) item.weightGrams = weight;
      if (hsCode !== null) item.hsCode = hsCode;
      if (initialStock) item.initialStock = initialStock;
      skus.push(item);
    }
  });

  // 가격 규칙(BE `PRODUCT_016`) — 판매중 SKU 가 있으면 그중 하나는 추가금 0
  const onSale = rows.filter((row) => row.sku.saleStatus === 'ON_SALE');
  const zeroOnSale = onSale.some((row) => parseMoneyInput(row.sku.additionalPrice, currency) === 0);
  const additionalParsed = rows.every((row) => {
    const amount = parseMoneyInput(row.sku.additionalPrice, currency);
    return amount !== null && !Number.isNaN(amount);
  });
  if (onSale.length > 0 && additionalParsed && !zeroOnSale) add('skus', ZERO_ADDITIONAL_REQUIRED);

  const entries = imageEntries(form);
  if (entries.length > IMAGES_MAX) add('images', IMAGES_TOO_MANY);
  const images: NonNullable<ProductCreateRequest['images']> = entries.map(
    ({ slot, uploadKey, imageType, index }) => {
      const altText = slot.altText.trim();
      if (!isPlainText(altText) || altText.length > ALT_TEXT_MAX) {
        add(`images.${index}.altText`, ALT_TEXT_INVALID);
      }
      const item: NonNullable<ProductCreateRequest['images']>[number] = {
        uploadKey,
        imageType,
      };
      if (altText !== '') item.altText = altText;
      return item;
    },
  );

  if (Object.keys(errors).length > 0 || !salePrice.ok) return { ok: false, errors };

  const request: ProductCreateRequest = {
    name,
    categoryId: form.categoryId,
    saleStatus: form.saleStatus,
    visible: form.visible,
    salePrice: { amount: salePrice.amount, currency },
    preorder: form.preorder,
    optionGroups: form.optionGroups.map((group) => ({
      name: group.name.trim(),
      values: group.values,
    })),
    skus,
  };
  if (summary !== '') request.summary = summary;
  if (form.descriptionHtml.trim() !== '') request.descriptionHtml = form.descriptionHtml;
  if (categoryIds.length > 0) request.categoryIds = categoryIds;
  if (listPrice.ok) request.listPrice = { amount: listPrice.amount, currency };
  if (typeof maxQuantity === 'number' && !Number.isNaN(maxQuantity)) {
    request.maxQuantityPerOrder = maxQuantity;
  }
  if (expectedShipDate !== undefined) request.expectedShipDate = expectedShipDate;
  if (images.length > 0) request.images = images;
  return { ok: true, request };
}

/**
 * 카테고리를 다시 읽은 뒤 트리에 없는 고른 값을 뺀다(그사이 삭제 — `PRODUCT_012`). 고른 것이 그대로면 같은 폼이다.
 */
export function pruneCategories(
  form: ProductCreateForm,
  rows: readonly CategoryRow[],
): ProductCreateForm {
  const known = new Set(rows.map((row) => row.node.categoryId));
  const categoryId = known.has(form.categoryId) ? form.categoryId : '';
  const categoryIds = form.categoryIds.filter((id) => known.has(id));
  if (categoryId === form.categoryId && categoryIds.length === form.categoryIds.length) return form;
  return { ...form, categoryId, categoryIds };
}

/** 등록 뒤 상세로 — 상세 설명을 정리해 저장했으면 상세가 한 번 알린다 */
export const SANITIZED_PARAM = 'sanitized';
export function createdProductHref(response: ProductCreateResponse): string {
  const base = `/products/${response.product.productId}`;
  return response.descriptionHtmlSanitized ? `${base}?${SANITIZED_PARAM}=1` : base;
}

/** HS 코드 칸 — 비우면 null(삭제), 점 · 하이픈 · 공백을 뺀 숫자 6~10자리면 그 값, 아니면 false(BE `SkuBulkUpdateRequest`) */
export function hsCodeInput(text: string): string | null | false {
  const raw = text.trim();
  if (raw === '') return null;
  const digits = raw.replace(HS_SEPARATORS, '');
  return HS_DIGITS.test(digits) && raw.length <= 20 ? digits : false;
}
