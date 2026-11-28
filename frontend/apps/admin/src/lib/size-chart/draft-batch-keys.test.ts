import { describe, expect, it } from 'vitest';
import {
  findKey,
  fingerprintDigest,
  forgetKey,
  KEY_STORAGE_NAME,
  KEY_TTL_MS,
  loadKeyBook,
  rememberKey,
  saveKeyBook,
  type KeyStorage,
} from './draft-batch-keys';

const NOW = Date.parse('2026-11-28T00:00:00Z');

function memoryStorage(initial: Record<string, string> = {}): KeyStorage & {
  data: Record<string, string>;
} {
  const data = { ...initial };
  return {
    data,
    getItem: (name) => data[name] ?? null,
    setItem: (name, value) => {
      data[name] = value;
    },
  };
}

const throwing: KeyStorage = {
  getItem: () => {
    throw new Error('차단');
  },
  setItem: () => {
    throw new Error('용량 초과');
  },
};

describe('키 장부', () => {
  it('같은 상품 · 같은 지문이면 저장된 키를 다시 쓴다. 지문 요약에는 원문이 없다', () => {
    const digest = fingerprintDigest('5\nS 가슴 50');
    expect(digest).toBe(fingerprintDigest('5\nS 가슴 50'));
    expect(digest).not.toBe(fingerprintDigest('5\nS 가슴 51'));
    expect(digest).not.toContain('가슴');

    const book = rememberKey({}, '1', { digest, key: 'k1', createdAt: NOW });
    expect(findKey(book, '1', digest, NOW)?.key).toBe('k1');
    expect(findKey(book, '2', digest, NOW)).toBeNull();
    expect(findKey(forgetKey(book, '1', digest), '1', digest, NOW)).toBeNull();
  });

  it('저장했다 다시 읽으면 같은 키다(같은 탭 새로 고침)', () => {
    const storage = memoryStorage();
    const book = rememberKey({}, '1', { digest: 'a', key: 'k1', createdAt: NOW });
    saveKeyBook(storage, book);
    expect(JSON.parse(storage.data[KEY_STORAGE_NAME] ?? '{}')).toEqual({
      '1': [{ digest: 'a', key: 'k1', createdAt: NOW }],
    });
    expect(findKey(loadKeyBook(storage, NOW + 1000), '1', 'a', NOW + 1000)?.key).toBe('k1');
  });

  it('7일이 지난 키는 읽을 때 버린다', () => {
    const storage = memoryStorage();
    saveKeyBook(storage, {
      '1': [
        { digest: 'old', key: 'k-old', createdAt: NOW - KEY_TTL_MS },
        { digest: 'new', key: 'k-new', createdAt: NOW - KEY_TTL_MS + 1 },
      ],
      '2': [{ digest: 'old', key: 'k2', createdAt: NOW - KEY_TTL_MS - 1 }],
    });
    expect(loadKeyBook(storage, NOW)).toEqual({
      '1': [{ digest: 'new', key: 'k-new', createdAt: NOW - KEY_TTL_MS + 1 }],
    });
    expect(
      findKey({ '1': [{ digest: 'a', key: 'k', createdAt: NOW }] }, '1', 'a', NOW + KEY_TTL_MS),
    ).toBeNull();
  });

  it('저장소가 없거나 · 예외를 던지거나 · 모양이 틀리면 빈 장부로 메모리 동작한다', () => {
    expect(loadKeyBook(null, NOW)).toEqual({});
    expect(loadKeyBook(throwing, NOW)).toEqual({});
    expect(() => saveKeyBook(throwing, { '1': [] })).not.toThrow();
    expect(() => saveKeyBook(null, {})).not.toThrow();
    expect(loadKeyBook(memoryStorage({ [KEY_STORAGE_NAME]: '{깨짐' }), NOW)).toEqual({});
    expect(loadKeyBook(memoryStorage({ [KEY_STORAGE_NAME]: '[1,2]' }), NOW)).toEqual({});
    expect(
      loadKeyBook(
        memoryStorage({
          [KEY_STORAGE_NAME]: JSON.stringify({
            '1': [{ digest: 'a', key: 'k', createdAt: NOW }, { digest: 1 }],
          }),
        }),
        NOW,
      ),
    ).toEqual({ '1': [{ digest: 'a', key: 'k', createdAt: NOW }] });
  });
});
