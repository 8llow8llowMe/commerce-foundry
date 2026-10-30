import { ApiError, type Money } from '@cf/api-client';
import { accessDeniedOf, type AccessDenied } from '@/lib/api-errors';
import { flattenTree } from '@/lib/category/tree';
import type { CategoryNode } from '@/lib/category/types';
import { ADMIN_PRODUCTS_PATH, isLongId } from './query';

/**
 * 어드민 상품 상세 `GET /api/v1/admin/products/{productId}`(BE #394, `backend/docs/api-design-guide.md` 어드민 상품 상세).
 * 숨김 상품도 준다. 없거나 삭제된 상품은 404 `PRODUCT_001`, 경로 아이디가 양수가 아니면 400 `PRODUCT_105`, 숫자가 아니면 `PRODUCT_198`.
 * 저장된 값 그대로다 — 은퇴한 옵션 · 판매 종료 SKU · 고객 기능 플래그가 꺼진 프리오더 · 영상 · 맞춤 제작 설정도 있다.
 */
export const adminProductPath = (productId: string) => `${ADMIN_PRODUCTS_PATH}/${productId}`;

/** 서버 `CodeNameDescriptionMetadata`. 화면은 `name`(설명은 `title`)만 쓰고 `code` 는 분기에만 쓴다 */
export type CodeName = { code: string; name: string; description: string };

export type AdminOptionValue = {
  optionValueId: string;
  name: string;
  displayOrder: number;
  /** 은퇴 시각(UTC ISO). 사용 중이면 null */
  retiredAt: string | null;
};

export type AdminOptionGroup = {
  optionGroupId: string;
  name: string;
  displayOrder: number;
  retiredAt: string | null;
  values: AdminOptionValue[];
};

export type AdminSku = {
  skuId: string;
  optionValueIds: string[];
  additionalPrice: Money;
  saleStatus: CodeName;
  weightGrams: number | null;
  hsCode: string | null;
};

export type AdminProductMedia = {
  imageId: string;
  /** THUMBNAIL · GALLERY · VIDEO */
  imageType: CodeName;
  url: string;
  width: number | null;
  height: number | null;
  altText: string | null;
  displayOrder: number;
  /** 영상의 재생 전 정지 이미지. 정지 이미지면 null */
  posterUrl: string | null;
};

export type AdminCustomOrderField = {
  fieldKey: string;
  label: string;
  /** NUMBER · TEXT */
  inputType: CodeName;
  unit: string | null;
  required: boolean;
  /** BigDecimal — JSON 숫자로 온다 */
  minValue: number | null;
  /** 숫자 입력이면 최대값, 글자 입력이면 최대 글자 수 */
  maxValue: number | null;
  stepValue: number | null;
  helpText: string | null;
  displayOrder: number;
};

/** `ProductDetailAdminResponse` */
export type AdminProductDetail = {
  productId: string;
  categoryId: string;
  categoryIds: string[];
  name: string;
  summary: string | null;
  /** 서버가 정제하지 않은 원문 — 서버 컴포넌트에서 `sanitizeProductHtml` 을 거쳐서만 넣는다 */
  descriptionHtml: string | null;
  saleStatus: CodeName;
  visible: boolean;
  salePrice: Money;
  listPrice: Money | null;
  maxQuantityPerOrder: number | null;
  customOrder: boolean;
  customOrderNotice: string | null;
  customOrderLeadTimeDays: number | null;
  preorder: boolean;
  /** `yyyy-MM-dd`(날짜만). 미입력이면 null */
  expectedShipDate: string | null;
  /** 수정 충돌 확인용(#9 수정 화면에서 쓴다). 화면에 보이지 않는다 */
  version: number | null;
  createdByMemberId: string | null;
  optionGroups: AdminOptionGroup[];
  skus: AdminSku[];
  images: AdminProductMedia[];
  customOrderFields: AdminCustomOrderField[];
};

export type ProductDetailLoad =
  | { kind: 'ok'; product: AdminProductDetail }
  | { kind: 'notFound' }
  | { kind: 'denied'; denied: AccessDenied }
  | { kind: 'failed' };

/** 경로 아이디가 틀렸을 때 서버 코드 — 화면에는 "없는 상품" 과 같다 */
const INVALID_PATH_CODES = new Set(['PRODUCT_105', 'PRODUCT_198']);

/**
 * 상세를 읽어 화면 상태로 바꾼다. `load` 는 서버 컴포넌트의 `serverFetch`(테스트는 대역)다.
 * 형식이 틀린 아이디는 부르지 않고 없는 상품으로 본다 — 서버도 400 이고, 운영자에게는 같은 일이다.
 */
export async function loadProductDetail(
  productId: string,
  load: <T>(path: string) => Promise<T>,
): Promise<ProductDetailLoad> {
  if (!isLongId(productId)) return { kind: 'notFound' };
  try {
    return { kind: 'ok', product: await load<AdminProductDetail>(adminProductPath(productId)) };
  } catch (cause) {
    const denied = accessDeniedOf(cause);
    if (denied) return { kind: 'denied', denied };
    if (cause instanceof ApiError) {
      if (cause.kind === 'notFound') return { kind: 'notFound' };
      if (cause.code !== null && INVALID_PATH_CODES.has(cause.code)) return { kind: 'notFound' };
    }
    return { kind: 'failed' };
  }
}

