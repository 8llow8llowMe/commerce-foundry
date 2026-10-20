import { ApiError } from '@cf/api-client';
import { describe, expect, it, vi } from 'vitest';
import { addMemberLines, type AddLine } from './add-to-cart';

vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: vi.fn(), refresh: vi.fn() }) }));

const line = (skuId: string): AddLine => ({
  productId: '1',
  skuId,
  quantity: 1,
  customOptions: [],
  maxQuantity: null,
});
const lines = [line('1'), line('2'), line('3')];
const error = (kind: ConstructorParameters<typeof ApiError>[0], status: number | null) =>
  new ApiError(kind, status, null, null);

describe('addMemberLines', () => {
  it('모두 담기면 added, 맞춤 제작 입력이 없으면 customOptions 를 싣지 않는다', async () => {
    const post = vi.fn().mockResolvedValue({});
    expect(await addMemberLines(lines, post)).toEqual({ status: 'added' });
    expect(post).toHaveBeenCalledTimes(3);
    expect(post.mock.calls[0]![0]).toEqual({ skuId: '1', quantity: 1 });
  });

  it('한 줄이 거절돼도(409) 나머지는 담고, 첫 거절과 담긴 수를 돌려준다', async () => {
    const conflict = error('conflict', 409);
    const post = vi.fn().mockRejectedValueOnce(conflict).mockResolvedValue({});
    expect(await addMemberLines(lines, post)).toEqual({
      status: 'failed',
      error: conflict,
      addedCount: 2,
    });
    expect(post).toHaveBeenCalledTimes(3);
  });

  it('429 · 결과 모름(504) · 연결 실패면 멈춘다', async () => {
    for (const cause of [
      error('rateLimited', 429),
      error('unknownOutcome', 504),
      error('network', null),
    ]) {
      const post = vi
        .fn()
        .mockResolvedValueOnce({})
        .mockRejectedValueOnce(cause)
        .mockResolvedValue({});
      expect(await addMemberLines(lines, post)).toEqual({
        status: 'failed',
        error: cause,
        addedCount: 1,
      });
      expect(post).toHaveBeenCalledTimes(2);
    }
  });

  it('401 이면 남은 줄을 돌려준다 — 호출한 쪽이 로컬에 담는다', async () => {
    const post = vi
      .fn()
      .mockResolvedValueOnce({})
      .mockRejectedValueOnce(error('unauthorized', 401));
    expect(await addMemberLines(lines, post)).toEqual({ remaining: [line('2'), line('3')] });
  });
});
