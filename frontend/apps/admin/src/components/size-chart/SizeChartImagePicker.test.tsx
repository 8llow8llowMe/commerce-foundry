import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { ImageSource } from '@/lib/size-chart/draft-image';
import { SizeChartImagePicker } from './SizeChartImagePicker';
import type { SizeChartImageSourcesState } from './useSizeChartImageSources';

const noop = () => {};

const source = (sourceId: string, overrides: Partial<ImageSource> = {}): ImageSource => ({
  sourceId,
  previewUrl: `https://cdn.example.com/products/3/${sourceId}.jpg`,
  eligible: true,
  reason: null,
  ...overrides,
});

function images(overrides: Partial<SizeChartImageSourcesState> = {}): SizeChartImageSourcesState {
  return {
    status: 'ready',
    sources: [],
    message: null,
    selected: [],
    inspections: {},
    approved: null,
    load: noop,
    reload: async () => {},
    toggle: noop,
    reinspect: noop,
    close: noop,
    ...overrides,
  };
}

const render = (state: SizeChartImageSourcesState, errorId: string | null = null) =>
  renderToStaticMarkup(
    <SizeChartImagePicker images={state} disabled={false} idPrefix="p" errorId={errorId} />,
  );

describe('SizeChartImagePicker', () => {
  it('읽는 중 · 닫힘 · 빈 목록 · 실패를 구분해 보인다', () => {
    expect(render(images({ status: 'loading' }))).toContain('이미지 목록을 불러오는 중');
    expect(render(images({ status: 'closed' }))).toBe('');
    expect(render(images())).toContain('이 상품에 등록된 이미지가 없습니다');
    expect(render(images({ status: 'error' }))).toContain('이미지 목록 다시 불러오기');
  });

  it('쓸 수 없는 후보는 체크 없이 서버 사유를 보이고, 외부 주소는 미리보기가 없다', () => {
    const html = render(
      images({
        sources: [
          source('IMG-1'),
          source('DESC-0123456789abcdef0123456789abcdef', {
            previewUrl: null,
            eligible: false,
            reason: {
              code: 'EXTERNAL_IMAGE',
              name: '외부 주소 이미지',
              description: '우리 저장소 밖의 이미지는 사용할 수 없습니다.',
            },
          }),
        ],
      }),
    );
    expect(html.match(/type="checkbox"/g)).toHaveLength(1);
    expect(html).toContain('외부 주소 이미지 — 우리 저장소 밖의 이미지는 사용할 수 없습니다.');
    expect(html).toContain('미리보기 없음');
    expect(html).toContain('src="https://cdn.example.com/products/3/IMG-1.jpg"');
  });

  it('고른 순서와 확인 상태(확인 중 · 확인됨 · 오류)를 칸마다 보인다', () => {
    const html = render(
      images({
        sources: [source('IMG-1'), source('IMG-2'), source('IMG-3')],
        selected: ['IMG-2', 'IMG-1', 'IMG-3'],
        inspections: {
          'IMG-1': {
            status: 'ok',
            inspection: {
              sourceId: 'IMG-1',
              contentHash: 'a'.repeat(64),
              contentType: 'image/png',
              contentLength: 512 * 1024,
            },
          },
          'IMG-2': { status: 'checking' },
          'IMG-3': { status: 'error', message: '형식 안내' },
        },
      }),
    );
    expect(html).toContain('고른 이미지 3 / 4장');
    expect(html).toContain('이미지 1 (2번째로 읽음)');
    expect(html).toContain('이미지 2 (1번째로 읽음)');
    expect(html).toContain('확인됨 · PNG · 512KB');
    expect(html).toContain('이미지 확인 중…');
    expect(html).toContain('형식 안내');
  });

  it('4장을 고르면 남은 후보는 고를 수 없다', () => {
    const ids = ['IMG-1', 'IMG-2', 'IMG-3', 'IMG-4', 'IMG-5'];
    const html = render(
      images({ sources: ids.map((id) => source(id)), selected: ids.slice(0, 4) }),
    );
    // 고른 4장은 풀 수 있고 다섯 번째만 막힌다
    expect(html.match(/type="checkbox"[^>]*disabled=""/g)).toHaveLength(1);
  });

  it('오류 칸은 오류가 있을 때만 목록 설명에 잇고, 고른 칸의 확인 상태는 체크박스 설명 · live region 이다', () => {
    const state = images({
      sources: [source('IMG-1')],
      selected: ['IMG-1'],
      inspections: { 'IMG-1': { status: 'error', message: '형식 안내' } },
    });
    expect(render(state)).toContain('aria-describedby="p-images-help"');
    expect(render(state, 'p-error')).toContain('aria-describedby="p-images-help p-error"');
    const html = render(state);
    expect(html).toMatch(/type="checkbox"[^>]*aria-describedby="p-image-0-status"/);
    expect(html).toMatch(/<p id="p-image-0-status" role="status"[^>]*>형식 안내<\/p>/);
  });
});
