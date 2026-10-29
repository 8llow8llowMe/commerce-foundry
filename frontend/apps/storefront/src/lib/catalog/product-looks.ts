import type { LookImage, ProductListItem, ProductLook } from './types';

// 상품별 룩 응답(BE #62) 정규화. 룩은 상세 페이지에 덧붙이는 영역이라 모양이 어긋난 응답(배포 순서 · 계약 변경)이
// 페이지를 깨뜨리지 않게, 쓸 수 있는 칸만 골라 계약 모양으로 맞춘다. 버린 값은 그리지 않을 뿐 오류로 올리지 않는다.

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

const nonEmptyString = (value: unknown): value is string =>
  typeof value === 'string' && value.trim() !== '';

const optionalString = (value: unknown): string | null =>
  typeof value === 'string' && value.trim() !== '' ? value : null;

const optionalSize = (value: unknown): number | null =>
  typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : null;

function normalizeImage(value: unknown): LookImage | null {
  if (!isRecord(value) || !nonEmptyString(value.url)) return null;
  return {
    url: value.url,
    width: optionalSize(value.width),
    height: optionalSize(value.height),
    altText: optionalString(value.altText),
  };
}

/** 구성품은 아이디만 확실하면 남긴다 — 옵션 · SKU · 판매 상태는 상품 상세 API 로 다시 받는다 */
const isProductCard = (value: unknown): value is ProductListItem =>
  isRecord(value) && nonEmptyString(value.productId);

function normalizeLook(value: unknown): ProductLook | null {
  if (!isRecord(value) || !nonEmptyString(value.lookId) || !nonEmptyString(value.title)) {
    return null;
  }
  const products = Array.isArray(value.products) ? value.products.filter(isProductCard) : [];
  return {
    lookId: value.lookId,
    title: value.title,
    description: optionalString(value.description),
    image: normalizeImage(value.image),
    // 같은 상품이 두 번 오면(계약상 없다) 처음 것만 — 체크박스 key 가 겹치지 않게
    products: products.filter(
      (product, index) => products.findIndex((p) => p.productId === product.productId) === index,
    ),
  };
}

/** `{looks:[...]}` 가 아니면 빈 목록. 아이디 · 제목이 없는 룩과 같은 아이디가 다시 나온 룩은 뺀다 */
export function normalizeProductLooks(body: unknown): ProductLook[] {
  if (!isRecord(body) || !Array.isArray(body.looks)) return [];
  const seen = new Set<string>();
  const looks: ProductLook[] = [];
  for (const raw of body.looks) {
    const look = normalizeLook(raw);
    if (!look || seen.has(look.lookId)) continue;
    seen.add(look.lookId);
    looks.push(look);
  }
  return looks;
}
