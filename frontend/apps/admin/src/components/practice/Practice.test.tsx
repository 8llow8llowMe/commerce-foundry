import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { apiFetch } from '@cf/api-client';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PRACTICE_ORDERS } from '@/lib/practice/fixtures';
import {
  INITIAL_PRACTICE,
  isFinished,
  practiceReducer,
  runPracticeAction,
  SCENARIOS,
  type ScenarioId,
} from '@/lib/practice/scenario';
import { createPracticeTransport, type PracticeAction } from '@/lib/practice/transport';
import { PracticeOrderCard, StepAction } from './PracticeParts';
import { PracticeScreen } from './PracticeScreen';

vi.mock('@cf/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@cf/api-client')>()),
  apiFetch: vi.fn(),
}));

const noop = () => {};

describe('PracticeScreen', () => {
  it('연습 표시 · 처음부터 다시 · 연습 끝내기와 두 시나리오를 보인다', () => {
    const html = renderToStaticMarkup(<PracticeScreen />);
    expect(html).toContain('role="note"');
    expect(html).toContain('연습 모드');
    expect(html).toContain('실제 주문 · 결제 · 재고 · 배송 · 고객 알림에 아무것도 반영되지');
    expect(html).toContain('>처음부터 다시<');
    expect(html).toContain('href="/"');
    expect(html).toContain('>연습 끝내기<');
    expect(html).toContain('주문 준비 → 송장 입력');
    expect(html).toContain('주문 취소 → 환불 결과');
    expect(html.match(/>이 연습 시작</g)).toHaveLength(2);
    // "다음" 으로 넘기는 버튼은 없다
    expect(html).not.toContain('>다음<');
  });
});

describe('PracticeParts', () => {
  it('샘플 주문 금액은 fixture 값을 formatMoney 로 보인다', () => {
    const html = renderToStaticMarkup(<PracticeOrderCard order={PRACTICE_ORDERS.ship} />);
    expect(html).toContain('SAMPLE-0001');
    expect(html).toContain('결제 완료');
    expect(html).toContain('93,000원');
  });

  it('단계마다 그 단계의 샘플 버튼 · 입력만 보인다', () => {
    const [confirm, prepare, ship] = SCENARIOS.ship.steps;
    const [, cancel, refund] = SCENARIOS.cancel.steps;
    const render = (step: typeof confirm) =>
      renderToStaticMarkup(<StepAction step={step!} pending={false} onSend={noop} />);
    expect(render(confirm)).toContain('>주문 확인<');
    expect(render(prepare)).toContain('>상품 준비중으로 바꾸기<');
    const shipHtml = render(ship);
    expect(shipHtml).toContain('연습 택배 A');
    expect(shipHtml).not.toContain('CJ_LOGISTICS');
    expect(shipHtml).toContain('송장 번호 (연습용)');
    expect(shipHtml).toContain('>송장 등록<');
    expect(render(cancel)).toContain('취소 사유');
    expect(render(refund)).toContain('>환불 결과 보기<');
  });

  it('처리 중에는 disabled 가 아니라 aria-disabled 로 막고, 실패한 입력 칸은 오류 안내를 가리킨다', () => {
    const [confirm] = SCENARIOS.ship.steps;
    const [, cancel] = SCENARIOS.cancel.steps;
    const pending = renderToStaticMarkup(<StepAction step={confirm!} pending onSend={noop} />);
    expect(pending).toContain('aria-disabled="true"');
    expect(pending).not.toContain('disabled=""');
    const failed = renderToStaticMarkup(
      <StepAction step={cancel!} pending={false} errorId="err" onSend={noop} />,
    );
    expect(failed).toContain('aria-describedby="err"');
    expect(failed).toContain('aria-invalid="true"');
  });
});

/**
 * #365 완료 기준 — 연습 본문(샘플 버튼 · 연습 transport)은 실서비스에 요청을 하나도 보내지 않는다.
 * 콘솔 화면 틀(알림 · 투어 진행)은 운영 업무 데이터가 아닌 운영자 개인 UI 상태라 이 범위 밖이다.
 * 1) 실행: 네트워크로 나갈 수 있는 길(fetch · XMLHttpRequest · EventSource · WebSocket · sendBeacon · apiFetch)을 감시한 채
 *    화면의 샘플 버튼과 같은 길(`runPracticeAction` → `practiceReducer`)로 두 시나리오를 실패 입력까지 섞어 끝까지 돌린다 → 0건.
 * 2) 소스: 연습 폴더 파일은 연습 폴더 · 허용한 라이브러리만 들여오고 네트워크 API · BFF 경로를 쓰지 않는다.
 *    같은 규칙을 ESLint(`eslint.config.mjs` 의 practice override)가 편집 중에도 막는다.
 */
