import { ApiError } from '@cf/api-client';
import { describe, expect, it, vi } from 'vitest';
import { hasRejectedCharacters } from './custom-input';
import { parseLines } from './local-cart';
import { mergeBatch, mergeLocalCart, mergeRequestItems, rejectedItemIndexes } from './merge';
import type { CartItemRequest } from './types';

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

describe('mergeRequestItems — 입력 문자 규칙(#263)', () => {
  it('맞춤 입력의 제어 문자를 지워 싣고, 지워서 비는 칸은 뺀다', () => {
    expect(
      mergeRequestItems([
        {
          ...line('2', 2),
          customOptions: [
            { fieldKey: 'memo', value: '소매\t길게' },
            { fieldKey: 'note', value: '\u0000' },
          ],
        },
      ]),
    ).toEqual([
      { skuId: '2', quantity: 1, customOptions: [{ fieldKey: 'memo', value: '소매 길게' }] },
    ]);
  });
});

describe('rejectedItemIndexes', () => {
  const invalid = (...fields: string[]) =>
    new ApiError(
      'validation',
      400,
      'CART_115',
      null,
      fields.map((field) => ({ code: 'CART_115', field, message: 'x' })),
    );

  it('fieldErrors 의 items[i] 경로에서 항목 번호를 모은다', () => {
    expect(
      rejectedItemIndexes(
        invalid('items[1].customOptions[0].value', 'items[3].quantity', 'items[1].skuId'),
        5,
      ),
    ).toEqual(new Set([1, 3]));
  });

  it('요청 전체 오류 · 보낸 수를 넘는 번호 · 오류 없음이면 null 이다', () => {
    expect(rejectedItemIndexes(invalid('items'), 5)).toBeNull();
    expect(rejectedItemIndexes(invalid('items[0].quantity', 'request'), 5)).toBeNull();
    expect(rejectedItemIndexes(invalid('items[5].quantity'), 5)).toBeNull();
    expect(rejectedItemIndexes(invalid(), 5)).toBeNull();
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

  it('400 이 어느 항목인지 가리키지 않으면(fieldErrors 없음) 다시 보내도 같아서 보낸 줄을 버리고 수를 알린다', async () => {
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

  describe('400 이 항목을 가리키면 그 줄만 빼고 한 번 다시 보낸다', () => {
    const three = [line('1', 1), line('2', 2), line('3', 3)];
    const rejectItem = (index: number) =>
      new ApiError('validation', 400, 'CART_115', null, [
        { code: 'CART_115', field: `items[${index}].customOptions[0].value`, message: 'x' },
      ]);
    const run = (post: (items: CartItemRequest[]) => Promise<unknown>) => {
      const removed: unknown[] = [];
      const result = mergeLocalCart({
        read: () => three,
        remove: (batch) => removed.push(batch),
        post: post as never,
      });
      return { result, removed };
    };

    it('다시 보낸 것이 성공하면 뺀 줄을 건너뛴 수에 더한다', async () => {
      const post = vi
        .fn()
        .mockRejectedValueOnce(rejectItem(1))
        .mockResolvedValueOnce({ cart: {}, skippedItems: [] });
      const { result, removed } = run(post);
      expect(await result).toEqual({ kind: 'merged', skipped: 1 });
      expect(post).toHaveBeenCalledTimes(2);
      expect(post.mock.calls[1]![0]).toEqual([
        { skuId: '1', quantity: 1 },
        { skuId: '3', quantity: 1 },
      ]);
      expect(removed).toEqual([three]);
    });

    it('다시 보낸 것도 400 이면 더 보내지 않고 남은 줄을 버린다', async () => {
      const post = vi
        .fn()
        .mockRejectedValueOnce(rejectItem(1))
        .mockRejectedValueOnce(rejectItem(0));
      const { result, removed } = run(post);
      expect(await result).toEqual({ kind: 'invalid', dropped: 3 });
      expect(post).toHaveBeenCalledTimes(2);
      expect(removed).toEqual([three]);
    });

    it('다시 보낸 것이 서버가 처리하지 않은 실패(429 · 503 · 연결)면 뺀 줄까지 모두 남겨 다음에 다시 판정한다', async () => {
      for (const cause of [
        new ApiError('rateLimited', 429, 'GATEWAY_006', null),
        new ApiError('unavailable', 503, null, null),
        new ApiError('network', null, null, null),
        new Error('aborted'),
      ]) {
        const post = vi.fn().mockRejectedValueOnce(rejectItem(2)).mockRejectedValueOnce(cause);
        const { result, removed } = run(post);
        expect(await result).toEqual({ kind: 'retryLater' });
        expect(removed).toEqual([]);
      }
    });

    it('다시 보낸 것이 502 · 504(결과 모름)면 다시 보낸 줄만 지우고 뺀 줄은 남긴다', async () => {
      for (const status of [502, 504]) {
        const post = vi
          .fn()
          .mockRejectedValueOnce(rejectItem(1))
          .mockRejectedValueOnce(new ApiError('unknownOutcome', status, null, null));
        const { result, removed } = run(post);
        expect(await result).toEqual({ kind: 'unknown' });
        expect(removed).toEqual([[line('1', 1), line('3', 3)]]);
      }
    });

    it('다시 보낸 것이 401 이면 아무것도 지우지 않고 세션이 끝났다고 알린다', async () => {
      const post = vi
        .fn()
        .mockRejectedValueOnce(rejectItem(1))
        .mockRejectedValueOnce(new ApiError('unauthorized', 401, null, null));
      const { result, removed } = run(post);
      expect(await result).toEqual({ kind: 'signedOut' });
      expect(removed).toEqual([]);
    });

    it('fieldErrors 가 항목을 가리켜도 400 이 아니면 다시 보내지 않는다', async () => {
      const post = vi
        .fn()
        .mockRejectedValueOnce(
          new ApiError('validation', 409, 'CART_115', null, [
            { code: 'CART_115', field: 'items[1].customOptions[0].value', message: 'x' },
          ]),
        );
      const { result, removed } = run(post);
      expect(await result).toEqual({ kind: 'invalid', dropped: 3 });
      expect(post).toHaveBeenCalledTimes(1);
      expect(removed).toEqual([three]);
    });

    it('모든 항목이 거절되면 다시 보내지 않는다', async () => {
      const post = vi.fn().mockRejectedValueOnce(
        new ApiError('validation', 400, 'CART_115', null, [
          { code: 'CART_115', field: 'items[0].customOptions[0].value', message: 'x' },
          { code: 'CART_115', field: 'items[1].customOptions[0].value', message: 'x' },
          { code: 'CART_115', field: 'items[2].customOptions[0].value', message: 'x' },
        ]),
      );
      const { result, removed } = run(post);
      expect(await result).toEqual({ kind: 'invalid', dropped: 3 });
      expect(post).toHaveBeenCalledTimes(1);
      expect(removed).toEqual([three]);
    });
  });

  it('완료 기준(#263): 탭이 섞인 맞춤 입력이 있는 로컬 장바구니로 로그인해도 다른 줄이 사라지지 않는다', async () => {
    // 입력 문자 규칙 전에 저장된 로컬 장바구니 — BE 처럼 제어 문자가 있으면 요청 전체를 400 CART_115 로 거절하는 서버로 보낸다
    const stored = JSON.stringify([
      line('1', 1),
      { ...line('2', 2), customOptions: [{ fieldKey: 'memo', value: '소매\t길게' }] },
      line('3', 3),
    ]);
    const post = vi.fn(async (items: CartItemRequest[]) => {
      const index = items.findIndex((item) =>
        (item.customOptions ?? []).some((option) => hasRejectedCharacters(option.value)),
      );
      if (index >= 0) {
        throw new ApiError('validation', 400, 'CART_115', null, [
          { code: 'CART_115', field: `items[${index}].customOptions[0].value`, message: 'x' },
        ]);
      }
      return { cart: {}, skippedItems: [] };
    });
    const removed: unknown[] = [];
    const result = await mergeLocalCart({
      read: () => parseLines(stored),
      remove: (batch) => removed.push(batch),
      post: post as never,
    });
    expect(result).toEqual({ kind: 'merged', skipped: 0 });
    expect(post).toHaveBeenCalledTimes(1);
    expect(post.mock.calls[0]![0].map((item) => item.skuId)).toEqual(['1', '2', '3']);
    expect(post.mock.calls[0]![0][1]?.customOptions).toEqual([
      { fieldKey: 'memo', value: '소매 길게' },
    ]);
  });
});
