import { ApiError } from '@cf/api-client';
import type { HomeSection } from '@cf/ui';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DISPLAY_MAIN_PATH, DISPLAY_MAIN_REVALIDATE_SEC, loadHomeBlocks } from './load-home';

const serverFetch = vi.hoisted(() => vi.fn());
vi.mock('@/lib/server-api', () => ({ serverFetch }));

const TEMPLATE: HomeSection[] = [
  {
    type: 'STORY',
    id: 'brand-story',
    title: '브랜드 기본',
    body: '',
    links: [],
    media: [],
    layout: 'centered',
  },
];
vi.mock('@/lib/i18n/server', () => ({
  getI18n: async () => ({
    dict: { home: { more: '더 보기', viewAll: 'VIEW ALL', shop: 'SHOP', craftOverview: '' } },
    content: { home: TEMPLATE },
  }),
}));

beforeEach(() => {
  serverFetch.mockReset();
});

describe('loadHomeBlocks', () => {
  it('전시 API 를 토큰 없이 60초 캐시로 부르고 응답 섹션을 그린다', async () => {
    serverFetch.mockResolvedValue({
      sections: [
        {
          sectionId: '1',
          sectionType: { code: 'STORY', name: '스토리', description: '' },
          title: '운영 스토리',
          subtitle: null,
          body: null,
          link: null,
          items: [],
        },
      ],
    });
    const blocks = await loadHomeBlocks();
    expect(serverFetch).toHaveBeenCalledWith(
      DISPLAY_MAIN_PATH,
      { cache: 'force-cache', next: { revalidate: DISPLAY_MAIN_REVALIDATE_SEC } },
      { anonymous: true },
    );
    expect(DISPLAY_MAIN_REVALIDATE_SEC).toBe(60);
    expect(blocks.map((block) => block.id)).toEqual(['display-1']);
  });

  it.each([
    ['장애(503)', new ApiError('unavailable', 503, 'GATEWAY_002', null)],
    ['연결 실패', new ApiError('network', null, null, null)],
    ['봉투 아님', new ApiError('invalidResponse', 200, null, null)],
  ])('%s 면 브랜드 설정으로 메인을 그린다', async (_, error) => {
    serverFetch.mockRejectedValue(error);
    await expect(loadHomeBlocks()).resolves.toBe(TEMPLATE);
  });

  it('보이는 섹션이 없으면(빈 배열) 브랜드 설정이다', async () => {
    serverFetch.mockResolvedValue({ sections: [] });
    await expect(loadHomeBlocks()).resolves.toBe(TEMPLATE);
  });
});
