import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { mergeBatch, mergeLocalCart, mergeRequestItems } from './merge';

const line = (skuId: string, addedAt: number) => ({
  skuId,
  productId: '1',
  quantity: 1,
  customOptions: [],
  addedAt,
});

describe('mergeBatch', () => {
  it('오래 담은 것부터 50줄만 보낸다', () => {
    const lines = Array.from({ length: 55 }, (_, i) => line(String(i + 1), 100 - i));
    const batch = mergeBatch(lines);
    expect(batch).toHaveLength(50);
    expect(batch[0]?.addedAt).toBe(46);
  });
});

describe('mergeRequestItems', () => {
  it('맞춤 제작 입력이 없으면 customOptions 를 싣지 않는다', () => {
    expect(
      mergeRequestItems([
        line('1', 1),
        { ...line('2', 2), customOptions: [{ fieldKey: 'waist', value: '70' }] },
      ]),
    ).toEqual([
      { skuId: '1', quantity: 1 },
      { skuId: '2', quantity: 1, customOptions: [{ fieldKey: 'waist', value: '70' }] },
    ]);
  });
});

describe('mergeLocalCart', () => {
  const lines = [line('1', 1), line('2', 2)];
  const setup = (post: () => Promise<unknown>) => {
    const removed: unknown[] = [];
    const run = () =>
      mergeLocalCart({
        read: () => lines,
        remove: (batch) => removed.push(batch),
        post: post as never,
      });
    return { run, removed };
  };
  const fail = (kind: ConstructorParameters<typeof ApiError>[0], status: number | null) => () =>
    Promise.reject(new ApiError(kind, status, null, null));

  it('로컬이 비어 있으면 보내지 않는다', async () => {
    const result = await mergeLocalCart({
      read: () => [],
      remove: () => {},
      post: () => Promise.reject(new Error('called')),
    });
    expect(result).toEqual({ kind: 'empty' });
  });

  it('성공하면 보낸 줄을 지우고 건너뛴 수를 알린다', async () => {
    const { run, removed } = setup(() =>
      Promise.resolve({
        cart: {},
        skippedItems: [{ index: 1, skuId: '2', reason: 'NOT_PURCHASABLE' }],
      }),
    );
    expect(await run()).toEqual({ kind: 'merged', skipped: 1 });
    expect(removed).toEqual([lines]);
  });

  it('502 · 504(결과 모름)는 다시 보내면 두 번 더해질 수 있어 보낸 줄을 지우고 다시 보내지 않는다', async () => {
    for (const status of [502, 504]) {
      const { run, removed } = setup(fail('unknownOutcome', status));
      expect(await run()).toEqual({ kind: 'unknown' });
      expect(removed).toHaveLength(1);
    }
  });

  it('400(형식 오류)은 다시 보내도 같아서 보낸 줄을 버리고 수를 알린다', async () => {
    const { run, removed } = setup(fail('validation', 400));
    expect(await run()).toEqual({ kind: 'invalid', dropped: 2 });
    expect(removed).toHaveLength(1);
  });

  it('401 은 로컬을 두고 세션이 끝났다고 알린다', async () => {
    const { run, removed } = setup(fail('unauthorized', 401));
    expect(await run()).toEqual({ kind: 'signedOut' });
    expect(removed).toEqual([]);
  });

  it('서버가 처리하지 않은 실패(429 · 503 · 500 · 연결)는 로컬을 두고 다음에 다시 보낸다', async () => {
    for (const post of [
      fail('rateLimited', 429),
      fail('unavailable', 503),
      fail('server', 500),
      fail('network', null),
      () => Promise.reject(new Error('aborted')),
    ]) {
      const { run, removed } = setup(post);
      expect(await run()).toEqual({ kind: 'retryLater' });
      expect(removed).toEqual([]);
    }
  });
});