/** 은퇴 여부와 상관없이 옵션 값 아이디 → (그룹, 값) */
type OptionIndex = Map<string, { group: AdminOptionGroup; value: AdminOptionValue }>;

function indexOptions(groups: AdminOptionGroup[]): OptionIndex {
  const index: OptionIndex = new Map();
  for (const group of groups) {
    for (const value of group.values) index.set(value.optionValueId, { group, value });
  }
  return index;
}

export type SkuOptionLabel = {
  /** "색상: 빨강 · 사이즈: M". 옵션이 없는 상품의 SKU 는 "기본" */
  text: string;
  /** 조합에 은퇴한 그룹 · 값이 하나라도 있다 */
  retired: boolean;
};

/** 기본 상품(옵션 없음)의 SKU 이름 */
export const DEFAULT_SKU_LABEL = '기본';
/** 조합의 값 아이디가 옵션 목록에 없을 때 — 아이디 원문을 보이지 않는다(#305) */
export const UNKNOWN_OPTION_LABEL = '알 수 없는 옵션';

/**
 * SKU 의 옵션 값 조합을 사람이 읽는 이름으로. 그룹 진열 순서대로 늘어놓고, 목록에 없는 값은 `UNKNOWN_OPTION_LABEL` 로 둔다.
 */
export function skuOptionLabels(product: AdminProductDetail): Map<string, SkuOptionLabel> {
  const index = indexOptions(product.optionGroups);
  const groupOrder = new Map(product.optionGroups.map((group, order) => [group, order]));
  const labels = new Map<string, SkuOptionLabel>();
  for (const sku of product.skus) {
    if (sku.optionValueIds.length === 0) {
      labels.set(sku.skuId, { text: DEFAULT_SKU_LABEL, retired: false });
      continue;
    }
    const found = sku.optionValueIds.map((id) => index.get(id) ?? null);
    const known = found
      .filter((entry) => entry !== null)
      .sort((a, b) => (groupOrder.get(a.group) ?? 0) - (groupOrder.get(b.group) ?? 0));
    const parts = known.map(({ group, value }) => `${group.name}: ${value.name}`);
    if (known.length < found.length) parts.push(UNKNOWN_OPTION_LABEL);
    labels.set(sku.skuId, {
      text: parts.join(' · '),
      retired: known.some(
        ({ group, value }) => group.retiredAt !== null || value.retiredAt !== null,
      ),
    });
  }
  return labels;
}

export type CategoryLabel = { text: string; primary: boolean };

/** 카테고리 트리에서 찾지 못한 아이디(그사이 삭제 등) — 아이디 원문을 보이지 않는다(#305) */
export const UNKNOWN_CATEGORY_LABEL = '찾을 수 없는 카테고리';

/**
 * 연결 카테고리를 "상의 > 저고리" 경로 이름으로. 대표 카테고리를 맨 앞에 두고 나머지는 서버 순서다.
 * 트리를 못 읽었으면 null — 화면이 따로 안내한다.
 */
export function categoryLabels(
  product: AdminProductDetail,
  tree: CategoryNode[] | null,
): CategoryLabel[] | null {
  if (!tree) return null;
  const paths = new Map(
    flattenTree(tree).map((row) => [row.node.categoryId, row.path.join(' > ')]),
  );
  const ids = [
    product.categoryId,
    ...product.categoryIds.filter((id) => id !== product.categoryId),
  ];
  return ids.map((id) => ({
    text: paths.get(id) ?? UNKNOWN_CATEGORY_LABEL,
    primary: id === product.categoryId,
  }));
}

/** 미디어 유형 코드 */
export const VIDEO_MEDIA = 'VIDEO';
/** SKU · 상품 판매 상태 코드 — 판매 종료는 흐리게 그린다 */
export const STOP_SELLING = 'STOP_SELLING';

/** `2026-11-03` → `2026. 11. 3.` 날짜만이라 시간대로 바꾸지 않는다. 형식이 다르면 원문 그대로 */
export function formatLocalDate(value: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return value;
  return `${match[1]}. ${Number(match[2])}. ${Number(match[3])}.`;
}

const instant = new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeZone: 'Asia/Seoul' });

/** 은퇴 시각(UTC ISO) → 한국 날짜 */
export function formatRetiredAt(value: string): string {
  return instant.format(new Date(value));
}

/** 맞춤 제작 입력 범위 안내. 글자 입력은 최대 글자 수만 뜻이 있다 */
export function customFieldRange(field: AdminCustomOrderField): string | null {
  const unit = field.unit ? ` ${field.unit}` : '';
  if (field.inputType.code === 'TEXT') {
    return field.maxValue !== null ? `최대 ${field.maxValue}자` : null;
  }
  const parts: string[] = [];
  if (field.minValue !== null && field.maxValue !== null) {
    parts.push(`${field.minValue} ~ ${field.maxValue}${unit}`);
  } else if (field.minValue !== null) {
    parts.push(`${field.minValue}${unit} 이상`);
  } else if (field.maxValue !== null) {
    parts.push(`${field.maxValue}${unit} 이하`);
  }
  if (field.stepValue !== null) parts.push(`${field.stepValue}${unit} 단위`);
  return parts.length > 0 ? parts.join(', ') : null;
}
