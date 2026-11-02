import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { PeerOtpRecoveryForm } from './PeerOtpRecoveryForm';
import { RecoveryCodesPanel } from './RecoveryCodesPanel';

const target = { memberId: '2', name: '김**', email: 'ki***@shop.test' };
const noop = () => {};
const render = (remainingSec = 0) =>
  renderToStaticMarkup(
    <PeerOtpRecoveryForm
      target={target}
      cooldown={{ remainingSec, startFrom: () => false }}
      onRecovered={noop}
      onGone={noop}
      onDenied={noop}
      onCancel={noop}
    />,
  );

describe('PeerOtpRecoveryForm', () => {
  it('대상과 무효화되는 것을 실행 전에 보인다', () => {
    const html = render();
    expect(html).toContain('김**');
    expect(html).toContain('ki***@shop.test');
    expect(html).toContain('기존 인증 앱 등록과 복구 코드가 모두 무효화');
    expect(html).toContain('지금 로그인이 끊깁니다');
    expect(html).toContain('한 번만 보여 드립니다');
    expect(html).toContain('무효화되는 것을 확인했습니다');
  });

  it('행위자 본인의 비밀번호 · 코드를 받는 칸은 값을 들고 있지 않다(제어하지 않는 칸)', () => {
    const html = render();
    expect(html).toContain('내 현재 비밀번호');
    expect(html).toContain('내 인증 코드 또는 복구 코드');
    const password = html.match(/<input[^>]*name="currentPassword"[^>]*>/)?.[0] ?? '';
    const code = html.match(/<input[^>]*name="code"[^>]*>/)?.[0] ?? '';
    expect(password).toContain('type="password"');
    expect(password).toContain('autoComplete="current-password"');
    expect(password).not.toContain('value=');
    expect(code).toContain('autoComplete="one-time-code"');
    expect(code).not.toContain('value=');
  });

  it('429 쿨다운 동안에는 복구 버튼을 막고 남은 시간을 안내한다', () => {
    const html = render(120);
    expect(html).toMatch(
      /<button[^>]*type="submit"[^>]*disabled=""[^>]*>2단계 인증 복구<\/button>/,
    );
    expect(html).toContain('2분 뒤에 다시 시도해 주세요');
  });
});

describe('RecoveryCodesPanel (동료 복구)', () => {
  const codes = ['aaaaa-bbbbb', 'ccccc-ddddd', 'eeeee-fffff'];

  it('대상 이름 · 코드 · 전달 방법 · 대상의 다음 순서를 보이고, 전달 체크 전에는 완료를 막는다', () => {
    const html = renderToStaticMarkup(
      <RecoveryCodesPanel codes={codes} regenerated={false} recipient="김**" onClose={noop} />,
    );
    expect(html).toContain('김** 님의 새 복구 코드');
    for (const code of codes) expect(html).toContain(code);
    expect(html).toContain('2개 이상을 대상');
    expect(html).toContain('확인된 별도 경로로만');
    expect(html).toContain('단체 대화방 · 이메일 · 업무 티켓 · 이슈에는 붙여 넣지 마세요');
    expect(html).toContain('복구 코드 하나를 넣어 로그인');
    expect(html).toContain('로그인에 쓰지');
    expect(html).toContain('다시 등록');
    expect(html).toContain('대상 관리자에게 코드를 전했거나');
    expect(html).toMatch(/<button[^>]*disabled=""[^>]*>완료<\/button>/);
  });

  it('내 코드 화면에는 동료 안내를 싣지 않는다', () => {
    const html = renderToStaticMarkup(
      <RecoveryCodesPanel codes={codes} regenerated={false} onClose={noop} />,
    );
    expect(html).not.toContain('님의 새 복구 코드');
    expect(html).not.toContain('대상 관리자');
  });
});
