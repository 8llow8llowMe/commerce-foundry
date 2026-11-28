import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import {
  ITEM_ACCEPTED,
  ITEM_GONE,
  ITEM_REGISTRATION_UNKNOWN,
  type BatchItem,
} from '@/lib/size-chart/draft-batch';
import {
  TEXT_SOURCES_CHECK,
  TEXT_SOURCES_EMPTY,
  type TextSource,
  type TextSourcesState,
} from '@/lib/size-chart/text-source';
import { BatchItemRow } from './SizeChartBatch';
import { TextSourceOptions } from './TextSourceOptions';

const noop = () => {};

const SOURCE: TextSource = {
  sourceId: 'TEXT-KO-1',
  bodyHash: 'a'.repeat(64),
  sourceText: 'S  가슴 50  총장 60\nM  가슴 52  총장 62',
};

const ITEM: BatchItem = {
  productId: '4611686018427387904',
  productName: '린넨 셔츠',
  input: { kind: 'none' },
  result: { status: 'pending' },
};

const row = (item: BatchItem, sources: TextSourcesState = { status: 'idle' }, sending = false) =>
  renderToStaticMarkup(
    <ul>
      <BatchItemRow
        item={item}
        sending={sending}
        sources={sources}
        onInput={noop}
        onReload={noop}
        onRemove={noop}
      />
    </ul>,
  );

describe('TextSourceOptions', () => {
  it('후보 텍스트를 줄바꿈 그대로 보이고, 고른 후보에 체크한다(아이디 원문은 이름으로 바꾼다)', () => {
    const html = renderToStaticMarkup(
      <TextSourceOptions
        state={{ status: 'ok', sources: [SOURCE] }}
        name="row"
        selectedId="TEXT-KO-1"
        onPick={noop}
        onReload={noop}
        disabled={false}
      />,
    );
    expect(html).toContain(TEXT_SOURCES_CHECK);
    expect(html).toContain('국문 설명의 표 1');
    expect(html).toContain('whitespace-pre');
    expect(html).toContain('overflow-auto');
    expect(html).toContain('S  가슴 50  총장 60\nM  가슴 52  총장 62');
    expect(html).toMatch(/<input(?=[^>]*value="TEXT-KO-1")(?=[^>]*checked="")[^>]*>/);
  });

  it('후보가 없으면 직접 입력을 안내하고, 실패면 다시 불러오기를 둔다', () => {
    const empty = renderToStaticMarkup(
      <TextSourceOptions
        state={{ status: 'ok', sources: [] }}
        name="row"
        selectedId={null}
        onPick={noop}
        onReload={noop}
        disabled={false}
      />,
    );
    expect(empty).toContain(TEXT_SOURCES_EMPTY);
    const failed = renderToStaticMarkup(
      <TextSourceOptions
        state={{ status: 'error', message: '불러오지 못함' }}
        name="row"
        selectedId={null}
        onPick={noop}
        onReload={noop}
        disabled={false}
      />,
    );
    expect(failed).toContain('불러오지 못함');
    expect(failed).toContain('다시 불러오기');
  });
});

describe('BatchItemRow', () => {
  it('대기 중이면 후보 · 직접 붙여넣기 라디오를 한 묶음으로 보이고 상태를 글자로 적는다', () => {
    const html = row(ITEM, { status: 'ok', sources: [SOURCE] });
    expect(html).toContain('린넨 셔츠');
    expect(html).toContain('대기');
    expect(html).toContain('bg-border');
    expect(html).toContain('직접 붙여넣기');
    // 후보와 직접 입력이 같은 name 이라 하나만 고를 수 있다
    const names = [...html.matchAll(/<input(?=[^>]*type="radio")[^>]*name="([^"]+)"/g)].map(
      (match) => match[1],
    );
    expect(names).toHaveLength(2);
    expect(new Set(names).size).toBe(1);
    expect(html).not.toContain('<textarea');
    expect(html).toContain('목록에서 빼기');
  });

  it('후보를 골랐으면 고쳐 쓰기를, 직접 입력이면 입력칸을 보인다', () => {
    expect(row({ ...ITEM, input: { kind: 'source', source: SOURCE } })).toContain(
      '고른 표 고쳐 쓰기',
    );
    const direct = row({ ...ITEM, input: { kind: 'direct', text: 'S 50' } });
    expect(direct).toContain('<textarea');
    expect(direct).toContain('S 50');
  });

  it('접수되면 그 상품 사이즈표로 가는 검토 링크만 두고 입력 · 빼기를 숨긴다', () => {
    const html = row({ ...ITEM, result: { status: 'accepted', draftId: '7', jobId: '99' } });
    expect(html).toContain('접수됨');
    expect(html).toContain('bg-primary');
    expect(html).toContain('href="/size-charts/4611686018427387904"');
    expect(html).toContain('검토하러 가기');
    // 결과를 지키려고 새 탭으로 연다
    expect(html).toContain('target="_blank"');
    expect(html).toContain(ITEM_ACCEPTED);
    expect(html).not.toContain('직접 붙여넣기');
    expect(html).not.toContain('목록에서 빼기');
  });

  it('실패 · 다시 시도 가능은 띠와 함께 상태 글자와 사유를 보인다(오류 코드는 보이지 않는다)', () => {
    const failed = row({ ...ITEM, result: { status: 'failed', message: ITEM_GONE } });
    expect(failed).toContain('실패');
    expect(failed).toContain('bg-danger');
    expect(failed).toContain(ITEM_GONE);
    expect(failed).not.toContain('SIZE_CHART_DRAFT');
    const retryable = row({
      ...ITEM,
      input: { kind: 'direct', text: 'S 50' },
      result: {
        status: 'retryable',
        message: '다시 요청해 주세요.',
        newKey: false,
        unknown: false,
      },
    });
    expect(retryable).toContain('다시 시도 가능');
    expect(retryable).toContain('다시 요청해 주세요.');
    expect(retryable).toContain('목록에서 빼기');
    expect(row(ITEM, { status: 'idle' }, true)).toContain('요청 중');
  });

  it('접수 여부를 모르는 항목은 입력을 잠그고 빼기를 숨긴다', () => {
    const html = row({
      ...ITEM,
      input: { kind: 'direct', text: 'S 50' },
      result: {
        status: 'retryable',
        message: ITEM_REGISTRATION_UNKNOWN,
        newKey: false,
        unknown: true,
      },
    });
    expect(html).toContain(ITEM_REGISTRATION_UNKNOWN);
    expect(html).toMatch(/<fieldset[^>]*disabled=""/);
    expect(html).not.toContain('목록에서 빼기');
  });
});
