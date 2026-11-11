import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { DEFAULT_QUERY } from '@/lib/claim/query';
import type { ClaimDetail, ClaimListItem } from '@/lib/claim/types';
import { ClaimDetailView } from './ClaimDetailView';
import { ClaimFilters } from './ClaimFilters';
import { ClaimList } from './ClaimList';
import { ClaimRecovery } from './ClaimRecovery';
import { ClaimTable } from './ClaimTable';
import { OrderClaimsBody } from './OrderClaims';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));

const claim = (overrides: Partial<ClaimListItem> = {}): ClaimListItem => ({
  claimId: '4611686018427387910',
  orderId: '4611686018427387904',
  type: { code: 'RETURN', name: '반품', description: '반품 설명' },
  status: { code: 'REQUESTED', name: '접수', description: '접수 설명' },
  expectedRefund: { amount: 93000, currency: 'KRW' },
  requestedAt: '2026-11-01T01:00:00Z',
  ...overrides,
});

const detail = (overrides: Partial<ClaimDetail> = {}): ClaimDetail => ({
  ...claim(),
  items: [
    {
      orderItemId: '4611686018427387920',
      quantity: 2,
      reason: { code: 'DEFECTIVE', name: '상품 불량', description: '불량 설명' },
    },
  ],
  recovery: {
    situation: { code: 'NO_ACTION', name: '처리할 일 없음', description: '없음 설명' },
    nextAutomaticAttemptAt: null,
    retryable: false,
    version: 0,
  },
  ...overrides,
});

const page = (contents: ClaimListItem[], totalElements = contents.length) => ({
  contents,
  page: 0,
  size: 20,
  totalElements,
  totalPages: Math.ceil(totalElements / 20),
});

describe('ClaimTable', () => {
  it('클레임 · 주문 상세 링크, 서버 유형 · 상태 name, formatMoney 예상 환불액을 보인다', () => {
    const html = renderToStaticMarkup(<ClaimTable claims={[claim()]} />);
    expect(html).toContain('href="/claims/4611686018427387910"');
    expect(html).toContain('href="/orders/4611686018427387904"');
    expect(html).toContain('>반품<');
    expect(html).toContain('title="접수 설명"');
    expect(html).toContain('93,000원');
  });

  it('예상 환불액 null 은 0원이 아니라 미산정이다 · USD 는 서버 값 그대로', () => {
    const html = renderToStaticMarkup(
      <ClaimTable
        claims={[
          claim({ expectedRefund: null }),
          claim({ claimId: '2', expectedRefund: { amount: 8950, currency: 'USD' } }),
        ]}
      />,
    );
    expect(html).toContain('미산정');
    expect(html).not.toContain('0원');
    expect(html).toContain('$89.50');
  });
});

describe('ClaimDetailView', () => {
  it('주문 링크 · 항목별 수량 · 사유 name 을 보이고 처리 버튼은 두지 않는다', () => {
    const html = renderToStaticMarkup(<ClaimDetailView claim={detail()} />);
    expect(html).toContain('href="/orders/4611686018427387904"');
    expect(html).toContain('4611686018427387920');
    expect(html).toContain('>2<');
    expect(html).toContain('>상품 불량<');
    expect(html).toContain('93,000원');
    expect(html).not.toContain('<button');
  });

  it('투어(#228 claim-detail-v1)의 필수 앵커 — 접수 내용 · 접수 항목 — 를 단다', () => {
    const html = renderToStaticMarkup(<ClaimDetailView claim={detail()} />);
    expect(html).toContain('data-tour="claim-summary"');
    expect(html).toContain('data-tour="claim-items"');
  });
});

describe('ClaimList', () => {
  it('불러오기 전에는 투어(#228 claims-v1)의 조건 앵커만 있다 — 표 단계는 선택이다', () => {
    const html = renderToStaticMarkup(<ClaimList query={DEFAULT_QUERY} />);
    expect([...html.matchAll(/data-tour="([^"]+)"/g)].map((match) => match[1])).toEqual([
      'claim-filters',
    ]);
  });
});

describe('ClaimFilters', () => {
  it('유형 · 상태는 서버가 받는 값만 고른다', () => {
    const html = renderToStaticMarkup(<ClaimFilters query={DEFAULT_QUERY} />);
    expect(html).toContain('value="RETURN"');
    expect(html).toContain('value="REQUESTED"');
    expect(html).not.toContain('조건 초기화');
  });

  it('조건이 있으면 초기화 링크를 둔다', () => {
    const html = renderToStaticMarkup(<ClaimFilters query={{ ...DEFAULT_QUERY, orderId: '7' }} />);
    expect(html).toContain('조건 초기화');
  });
});

