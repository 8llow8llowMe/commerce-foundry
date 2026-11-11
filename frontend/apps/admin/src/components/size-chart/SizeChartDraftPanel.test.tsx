import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { SizeChartDraft } from '@/lib/size-chart/draft';
import { SizeChartDraftPanel } from './SizeChartDraftPanel';
import type { SizeChartDraftState } from './useSizeChartDraft';
import type { SizeChartImageSourcesState } from './useSizeChartImageSources';

const noop = () => {};
const meta = (code: string, name: string, description = '') => ({ code, name, description });

const DRAFT: SizeChartDraft = {
  draftId: '7',
  productId: '3',
  jobId: '99',
  status: meta('READY', '검토 대기', '치수와 경고를 검토한 뒤 적용해 주세요.'),
  version: 1,
  expiresAt: '2026-11-16T00:00:00Z',
  template: {
    templateId: '5',
    revision: 1,
    unit: meta('CM', '센티미터'),
    columns: [{ columnId: '11', name: '가슴 단면' }],
  },
  candidate: {
    unit: meta('CM', '센티미터'),
    rows: [{ sizeLabel: 'S', cells: [{ columnId: '11', value: null }] }],
    warnings: [],
  },
};

function state(overrides: Partial<SizeChartDraftState> = {}): SizeChartDraftState {
  return {
    draft: null,
    message: null,
    sourceError: null,
    submitting: false,
    checking: false,
    request: async () => null,
    check: async () => {},
    reload: async () => null,
    dismiss: noop,
    clearSourceError: noop,
    ...overrides,
  };
}

function imagesState(
  overrides: Partial<SizeChartImageSourcesState> = {},
): SizeChartImageSourcesState {
  return {
    status: 'idle',
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

const render = (ai: SizeChartDraftState, templateId: string | null = '5', reviewing = false) =>
  renderToStaticMarkup(
    <SizeChartDraftPanel
      ai={ai}
      images={imagesState()}
      templateId={templateId}
      reviewing={reviewing}
      busy={false}
      onReview={noop}
    />,
  );

describe('SizeChartDraftPanel', () => {
  it('초안이 없으면 텍스트 입력과 요청 버튼을 보이고, 템플릿이 없으면 먼저 고르라고 한다', () => {
    const html = render(state(), null);
    expect(html).toContain('치수 텍스트');
    expect(html).toContain('AI 초안 요청');
    expect(html).toContain('먼저 위에서 템플릿을 골라 주세요.');
    // 입력 방식은 텍스트가 기본이고, 이미지 입력을 고를 수 있다(목록은 고를 때 읽는다)
    expect(html).toContain('입력 방식');
    expect(html).toContain('상품 이미지');
    expect(html).toMatch(/<input(?=[^>]*value="TEXT")(?=[^>]*checked="")[^>]*>/);
    expect(html).not.toContain('이미지 목록을 불러오는 중');
  });

  it('생성 중이면 서버 상태 이름 · 작업 진행 링크 · 결과 확인을 보이고 입력칸은 숨긴다', () => {
    const html = render(
      state({ draft: { ...DRAFT, status: meta('REQUESTED', '생성 중'), candidate: null } }),
    );
    expect(html).toContain('생성 중');
    expect(html).toContain('href="/jobs/99"');
    expect(html).toContain('결과 확인');
    expect(html).not.toContain('AI 초안 요청');
  });

  it('검토 대기면 요약과 표에 불러오기를, 검토 중이면 안내만 보인다', () => {
    const ready = render(state({ draft: DRAFT }));
    expect(ready).toContain('사이즈 1개 · 빈 치수 1칸 · 확인할 경고 0건');
    expect(ready).toContain('표에 불러와 검토');
    const reviewing = render(state({ draft: DRAFT }), '5', true);
    expect(reviewing).toContain('표에서 검토 중입니다');
    expect(reviewing).not.toContain('버리고 새로 요청');
  });

  it('실패한 초안은 상태와 함께 다시 요청할 입력칸을 보인다', () => {
    const html = render(
      state({
        draft: {
          ...DRAFT,
          status: meta('FAILED', '생성 실패', '입력한 치수를 확인한 뒤 새 초안을 요청해 주세요.'),
        },
        message: '실패 안내',
      }),
    );
    expect(html).toContain('생성 실패');
    expect(html).toContain('AI 초안 요청');
    expect(html).toContain('실패 안내');
  });
});
