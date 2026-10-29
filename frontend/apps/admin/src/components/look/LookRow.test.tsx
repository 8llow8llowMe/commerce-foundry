import { DndContext } from '@dnd-kit/core';
import { SortableContext } from '@dnd-kit/sortable';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { LookAdmin } from '@/lib/look/types';
import { LookRow, type LookRowProps } from './LookRow';

const noop = () => {};

const look = (patch: Partial<LookAdmin> = {}): LookAdmin => ({
  lookId: '4611686018427387905',
  title: '가을 룩',
  description: null,
  image: { url: 'https://media.example.test/a.jpg', width: null, height: null, altText: null },
  visible: true,
  products: [
    { productId: '4611686018427387904', name: '저고리', visible: true, saleStatus: null },
    { productId: '12', name: '치마', visible: false, saleStatus: null },
  ],
  ...patch,
});

function render(target: LookAdmin, overrides: Partial<LookRowProps> = {}) {
  return renderToStaticMarkup(
    <DndContext id="test">
      <SortableContext items={[target.lookId]}>
        <ul>
          <LookRow
            look={target}
            position={1}
            total={3}
            reorderLocked={false}
            actionsLocked={false}
            toggling={false}
            confirming={false}
            deleting={false}
            error={null}
            onMove={noop}
            onToggle={noop}
            onEdit={noop}
            onAskDelete={noop}
            onCancelDelete={noop}
            onDelete={noop}
            {...overrides}
          />
        </ul>
      </SortableContext>
    </DndContext>,
  );
}

const disabledButton = (html: string, label: string) =>
  new RegExp(`<button[^>]*aria-label="${label}"[^>]*disabled=""`).test(html) ||
  new RegExp(`<button[^>]*disabled=""[^>]*aria-label="${label}"`).test(html);

describe('LookRow', () => {
  it('제목 · 노출 상태 · 상품 요약을 보이고 아이디는 보이지 않는다', () => {
    const html = render(look());
    expect(html).toContain('가을 룩');
    expect(html).toContain('노출 중');
    expect(html).toContain('상품 2개 · 숨긴 상품 1개');
    // 아이디는 포커스 표식(data-focus)에만 있고 글자로는 보이지 않는다
    expect(html).not.toMatch(/>[^<]*46116860184273879\d\d[^<]*</);
    expect(html).not.toContain('4611686018427387904');
  });

  it('숨긴 룩은 숨김으로 보인다', () => {
    expect(render(look({ visible: false }))).toContain('>숨김<');
  });

  it('첫 줄은 위로를 막고, 순서를 옮길 수 없으면 손잡이 · 위 · 아래를 모두 막는다', () => {
    const first = render(look());
    expect(disabledButton(first, '가을 룩 위로')).toBe(true);
    expect(disabledButton(first, '가을 룩 아래로')).toBe(false);
    const locked = render(look(), { position: 2, reorderLocked: true });
    expect(disabledButton(locked, '가을 룩 순서 바꾸기')).toBe(true);
    expect(disabledButton(locked, '가을 룩 위로')).toBe(true);
    expect(disabledButton(locked, '가을 룩 아래로')).toBe(true);
  });

  it('다른 일이 진행 중이면 노출 · 수정 · 삭제를 막는다', () => {
    const html = render(look(), { actionsLocked: true });
    expect(disabledButton(html, '가을 룩 수정')).toBe(true);
    expect(disabledButton(html, '가을 룩 삭제')).toBe(true);
    expect(html).toMatch(/<input[^>]*disabled=""[^>]*aria-label="가을 룩 고객에게 노출"/);
  });

  it('삭제 확인 단계에서는 삭제 · 취소를 보인다', () => {
    const html = render(look(), { confirming: true });
    expect(html).toContain('이 룩을 삭제할까요?');
    expect(html).toContain('>취소<');
  });
});