describe('OrderClaimsBody', () => {
  it('없으면 없다고, 있으면 클레임 상세 링크로 보인다', () => {
    expect(
      renderToStaticMarkup(
        <OrderClaimsBody orderId="1" loaded={{ orderId: '1', kind: 'ok', data: page([]) }} />,
      ),
    ).toContain('접수된 클레임이 없습니다.');
    const html = renderToStaticMarkup(
      <OrderClaimsBody orderId="1" loaded={{ orderId: '1', kind: 'ok', data: page([claim()]) }} />,
    );
    expect(html).toContain('href="/claims/4611686018427387910"');
    expect(html).not.toContain('모두 보기');
  });

  it('한 페이지보다 많으면 목록으로 가는 링크를 두고, 실패하면 이 칸만 안내한다', () => {
    const html = renderToStaticMarkup(
      <OrderClaimsBody
        orderId="1"
        loaded={{ orderId: '1', kind: 'ok', data: page([claim()], 21) }}
      />,
    );
    expect(html).toContain('href="/claims?orderId=1"');
    expect(html).toContain('21건 모두 보기');
    expect(
      renderToStaticMarkup(
        <OrderClaimsBody orderId="1" loaded={{ orderId: '1', kind: 'failed' }} />,
      ),
    ).toContain('불러오지 못했습니다');
  });
});

describe('ClaimRecovery', () => {
  const cancellation = (recovery: Partial<ClaimDetail['recovery']>) =>
    detail({
      type: { code: 'CANCELLATION', name: '결제 후 취소', description: '' },
      status: { code: 'REFUND_FAILED', name: '환불 실패', description: '' },
      requestedAt: '2026-11-01T00:00:00Z',
      recovery: {
        situation: { code: 'REFUND_UNCONFIRMED', name: '환불 미확정', description: '확인 필요' },
        nextAutomaticAttemptAt: null,
        retryable: true,
        version: 3,
        ...recovery,
      },
    });
  const render = (claim: ClaimDetail) =>
    renderToStaticMarkup(
      <ClaimRecovery
        claim={claim}
        onUpdated={vi.fn()}
        onReload={vi.fn()}
        now={Date.parse('2026-11-10T00:00:00Z')}
      />,
    );

  it('반품 접수에는 그리지 않는다', () => {
    expect(render(detail())).toBe('');
  });

  it('처리 상황 name · description, 결제 취소 기한 · 남은 시간, 다시 시도 버튼을 보인다', () => {
    const html = render(cancellation({}));
    expect(html).toContain('환불 미확정');
    expect(html).toContain('확인 필요');
    expect(html).toContain('결제 취소 기한');
    expect(html).toContain('5일 남음');
    expect(html).toContain('할 수 있습니다');
    expect(html).toContain('>다시 시도</button>');
    // 투어(#228 claim-detail-v1)의 선택 앵커 — 처리 상황 칸과 다시 시도 버튼
    expect(html).toContain('data-tour="claim-recovery"');
    expect(html).toMatch(/<button[^>]*data-tour="claim-retry"[^>]*>다시 시도<\/button>/);
  });

  it('다시 시도할 수 없으면 버튼을 두지 않고, 자동 처리 중이면 다음 확인 시각을 보인다', () => {
    const rejected = render(
      cancellation({
        situation: { code: 'REFUND_REJECTED', name: '환불 거절', description: '' },
        retryable: false,
      }),
    );
    expect(rejected).toContain('할 수 없습니다');
    expect(rejected).not.toContain('>다시 시도</button>');
    expect(rejected).not.toContain('결제 취소 기한');
    // 버튼이 없으면 투어의 다시 시도 단계도 빠진다(선택 단계)
    expect(rejected).toContain('data-tour="claim-recovery"');
    expect(rejected).not.toContain('data-tour="claim-retry"');

    const auto = render(
      cancellation({
        situation: { code: 'AUTO_PROCESSING', name: '자동 처리 중', description: '' },
        nextAutomaticAttemptAt: '2026-11-10T01:00:00Z',
        retryable: false,
      }),
    );
    expect(auto).toContain('다음 자동 확인');
  });
});
