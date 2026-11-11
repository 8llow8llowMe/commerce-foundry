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
    expect(html).toContain('AI 초안 생성은 준비 중');
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
});
