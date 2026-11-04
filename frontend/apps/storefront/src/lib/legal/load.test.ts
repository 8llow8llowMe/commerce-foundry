import { ApiError } from '@cf/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { formatPublishedDate, legalApiPath, parseRevision } from './document';
import { loadLegalDocument } from './load';

const serverFetch = vi.hoisted(() => vi.fn());
vi.mock('@/lib/server-api', () => ({ serverFetch }));

beforeEach(() => serverFetch.mockReset());

describe('parseRevision', () => {
  it('1 이상 정수만, 아니면 현재 게시본', () => {
    expect(parseRevision('3')).toBe(3);
    expect(parseRevision(['12', '4'])).toBe(12);
    for (const value of [undefined, '', '0', '-1', '1.5', '01', 'abc', '1234567890'])
      expect(parseRevision(value)).toBeNull();
  });
});

describe('legalApiPath', () => {
  it('언어는 query 로 싣는다', () => {
    expect(legalApiPath('TERMS', 'ko', null)).toBe('/api/v1/store/legal-documents/TERMS?locale=ko');
    expect(legalApiPath('PRIVACY', 'en', 2)).toBe(
      '/api/v1/store/legal-documents/PRIVACY/revisions/2?locale=en',
    );
  });
});

describe('loadLegalDocument', () => {
  it('토큰 없이 캐시하지 않고 부른다(게시 직후 바로 반영)', async () => {
    serverFetch.mockResolvedValue({ revisionNumber: 1 });
    const result = await loadLegalDocument('TERMS', 'en', null);
    expect(result).toEqual({ kind: 'ok', document: { revisionNumber: 1 } });
    expect(serverFetch).toHaveBeenCalledWith(
      '/api/v1/store/legal-documents/TERMS?locale=en',
      { cache: 'no-store' },
      { anonymous: true },
    );
  });

  it('404 는 없음, 그 밖은 실패', async () => {
    serverFetch.mockRejectedValueOnce(new ApiError('notFound', 404, 'LEGAL_DOCUMENT_001', null));
    expect(await loadLegalDocument('PRIVACY', 'ko', 7)).toEqual({ kind: 'missing' });
    serverFetch.mockRejectedValueOnce(new ApiError('server', 500, null, null));
    expect(await loadLegalDocument('PRIVACY', 'ko', null)).toEqual({ kind: 'failed' });
  });
});

describe('formatPublishedDate', () => {
  it('스토어 언어 · 한국 날짜, 읽을 수 없으면 null', () => {
    expect(formatPublishedDate('2026-11-02T15:30:00Z', 'ko')).toBe('2026년 11월 3일');
    expect(formatPublishedDate('2026-11-02T15:30:00Z', 'en')).toBe('November 3, 2026');
    expect(formatPublishedDate('x', 'ko')).toBeNull();
  });
});
