import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { LookAdmin } from '@/lib/look/types';
import { LookPickField, lookNotice } from './LookPickField';

// 테스트용 가짜 룩 — 실데이터를 쓰지 않는다(공개 저장소).
const look = (overrides: Partial<LookAdmin> = {}): LookAdmin => ({
  lookId: '7',
  title: '샘플 룩',
  description: null,
  image: { url: 'https://cdn.example.test/looks/a.jpg', width: null, height: null, altText: null },
  visible: true,
  products: [{ productId: '11', name: '상품 11', visible: true, saleStatus: null }],
  ...overrides,
});

describe('lookNotice', () => {
  it('목록을 못 읽었으면 알리지 않고, 지워진 · 숨긴 룩과 상품이 모두 숨김인 룩은 알린다', () => {
    expect(lookNotice(undefined)).toBeNull();
    expect(lookNotice(look())).toBeNull();
    expect(lookNotice(null)).toContain('찾을 수 없는 룩');
    expect(lookNotice(look({ visible: false }))).toContain('숨긴 룩');
    expect(
      lookNotice(
        look({ products: [{ productId: '11', name: null, visible: false, saleStatus: null }] }),
      ),
    ).toContain('화보만 보입니다');
  });
});

describe('LookPickField', () => {
  it('고른 룩 수 · 범위를 보이고, 목록을 읽기 전에는 불러오는 중이다', () => {
    const html = renderToStaticMarkup(
      <LookPickField min={1} max={10} lookIds={['7', '8']} errors={[]} onChange={() => {}} />,
    );
    expect(html).toContain('룩 (2 / 10개)');
    expect(html).toContain('1개 이상 10개까지');
    expect(html).toContain('불러오는 중');
    expect(html).toContain('aria-label="이름을 불러오지 못한 룩 위로"');
  });

  it('비었으면 안내하고 칸 오류를 보인다', () => {
    const html = renderToStaticMarkup(
      <LookPickField
        min={1}
        max={10}
        lookIds={[]}
        errors={['룩은 1개 이상 10개 이하로 골라 주세요.']}
        onChange={() => {}}
      />,
    );
    expect(html).toContain('아직 넣은 룩이 없습니다');
    expect(html).toContain('룩은 1개 이상 10개 이하로 골라 주세요.');
  });
});
