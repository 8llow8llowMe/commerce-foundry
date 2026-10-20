import { CART_MAX_LINES, CART_MAX_QUANTITY, type CustomOption } from './types';

/**
 * 비로그인 장바구니(브라우저 localStorage). BE 장바구니는 회원 전용이라 비로그인 줄은 여기 두고,
 * 로그인 직후 `/api/v1/carts/me/merge` 로 한 번 보낸 뒤 비운다(`CartMerge`).
 * 가격 · 이름은 저장하지 않는다 — 보여 줄 때 상품 상세 API 로 현재 값을 읽는다(BE 장바구니와 같은 원칙).
 * 저장소를 못 쓰는 환경(사생활 보호 모드 · 차단)에서는 빈 장바구니로 동작하고 담기는 실패로 알린다.
 */
export type LocalCartLine = {
  skuId: string;
  productId: string;
  quantity: number;
  customOptions: CustomOption[];
  /** 담은 시각(ms) — 병합은 오래된 것부터 보낸다 */
  addedAt: number;
};

export const LOCAL_CART_KEY = 'cf_cart';

/** 같은 줄 판정 — BE 와 같이 키 · 값의 앞뒤 공백을 자르고 NFC 로 맞춘 뒤 키 순으로 비교한다 */
export function normalizeOptions(options: CustomOption[]): CustomOption[] {
  return options
    .map((option) => ({
      fieldKey: option.fieldKey.trim().normalize('NFC'),
      value: option.value.trim().normalize('NFC'),
    }))
    .filter((option) => option.value !== '')
    .sort((a, b) => (a.fieldKey < b.fieldKey ? -1 : a.fieldKey > b.fieldKey ? 1 : 0));
}

export function lineKey(skuId: string, options: CustomOption[]): string {
  return JSON.stringify([skuId, normalizeOptions(options)]);
}

export type LocalAddResult = 'added' | 'lineLimit' | 'storageUnavailable';

/**
 * 줄을 더한다. 같은 줄이면 수량을 더하고 상한(maxQuantity, 없으면 99)에서 자른다 — 서버 병합과 같은 규칙.
 * 새 줄이 50줄을 넘으면 담지 않는다.
 */
export function addLines(
  current: LocalCartLine[],
  additions: (Omit<LocalCartLine, 'addedAt'> & { maxQuantity?: number | null })[],
  now: number,
): LocalCartLine[] | null {
  const next = current.map((line) => ({ ...line }));
  for (const { maxQuantity, ...addition } of additions) {
    const cap = Math.min(maxQuantity ?? CART_MAX_QUANTITY, CART_MAX_QUANTITY);
    const key = lineKey(addition.skuId, addition.customOptions);
    const existing = next.find((line) => lineKey(line.skuId, line.customOptions) === key);
    if (existing) {
      existing.quantity = Math.min(existing.quantity + addition.quantity, cap);
      continue;
    }
    if (next.length >= CART_MAX_LINES) return null;
    next.push({
      ...addition,
      customOptions: normalizeOptions(addition.customOptions),
      quantity: Math.min(addition.quantity, cap),
      addedAt: now,
    });
  }
  return next;
}

/** 저장 값을 믿지 않는다 — 모양이 틀린 줄은 버린다 */
export function parseLines(raw: string | null): LocalCartLine[] {
  if (!raw) return [];
  try {
    const value: unknown = JSON.parse(raw);
    if (!Array.isArray(value)) return [];
    return value.filter(isLine).slice(0, CART_MAX_LINES);
  } catch {
    return [];
  }
}

// BE `CartItemAddRequest` · `CustomOptionRequest` 와 같은 제약 — 하나라도 어긋나면 병합 요청 전체가 400 이라 미리 버린다
const CUSTOM_OPTIONS_MAX = 20;
const FIELD_KEY = /^[a-z][a-zA-Z0-9_]{0,49}$/;
const VALUE_MAX = 500;

function isOption(value: unknown): value is CustomOption {
  if (typeof value !== 'object' || value === null) return false;
  const { fieldKey, value: text } = value as Record<string, unknown>;
  return (
    typeof fieldKey === 'string' &&
    FIELD_KEY.test(fieldKey) &&
    typeof text === 'string' &&
    text.trim() !== '' &&
    text.length <= VALUE_MAX
  );
}

