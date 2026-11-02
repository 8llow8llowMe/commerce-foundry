import { ApiError } from '@cf/api-client';
import type { HomeSection, LookSection } from '@cf/ui';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { LOOKS_PATH, MAIN_LOOK_CANDIDATES } from '@/lib/catalog/look-products';
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
// 테스트마다 바꿔 끼우는 브랜드 설정 메인
let template: HomeSection[] = TEMPLATE;
vi.mock('@/lib/i18n/server', () => ({
  getI18n: async () => ({
    dict: { home: { more: '더 보기', viewAll: 'VIEW ALL', shop: 'SHOP', craftOverview: '' } },
    content: { home: template },
  }),
}));

beforeEach(() => {
  serverFetch.mockReset();
  template = TEMPLATE;
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

describe('loadHomeBlocks — 설정 LOOK 대신 룩 API', () => {
  const LOOK: LookSection = {
    type: 'LOOK',
    id: 'shop-the-look',
    title: 'Shop the Look',
    body: null,
    media: { image: null, placeholder: '착장' },
    productIds: [],
    link: null,
  };
  const looksSlice = {
    contents: [
      { lookId: '1', title: '이미지 없는 룩', image: null, products: [] },
      {
        lookId: '2',
        title: '가을 룩',
        image: { url: 'https://cdn.example.com/2.jpg', width: null, height: null, altText: null },
        products: [{ productId: '9' }],
      },
    ],
    hasNext: false,
  };

  it('전시 API 를 못 쓰면 룩 목록을 토큰 없이 60초 캐시로 불러, 이미지 있는 첫 룩을 설정 LOOK 자리에 그린다', async () => {
    template = [TEMPLATE[0]!, LOOK];
    serverFetch.mockImplementation(async (path: string) => {
      if (path === DISPLAY_MAIN_PATH) throw new ApiError('unavailable', 503, null, null);
      return looksSlice;
    });
    const blocks = await loadHomeBlocks();
    expect(serverFetch).toHaveBeenCalledWith(
      `${LOOKS_PATH}?page=0&size=${MAIN_LOOK_CANDIDATES}`,
      { cache: 'force-cache', next: { revalidate: 60 } },
      { anonymous: true },
    );
    expect(blocks.map((block) => block.id)).toEqual(['brand-story', 'look-2']);
  });

  it('전시 LOOK 에 그릴 미디어가 없어 설정 LOOK 이 들어가도 룩 목록의 룩으로 바꾼다', async () => {
    template = [LOOK];
    serverFetch.mockImplementation(async (path: string) =>
      path === DISPLAY_MAIN_PATH
        ? {
            sections: [
              {
                sectionId: '5',
                sectionType: { code: 'LOOK', name: '룩', description: '' },
                title: null,
                subtitle: null,
                body: null,
                link: null,
                items: [],
              },
            ],
          }
        : looksSlice,
    );
    const blocks = await loadHomeBlocks();
    expect(blocks.map((block) => block.id)).toEqual(['look-2']);
  });

  it('룩 목록이 실패하거나 비면 설정 LOOK 그대로다', async () => {
    template = [LOOK];
    serverFetch.mockImplementation(async (path: string) => {
      if (path === DISPLAY_MAIN_PATH) return { sections: [] };
      throw new ApiError('network', null, null, null);
    });
    await expect(loadHomeBlocks()).resolves.toEqual([LOOK]);

    serverFetch.mockImplementation(async (path: string) =>
      path === DISPLAY_MAIN_PATH ? { sections: [] } : { contents: [], hasNext: false },
    );
    await expect(loadHomeBlocks()).resolves.toEqual([LOOK]);
  });

  it('설정 LOOK 이 들어가지 않으면 룩 목록을 부르지 않는다', async () => {
    template = [TEMPLATE[0]!, LOOK];
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
    await loadHomeBlocks();
    expect(serverFetch).toHaveBeenCalledTimes(1);
  });
});
