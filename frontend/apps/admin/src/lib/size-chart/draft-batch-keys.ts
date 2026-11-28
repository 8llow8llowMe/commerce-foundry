/**
 * 일괄 초안 요청의 항목별 멱등 키 보관(#218 · BE #737).
 * - 상품마다 "입력 지문 → 키" 를 여럿 둔다. 입력을 바꿨다가 원래대로 돌리면 원래 키를 다시 쓴다.
 * - 이 탭의 sessionStorage 에 둔다(결제 승인 키와 같은 선례, `docs/architecture.md` 결제). 새로 고침 · 다른 화면에 다녀와도
 *   같은 탭에서 같은 상품 · 같은 입력이면 같은 키라 서버가 기존 초안을 돌려준다. 다른 탭 · 창을 닫으면 사라진다.
 * - 저장하는 값은 최소다 — 상품 아이디 · 지문의 요약값(치수 텍스트 원문은 담지 않는다) · 키 · 만든 시각.
 * - BE 멱등 보관은 7일이다. 7일이 지난 키는 읽을 때 버린다(다시 보내면 새 과금이 생길 수 있어 저절로 다시 쓰지 않는다).
 * - 저장소를 못 쓰면(차단 · 용량 · 형식 오류) 메모리로만 동작한다. 모든 접근은 예외를 삼킨다.
 */
export const KEY_TTL_MS = 7 * 24 * 60 * 60 * 1000;
export const KEY_STORAGE_NAME = 'cf.sizechart.batch.keys';
/** 상품마다 남길 지문 수 — 오래된 것부터 버린다 */
const MAX_ENTRIES_PER_PRODUCT = 20;

export type KeyEntry = { digest: string; key: string; createdAt: number };
export type KeyBook = Readonly<Record<string, readonly KeyEntry[]>>;

export type KeyStorage = Pick<Storage, 'getItem' | 'setItem'>;

/**
 * 입력 지문의 요약값(53비트 cyrb53, 16진수). 보안 해시가 아니다 — 치수 원문을 저장소에 두지 않으려는 것이다.
 * 드물게 겹쳐 다른 입력에 같은 키가 가면 서버가 002(같은 키 다른 입력)로 거절하고, 그 항목만 새 키로 다시 보낸다.
 */
export function fingerprintDigest(fingerprint: string): string {
  let h1 = 0xdeadbeef;
  let h2 = 0x41c6ce57;
  for (let i = 0; i < fingerprint.length; i += 1) {
    const ch = fingerprint.charCodeAt(i);
    h1 = Math.imul(h1 ^ ch, 2654435761);
    h2 = Math.imul(h2 ^ ch, 1597334677);
  }
  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
  h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
  return (4294967296 * (2097151 & h2) + (h1 >>> 0)).toString(16);
}

const alive = (entry: KeyEntry, now: number) =>
  entry.createdAt <= now && now - entry.createdAt < KEY_TTL_MS;

/** 만료된 키를 버린다 */
export function liveKeys(book: KeyBook, now: number): KeyBook {
  const next: Record<string, KeyEntry[]> = {};
  for (const [productId, entries] of Object.entries(book)) {
    const kept = entries.filter((entry) => alive(entry, now));
    if (kept.length > 0) next[productId] = kept;
  }
  return next;
}

/** 같은 상품 · 같은 지문의 살아 있는 키. 없으면 null */
export function findKey(
  book: KeyBook,
  productId: string,
  digest: string,
  now: number,
): KeyEntry | null {
  return book[productId]?.find((entry) => entry.digest === digest && alive(entry, now)) ?? null;
}

/** 키를 남긴다. 같은 지문의 이전 키는 바꾼다 */
export function rememberKey(book: KeyBook, productId: string, entry: KeyEntry): KeyBook {
  const others = (book[productId] ?? []).filter((item) => item.digest !== entry.digest);
  return { ...book, [productId]: [...others, entry].slice(-MAX_ENTRIES_PER_PRODUCT) };
}

/** 그 지문의 키를 버린다(같은 키에 다른 입력이 있던 002) */
export function forgetKey(book: KeyBook, productId: string, digest: string): KeyBook {
  const entries = book[productId];
  if (!entries) return book;
  return { ...book, [productId]: entries.filter((entry) => entry.digest !== digest) };
}

const isEntry = (value: unknown): value is KeyEntry =>
  typeof value === 'object' &&
  value !== null &&
  typeof (value as KeyEntry).digest === 'string' &&
  typeof (value as KeyEntry).key === 'string' &&
  typeof (value as KeyEntry).createdAt === 'number';

/** 저장소에서 읽는다. 없거나 · 모양이 틀리거나 · 읽지 못하면 빈 장부. 만료된 키는 버린다 */
export function loadKeyBook(storage: KeyStorage | null, now: number): KeyBook {
  if (!storage) return {};
  try {
    const raw = storage.getItem(KEY_STORAGE_NAME);
    if (!raw) return {};
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return {};
    const book: Record<string, KeyEntry[]> = {};
    for (const [productId, entries] of Object.entries(parsed)) {
      if (Array.isArray(entries)) book[productId] = entries.filter(isEntry);
    }
    return liveKeys(book, now);
  } catch {
    return {};
  }
}

/** 저장소에 쓴다. 실패하면 조용히 넘어간다(메모리 장부는 그대로 쓴다) */
export function saveKeyBook(storage: KeyStorage | null, book: KeyBook): void {
  if (!storage) return;
  try {
    storage.setItem(KEY_STORAGE_NAME, JSON.stringify(book));
  } catch {
    // 용량 초과 · 차단 — 메모리로 동작한다
  }
}

/** 이 탭의 sessionStorage. 서버 렌더 · 차단된 브라우저면 null */
export function sessionKeyStorage(): KeyStorage | null {
  try {
    return typeof window === 'undefined' ? null : window.sessionStorage;
  } catch {
    return null;
  }
}