function isLine(value: unknown): value is LocalCartLine {
  if (typeof value !== 'object' || value === null) return false;
  const line = value as Record<string, unknown>;
  return (
    typeof line.skuId === 'string' &&
    /^\d+$/.test(line.skuId) &&
    typeof line.productId === 'string' &&
    Number.isInteger(line.quantity) &&
    (line.quantity as number) >= 1 &&
    (line.quantity as number) <= CART_MAX_QUANTITY &&
    Array.isArray(line.customOptions) &&
    line.customOptions.length <= CUSTOM_OPTIONS_MAX &&
    line.customOptions.every(isOption) &&
    typeof line.addedAt === 'number'
  );
}

// --- 브라우저 저장소 + 구독 (useSyncExternalStore) ---

const listeners = new Set<() => void>();
let cachedRaw: string | null | undefined;
let cachedLines: LocalCartLine[] = [];
const EMPTY: LocalCartLine[] = [];

function readRaw(): string | null {
  try {
    return window.localStorage.getItem(LOCAL_CART_KEY);
  } catch {
    return null;
  }
}

/** 같은 값이면 같은 배열을 돌려준다(useSyncExternalStore 가 매번 다시 그리지 않게) */
export function readLocalCart(): LocalCartLine[] {
  if (typeof window === 'undefined') return EMPTY;
  const raw = readRaw();
  if (raw !== cachedRaw) {
    cachedRaw = raw;
    cachedLines = parseLines(raw);
  }
  return cachedLines;
}

export function serverLocalCart(): LocalCartLine[] {
  return EMPTY;
}

function write(lines: LocalCartLine[]): boolean {
  try {
    if (lines.length === 0) window.localStorage.removeItem(LOCAL_CART_KEY);
    else window.localStorage.setItem(LOCAL_CART_KEY, JSON.stringify(lines));
  } catch {
    return false;
  }
  for (const listener of listeners) listener();
  return true;
}

export function subscribeLocalCart(listener: () => void): () => void {
  listeners.add(listener);
  // 다른 탭에서 바뀐 것도 반영한다
  const onStorage = (event: StorageEvent) => {
    if (event.key === LOCAL_CART_KEY || event.key === null) listener();
  };
  window.addEventListener('storage', onStorage);
  return () => {
    listeners.delete(listener);
    window.removeEventListener('storage', onStorage);
  };
}

export function addToLocalCart(
  additions: Parameters<typeof addLines>[1],
  now = Date.now(),
): LocalAddResult {
  const next = addLines(readLocalCart(), additions, now);
  if (!next) return 'lineLimit';
  return write(next) ? 'added' : 'storageUnavailable';
}

export function setLocalQuantity(skuId: string, options: CustomOption[], quantity: number): void {
  const key = lineKey(skuId, options);
  write(
    readLocalCart().map((line) =>
      lineKey(line.skuId, line.customOptions) === key ? { ...line, quantity } : line,
    ),
  );
}

export function removeLocalLine(skuId: string, options: CustomOption[]): void {
  const key = lineKey(skuId, options);
  write(readLocalCart().filter((line) => lineKey(line.skuId, line.customOptions) !== key));
}

/**
 * 병합으로 보낸 만큼만 지운다 — 병합하는 동안 새로 담은 줄이나 늘린 수량은 남겨 다음 병합에 보낸다.
 * 보낸 줄은 담은 시각까지 같아야 같은 줄로 본다.
 */
export function withoutMerged(current: LocalCartLine[], merged: LocalCartLine[]): LocalCartLine[] {
  const sent = new Map(
    merged.map((line) => [`${lineKey(line.skuId, line.customOptions)}@${line.addedAt}`, line]),
  );
  const next: LocalCartLine[] = [];
  for (const line of current) {
    const before = sent.get(`${lineKey(line.skuId, line.customOptions)}@${line.addedAt}`);
    if (!before) next.push(line);
    else if (line.quantity > before.quantity)
      next.push({ ...line, quantity: line.quantity - before.quantity });
  }
  return next;
}

export function removeMergedLines(merged: LocalCartLine[]): void {
  write(withoutMerged(readLocalCart(), merged));
}
