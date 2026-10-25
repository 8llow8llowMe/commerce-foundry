import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { JobCancelControl } from './JobCancelControl';

const noop = () => {};
const render = (cancellable: boolean, confirming = false, cancelling = false) =>
  renderToStaticMarkup(
    <JobCancelControl
      job={{ cancellable }}
      confirming={confirming}
      cancelling={cancelling}
      onAsk={noop}
      onDismiss={noop}
      onConfirm={noop}
    />,
  );

describe('JobCancelControl', () => {
  it('cancellable 이면 취소 버튼을 보인다', () => {
    expect(render(true)).toContain('작업 취소');
  });

  it('cancellable 이 false 면(실행 중이라도 이미 취소 요청 · 끝난 작업) 아무것도 보이지 않는다', () => {
    expect(render(false)).toBe('');
    expect(render(false, true)).toBe('');
  });

  it('확인 단계는 확인 · 그만두기, 보내는 중에는 둘 다 막는다', () => {
    const asking = render(true, true);
    expect(asking).toContain('취소 확인');
    expect(asking).toContain('그만두기');
    expect(asking).not.toContain('disabled=""');
    const sending = render(true, true, true);
    expect(sending).toContain('취소하는 중…');
    expect(sending.match(/disabled=""/g)).toHaveLength(2);
  });
});
