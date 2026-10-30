import { DndContext } from '@dnd-kit/core';
import { SortableContext } from '@dnd-kit/sortable';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { mediaItem, productItem, section } from '@/lib/display/test-fixtures';
import type { DisplaySectionAdmin } from '@/lib/display/types';
import { SectionRow, type SectionRowProps } from './SectionRow';

const noop = () => {};

function render(target: DisplaySectionAdmin, overrides: Partial<SectionRowProps> = {}) {
  return renderToStaticMarkup(
    <DndContext id="test">
      <SortableContext items={[target.sectionId]}>
        <ul>
          <SectionRow
            section={target}
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

describe('SectionRow', () => {
  it('유형 · 제목 · 노출 상태 · 요약을 보이고 아이디 · 코드값은 보이지 않는다', () => {
    const html = render(
      section('PRODUCT_PICK', { items: [productItem('11'), productItem('12')] }, 'SCHEDULED'),
    );
    expect(html).toContain('상품 픽');
    expect(html).toContain('샘플 제목');
    expect(html).toContain('예정');
    expect(html).toContain('기간 제한 없음 · 상품 2개');
    expect(html).not.toContain('PRODUCT_PICK');
    expect(html).not.toContain('SCHEDULED');
    expect(html).not.toMatch(/>4611686018427387904</);
  });

  it('첫 줄은 위로를 막고, 손잡이 · 아래로는 쓸 수 있다', () => {
    const html = render(section('STORY'));
    expect(disabledButton(html, '샘플 제목 위로')).toBe(true);
    expect(disabledButton(html, '샘플 제목 아래로')).toBe(false);
    expect(disabledButton(html, '샘플 제목 순서 바꾸기')).toBe(false);
    expect(html).toContain('aria-roledescription="순서 바꾸기 손잡이"');
  });

  it('순서를 옮길 수 없으면 손잡이 · 위 · 아래를 모두 막는다', () => {
    const html = render(section('STORY'), { position: 2, reorderLocked: true });
    expect(disabledButton(html, '샘플 제목 순서 바꾸기')).toBe(true);
    expect(disabledButton(html, '샘플 제목 위로')).toBe(true);
    expect(disabledButton(html, '샘플 제목 아래로')).toBe(true);
  });

  it('LOOK 은 다른 유형처럼 수정할 수 있다', () => {
    const html = render(section('LOOK', { items: [mediaItem()] }));
    expect(html).toContain('aria-label="샘플 제목 수정"');
    expect(html).not.toContain('편집 준비 중');
  });

  it('모르는 유형은 수정 대신 편집 준비 중 안내, 노출 · 삭제는 그대로다', () => {
    const base = section('STORY');
    const html = render({ ...base, sectionType: { ...base.sectionType, code: 'BANNER' } });
    expect(html).toContain('편집 준비 중');
    expect(html).not.toContain('aria-label="샘플 제목 수정"');
    expect(html).toContain('aria-label="샘플 제목 삭제"');
    expect(html).toContain('aria-label="샘플 제목 메인에 노출"');
  });

  it('삭제 확인 단계에서는 삭제 · 취소를 보인다', () => {
    const html = render(section('STORY'), { confirming: true });
    expect(html).toContain('이 섹션을 삭제할까요?');
    expect(html).toContain('>취소<');
  });

  it('다른 일이 진행 중이면 노출 · 수정 · 삭제를 막는다', () => {
    const html = render(section('STORY'), { actionsLocked: true });
    expect(disabledButton(html, '샘플 제목 수정')).toBe(true);
    expect(disabledButton(html, '샘플 제목 삭제')).toBe(true);
    expect(html).toMatch(/<input[^>]*disabled=""[^>]*aria-label="샘플 제목 메인에 노출"/);
  });
});
