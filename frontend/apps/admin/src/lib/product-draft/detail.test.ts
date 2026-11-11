import { ApiError } from '@cf/api-client';
import { describe, expect, it, vi } from 'vitest';
import type { CategoryRow } from '@/lib/category/tree';
import { draftFixture } from './test-fixtures';
import {
  canReject,
  CATEGORY_MISSING,
  CATEGORY_UNKNOWN,
  categoryLabel,
  formatDraftTime,
  loadProductDraft,
  statusNote,
} from './detail';

const status = (code: string) => ({ code, name: code, description: '' });

describe('상태 판정', () => {
  it.each([
    ['REQUESTED', true],
    ['GENERATING', true],
    ['READY', true],
    ['FAILED', true],
    ['APPLIED', false],
    ['REJECTED', false],
    ['EXPIRED', false],
  ])('%s 거절 가능 = %s', (code, expected) => {
    expect(canReject(status(code))).toBe(expected);
  });

  it('생성 대기 · 생성 중은 결과가 아직 없다고 정직하게 안내한다', () => {
    expect(statusNote(status('REQUESTED'))).toContain('AI 초안 생성은 준비 중');
    expect(statusNote(status('REQUESTED'))).toContain('이 화면에서 확인');
    expect(statusNote(status('READY'))).toContain('적용하는 기능은 준비 중');
    expect(statusNote(status('REJECTED'))).toBeNull();
  });
});

describe('loadProductDraft', () => {
  it('형식이 틀린 아이디는 부르지 않고 없는 초안이다', async () => {
    const load = vi.fn();
    expect(await loadProductDraft('0', load)).toEqual({ kind: 'notFound' });
    expect(await loadProductDraft('abc', load)).toEqual({ kind: 'notFound' });
    expect(load).not.toHaveBeenCalled();
  });

  it('BFF 경로로 읽는다', async () => {
    const draft = draftFixture();
    const load = vi.fn().mockResolvedValue(draft);
    expect(await loadProductDraft('42', load)).toEqual({ kind: 'ok', draft });
    expect(load).toHaveBeenCalledWith('/api/v1/admin/product-drafts/42');
  });

  it.each([
    [new ApiError('notFound', 404, 'PRODUCT_DRAFT_001', null), { kind: 'notFound' }],
    [new ApiError('validation', 400, 'PRODUCT_DRAFT_127', null), { kind: 'notFound' }],
    [new ApiError('forbidden', 403, null, null), { kind: 'denied', denied: 'forbidden' }],
    [new ApiError('unauthorized', 401, null, null), { kind: 'denied', denied: 'unauthorized' }],
    [new ApiError('server', 500, null, null), { kind: 'failed' }],
  ])('실패 %#', async (cause, expected) => {
    expect(await loadProductDraft('42', vi.fn().mockRejectedValue(cause))).toEqual(expected);
  });
});

describe('categoryLabel', () => {
  const rows = [
    { node: { categoryId: '12' }, path: ['상의', '저고리'] },
  ] as unknown as CategoryRow[];

  it('경로 이름을 보이고 아이디 원문은 보이지 않는다', () => {
    expect(categoryLabel(null, rows)).toBeNull();
    expect(categoryLabel('12', rows)).toBe('상의 > 저고리');
    expect(categoryLabel('99', rows)).toBe(CATEGORY_MISSING);
    expect(categoryLabel('12', null)).toBe(CATEGORY_UNKNOWN);
  });
});

describe('formatDraftTime', () => {
  it('한국 시각으로 보이고 없거나 틀리면 대시', () => {
    expect(formatDraftTime('2026-11-01T03:00:00Z')).toContain('12:00');
    expect(formatDraftTime(null)).toBe('—');
    expect(formatDraftTime('nope')).toBe('—');
  });
});
