import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { draftFixture } from '@/lib/product-draft/test-fixtures';
import { ProductDraftDetailView } from './ProductDraftDetailView';

vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh: vi.fn() }) }));

const render = (
  patch: Parameters<typeof draftFixture>[0] = {},
  categoryName: string | null = null,
) =>
  renderToStaticMarkup(
    <ProductDraftDetailView draft={draftFixture(patch)} categoryName={categoryName} />,
  );

describe('ProductDraftDetailView', () => {
  it('상태 · 시각 · 생성 준비 중 안내 · 부족한 입력 · 사실 · 사진을 보인다', () => {
    const html = render({}, '상의 > 저고리');
    expect(html).toContain('생성 대기');
    expect(html).toMatch(/<button[^>]*>생성 시작<\/button>/);
    expect(html).toContain('요청 시각');
    expect(html).toContain('보관 만료');
    expect(html).toContain('카테고리</span> — 상품 카테고리를 골라 주세요.');
    expect(html).toContain('린넨 저고리');
    expect(html).toContain('린넨 100%');
    expect(html).toContain('상의 &gt; 저고리');
    expect(html).toContain('alt="대표 사진"');
    expect(html).toContain('새로고침');
    expect(html).toContain('초안 거절');
  });

  it('초안 · 회원 아이디와 상태 코드를 보이지 않는다(#305)', () => {
    const html = render();
    expect(html).not.toContain('4611686018427387904');
    expect(html).not.toContain('9876543210123');
    expect(html).not.toContain('REQUESTED');
  });

  it('입력하지 않은 사실 칸은 그리지 않는다', () => {
    const html = render();
    expect(html).not.toContain('원산지');
    expect(html).not.toContain('인증');
  });

  it.each([
    ['APPLIED', '적용 완료'],
    ['REJECTED', '거절'],
    ['EXPIRED', '기간 만료'],
  ])('%s 초안은 거절 버튼이 없다', (code, name) => {
    const html = render({
      status: { code, name, description: '' },
      rejectedAt: code === 'REJECTED' ? '2026-11-02T03:00:00Z' : null,
    });
    expect(html).not.toContain('초안 거절');
    expect(html).toContain('새로고침');
    if (code === 'REJECTED') expect(html).toContain('거절 시각');
  });

  it('부족한 입력이 없으면 그 절을 그리지 않는다', () => {
    expect(render({ missingRequiredInputs: [] })).not.toContain('더 넣어야 할 것');
  });

  it('생성 중이면 결과 확인 버튼을, 실패면 실패 안내를 보인다', () => {
    const generating = render({
      status: { code: 'GENERATING', name: '생성 중', description: '만들고 있습니다.' },
    });
    expect(generating).toMatch(/<button[^>]*>결과 확인<\/button>/);
    expect(generating).not.toMatch(/<button[^>]*>생성 시작<\/button>/);

    const failed = render({
      status: { code: 'FAILED', name: '생성 실패', description: '실패했습니다.' },
      failureNotice: '초안을 만들지 못했습니다. 새 초안을 요청해 주세요.',
    });
    expect(failed).toContain('초안을 만들지 못했습니다. 새 초안을 요청해 주세요.');
    expect(failed).not.toMatch(/<button[^>]*>(결과 확인|생성 시작)<\/button>/);
  });

  it('검토 대기면 제안 · 사진 대체 텍스트 · 확인할 사실 · 표현 경고를 평문으로 보인다', () => {
    const html = render({
      status: { code: 'READY', name: '검토 대기', description: '검토해 주세요.' },
      result: {
        suggestion: {
          name: '린넨 생활한복 저고리',
          description: '가벼운 린넨 저고리<script>',
          searchTags: ['저고리', '린넨'],
          seoTitle: null,
          seoDescription: '',
          imageAlts: [{ index: 0, altText: '아이보리 린넨 저고리 정면' }],
        },
        unconfirmedFacts: [
          { code: 'MATERIAL', name: '소재', description: '소재를 확인해 주세요.' },
        ],
        warnings: [
          {
            code: 'MUST_AVOID_USED',
            name: '제외 표현 사용',
            description: '제안에 쓰지 않을 표현이 있습니다.',
            index: 0,
          },
        ],
      },
    });
    expect(html).toContain('AI 제안');
    expect(html).toContain('린넨 생활한복 저고리');
    expect(html).toContain('가벼운 린넨 저고리&lt;script&gt;');
    expect(html).toContain('저고리, 린넨');
    expect(html).not.toContain('검색 노출 제목');
    expect(html).not.toContain('검색 노출 설명');
    expect(html).toContain('대표 사진: 아이보리 린넨 저고리 정면');
    expect(html).toContain('직접 확인해야 할 사실');
    expect(html).toContain('제외 표현 사용</span> &quot;최고&quot;');
    expect(html).not.toMatch(/<button[^>]*>(결과 확인|생성 시작)<\/button>/);
  });
});
