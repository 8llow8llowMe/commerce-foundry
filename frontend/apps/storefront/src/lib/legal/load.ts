import { ApiError } from '@cf/api-client';
import type { Locale } from '@cf/ui';
import { cache } from 'react';
import { serverFetch } from '@/lib/server-api';
import { legalApiPath, type LegalDocument, type LegalLoad, type LegalType } from './document';

/**
 * 문서 읽기. **캐시하지 않는다** — BE 가 `no-store` 를 주고(본문 · 버전을 BFF · 브라우저 · 중간 캐시에 두지 않는다),
 * 게시 직후 다음 요청부터 새 게시본이 보여야 한다. 공개 · 사용자 무관이라 토큰은 싣지 않는다.
 * 같은 요청 안의 `generateMetadata` · 페이지는 `cache` 로 한 번만 부른다.
 */
export const loadLegalDocument = cache(
  async (type: LegalType, locale: Locale, revision: number | null): Promise<LegalLoad> => {
    try {
      const document = await serverFetch<LegalDocument>(
        legalApiPath(type, locale, revision),
        { cache: 'no-store' },
        { anonymous: true },
      );
      return { kind: 'ok', document };
    } catch (cause) {
      if (cause instanceof ApiError && cause.kind === 'notFound') return { kind: 'missing' };
      // 형식이 틀린 번호(400)도 "없는 버전" 으로 본다
      if (cause instanceof ApiError && cause.status === 400 && revision !== null)
        return { kind: 'missing' };
      return { kind: 'failed' };
    }
  },
);