describe('연습 본문 실서비스 요청 0건', () => {
  const fetchSpy = vi.fn(() => Promise.reject(new Error('연습 화면이 fetch 를 불렀다')));
  const xhrSpy = vi.fn();
  const eventSourceSpy = vi.fn();
  const socketSpy = vi.fn();
  const beaconSpy = vi.fn(() => false);

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchSpy);
    vi.stubGlobal('XMLHttpRequest', xhrSpy);
    vi.stubGlobal('EventSource', eventSourceSpy);
    vi.stubGlobal('WebSocket', socketSpy);
    vi.stubGlobal('navigator', { sendBeacon: beaconSpy });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  const play: Record<ScenarioId, PracticeAction[]> = {
    ship: [
      { type: 'prepare' }, // 순서 틀림 — 실패
      { type: 'confirm' },
      { type: 'prepare' },
      { type: 'ship', carrier: '', trackingNumber: '' }, // 입력 틀림 — 실패
      { type: 'ship', carrier: 'PRACTICE_A', trackingNumber: 'PRACTICE-1' },
    ],
    cancel: [
      { type: 'confirm' },
      { type: 'cancel', reason: '' }, // 사유 없음 — 실패
      { type: 'cancel', reason: '고객 요청' },
      { type: 'refund-result' },
    ],
  };

  it('두 시나리오를 샘플 버튼과 같은 길로 실패까지 섞어 끝까지 돌려도 네트워크 요청이 없다', async () => {
    const transport = createPracticeTransport();
    for (const scenario of Object.keys(play) as ScenarioId[]) {
      let state = practiceReducer(INITIAL_PRACTICE, {
        type: 'start',
        scenario,
        order: transport.order(SCENARIOS[scenario].orderId)!,
      });
      for (const action of play[scenario]) {
        const event = await runPracticeAction(transport, state, action);
        if (event) state = practiceReducer(state, event);
      }
      expect(isFinished(state)).toBe(true);
    }
    // 샘플이 아닌(실제 모양의) 주문 번호도 실서비스로 넘기지 않는다
    expect((await transport.send('4611686018427387904', { type: 'confirm' })).ok).toBe(false);
    transport.reset();
    renderToStaticMarkup(<PracticeScreen transport={transport} />);

    expect(transport.order(SCENARIOS.ship.orderId)?.status).toBe('PAID');
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(xhrSpy).not.toHaveBeenCalled();
    expect(eventSourceSpy).not.toHaveBeenCalled();
    expect(socketSpy).not.toHaveBeenCalled();
    expect(beaconSpy).not.toHaveBeenCalled();
    expect(apiFetch).not.toHaveBeenCalled();
  });

  it('연습 폴더 소스는 허용한 모듈만 들여오고 네트워크 API · BFF 경로를 쓰지 않는다', () => {
    const src = fileURLToPath(new URL('../../', import.meta.url));
    const dirs = ['components/practice', 'lib/practice', 'app/(console)/practice'];
    const files = dirs.flatMap((dir) =>
      readdirSync(join(src, dir), { recursive: true, encoding: 'utf8' })
        .filter((name) => /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name))
        .map((name) => join(src, dir, name)),
    );
    expect(files.length).toBeGreaterThanOrEqual(6);
    const allowed = new Set(['react', 'next', 'next/link', '@cf/ui', '@cf/api-client']);
    for (const file of files) {
      const code = readFileSync(file, 'utf8');
      expect(code, file).not.toMatch(
        /apiFetch|serverFetch|\bfetch\b|XMLHttpRequest|EventSource|WebSocket|sendBeacon|use server|\/api\//,
      );
      // 들여오는 모든 모듈(import · export from · 동적 import)이 허용 목록 · 연습 폴더 안이어야 한다
      const specifiers = [
        ...code.matchAll(/\bfrom\s+['"]([^'"]+)['"]/g),
        ...code.matchAll(/\bimport\s*\(\s*['"`]([^'"`]+)/g),
        ...code.matchAll(/^\s*import\s+['"]([^'"]+)['"]/gm),
      ].map((match) => match[1]!);
      expect(specifiers.length, file).toBeGreaterThan(0);
      for (const specifier of specifiers) {
        const ok =
          allowed.has(specifier) ||
          specifier.startsWith('@/lib/practice/') ||
          specifier.startsWith('@/components/practice/') ||
          specifier.startsWith('./');
        expect(ok, `${file} → ${specifier}`).toBe(true);
      }
      // @cf/api-client 에서는 금액 표시만 가져온다(이름 목록이 없는 import * 도 막는다)
      expect(code, file).not.toMatch(/import\s+\*\s+as\s+\w+\s+from\s+['"]@cf\/api-client/);
      for (const [, names] of code.matchAll(/\{([^}]*)\}\s+from\s+['"]@cf\/api-client['"]/g)) {
        for (const name of names!.split(',').map((part) => part.replace(/\btype\b/, '').trim())) {
          if (name) expect(['formatMoney', 'Money'], file).toContain(name);
        }
      }
    }
  });
});
