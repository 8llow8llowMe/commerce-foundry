import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { DEFAULT_QUERY } from '@/lib/claim/query';
import type { ClaimDetail, ClaimListItem } from '@/lib/claim/types';
import { ClaimDecisions } from './ClaimDecisions';
import { ClaimDetailView } from './ClaimDetailView';
import { ClaimFilters } from './ClaimFilters';
import { ClaimList } from './ClaimList';
import { ClaimQuickFilters, PAYMENT_UNKNOWN_HREF } from './ClaimQuickFilters';
import { ClaimRecovery } from './ClaimRecovery';
import { ClaimReturnDecision } from './ClaimReturnDecision';
import { ClaimTable, formatClaimTime } from './ClaimTable';
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
    refundConfirmable: false,
    completable: false,
    lastDecision: null,
    version: 0,
  },
  returnApproval: null,
  returnDecision: null,
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

  it('투어(#228 claim-detail-v2)의 필수 앵커 — 접수 내용 · 접수 항목 — 를 단다', () => {
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
        refundConfirmable: false,
        completable: false,
        lastDecision: null,
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
    // 투어(#228 claim-detail-v2)의 선택 앵커 — 처리 상황 칸과 다시 시도 버튼
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

describe('ClaimDecisions · 마지막 운영자 결정', () => {
  const cancellation = (recovery: Partial<ClaimDetail['recovery']>) =>
    detail({
      type: { code: 'CANCELLATION', name: '결제 후 취소', description: '' },
      status: { code: 'REFUND_FAILED', name: '환불 실패', description: '' },
      recovery: {
        situation: { code: 'REFUND_REJECTED', name: '환불 거절', description: '' },
        nextAutomaticAttemptAt: null,
        retryable: false,
        refundConfirmable: false,
        completable: false,
        lastDecision: null,
        version: 4,
        ...recovery,
      },
    });
  const decisions = (claim: ClaimDetail, canSettle: boolean | null) =>
    renderToStaticMarkup(
      <ClaimDecisions claim={claim} canSettle={canSettle} onUpdated={vi.fn()} onReload={vi.fn()} />,
    );

  it('대표 관리자에게 서버 플래그가 true 인 버튼만 보인다', () => {
    const confirmable = decisions(cancellation({ refundConfirmable: true }), true);
    expect(confirmable).toContain('>환불 완료로 확정</button>');
    expect(confirmable).not.toContain('재고 복원 없이 종결');
    expect(confirmable).toContain('data-tour="claim-decisions"');

    const completable = decisions(
      cancellation({
        situation: { code: 'RESTORATION_REJECTED', name: '복원 거절', description: '' },
        completable: true,
      }),
      true,
    );
    expect(completable).toContain('>재고 복원 없이 종결</button>');
    expect(completable).not.toContain('환불 완료로 확정');
  });

  it('플래그가 모두 false 면 그리지 않는다', () => {
    expect(decisions(cancellation({}), true)).toBe('');
    expect(decisions(cancellation({}), false)).toBe('');
  });

  it('대표가 아니면(MANAGER · STAFF) 버튼 대신 대표 관리자만 할 수 있다고 안내한다', () => {
    const html = decisions(cancellation({ refundConfirmable: true, completable: true }), false);
    expect(html).not.toContain('</button>');
    expect(html).toContain('대표 관리자만 할 수 있습니다');
  });

  it('역할을 모르면(null) 숨기지 않고 서버 403 안내에 맡긴다', () => {
    expect(decisions(cancellation({ refundConfirmable: true }), null)).toContain(
      '>환불 완료로 확정</button>',
    );
  });

  it('유형을 보지 않고 서버 플래그를 따른다 — 플래그가 없는 반품은 그리지 않고, 플래그가 오면 반품도 보인다', () => {
    expect(decisions(detail(), true)).toBe('');
    const flagged = decisions(
      detail({
        recovery: { ...detail().recovery, refundConfirmable: true, completable: true },
      }),
      true,
    );
    expect(flagged).toContain('>환불 완료로 확정</button>');
    expect(flagged).toContain('>재고 복원 없이 종결</button>');
  });

  it('처리 상황 칸 안에 결정 버튼과 마지막 운영자 결정(사유 · 시각)을 보인다', () => {
    const html = renderToStaticMarkup(
      <ClaimRecovery
        claim={cancellation({
          refundConfirmable: true,
          lastDecision: {
            reason: '결제 관리 화면에서 취소 완료 확인',
            decidedAt: '2026-11-10T03:00:00Z',
          },
        })}
        canSettle
        onUpdated={vi.fn()}
        onReload={vi.fn()}
      />,
    );
    expect(html).toContain('마지막 운영자 결정');
    expect(html).toContain('결제 관리 화면에서 취소 완료 확인');
    expect(html).toContain(formatClaimTime('2026-11-10T03:00:00Z'));
    expect(html).toContain('>환불 완료로 확정</button>');
  });

  it('마지막 결정이 없으면 그 줄을 두지 않는다', () => {
    const html = renderToStaticMarkup(
      <ClaimRecovery claim={cancellation({})} onUpdated={vi.fn()} onReload={vi.fn()} />,
    );
    expect(html).not.toContain('마지막 운영자 결정');
  });
});

describe('ClaimQuickFilters', () => {
  it('확인이 필요한 건 바로가기를 두고, 지금 조건과 같으면 선택 표시한다', () => {
    const html = renderToStaticMarkup(
      <ClaimQuickFilters
        query={{ ...DEFAULT_QUERY, type: 'RETURN', status: 'REQUESTED', page: 2 }}
        showPayments
      />,
    );
    expect(html).toContain('확인이 필요한 건');
    expect(html).toMatch(
      /aria-current="page"[^>]*href="\/claims\?type=RETURN&amp;status=REQUESTED"/,
    );
    expect(html).toContain('href="/claims?status=REFUND_FAILED"');
    expect(html).toContain('href="/claims?status=RESTORATION_FAILED"');
    expect(html.match(/aria-current/g)).toHaveLength(1);
    expect(html).toContain(`href="${PAYMENT_UNKNOWN_HREF}"`);
    expect(PAYMENT_UNKNOWN_HREF).toBe('/payments?status=UNKNOWN');
  });

  it('결제 확인 권한이 없으면 결제 확인 중 바로가기를 두지 않는다 · 조건이 다르면 선택 표시가 없다', () => {
    const html = renderToStaticMarkup(
      <ClaimQuickFilters
        query={{ ...DEFAULT_QUERY, status: 'REFUND_FAILED', orderId: '7' }}
        showPayments={false}
      />,
    );
    expect(html).not.toContain('결제 확인 중');
    expect(html).not.toContain('aria-current');
  });

  it('목록 화면 위에 그린다', () => {
    const html = renderToStaticMarkup(<ClaimList query={DEFAULT_QUERY} showPayments={false} />);
    expect(html).toContain('반품 승인 대기');
    expect(html).not.toContain('결제 확인 중');
  });
});

describe('ClaimReturnDecision', () => {
  const fault = (code: string, name: string) => ({ code, name, description: '' });
  const decision = (overrides: Partial<NonNullable<ClaimDetail['returnDecision']>> = {}) => ({
    approvable: true,
    rejectable: true,
    reason: null,
    defaultFault: fault('SELLER', '판매자 귀책'),
    refundByFault: [
      {
        fault: fault('CUSTOMER', '고객 귀책'),
        amount: { amount: 90000, currency: 'KRW' as const },
      },
      {
        fault: fault('SELLER', '판매자 귀책'),
        amount: { amount: 96000, currency: 'KRW' as const },
      },
    ],
    ...overrides,
  });
  const render = (claim: ClaimDetail, canManage: boolean | null) =>
    renderToStaticMarkup(
      <ClaimReturnDecision
        claim={claim}
        canManage={canManage}
        onUpdated={vi.fn()}
        onReload={vi.fn()}
      />,
    );

  it('취소 클레임 · 미리보기 없는 접수 반품에는 그리지 않는다', () => {
    expect(
      render(
        detail({
          type: { code: 'CANCELLATION', name: '결제 후 취소', description: '' },
          returnDecision: decision(),
        }),
        true,
      ),
    ).toBe('');
    expect(render(detail(), true)).toBe('');
  });

  it('승인할 수 있으면 귀책(기본 표시) · 재고 선택 · 차감액과 환불 예정액 내역, 승인 · 거절 버튼을 보인다', () => {
    const html = render(detail({ returnDecision: decision() }), true);
    expect(html).toContain('data-tour="claim-return-decision"');
    expect(html).toContain('판매자 귀책 (기본)');
    expect(html).toContain('정책 환불액 96,000원');
    // 기본 귀책이 골라져 있고 재고는 아무것도 골라져 있지 않다
    expect(html).toMatch(/checked="" value="SELLER"/);
    expect(html).not.toMatch(/checked="" value="CUSTOMER"/);
    expect(html).toContain('재고로 되돌림');
    expect(html).toContain('되돌리지 않음');
    expect(html.match(/name="[^"]*-restock"[^>]*checked/g)).toBeNull();
    expect(html).toContain('value="0"');
    expect(html).toContain('환불 예정액');
    expect(html).toContain('− 0원');
    // 재고를 고르기 전이라 승인은 aria-disabled
    expect(html).toMatch(/aria-disabled="true"[^>]*>반품 승인<\/button>/);
    expect(html).toContain('>반품 거절</button>');
    expect(html).not.toContain('ORDER_');
  });

  it('정책 환불액이 0 이하인 귀책은 승인할 수 없다고 바로 알린다', () => {
    const html = render(
      detail({
        returnDecision: decision({
          refundByFault: [
            { fault: fault('SELLER', '판매자 귀책'), amount: { amount: -2000, currency: 'KRW' } },
          ],
        }),
      }),
      true,
    );
    expect(html).toContain('이 귀책으로는 승인할 수 없습니다.');
    expect(html).toContain('정책 환불액이 0원 이하라 승인할 수 없습니다');
  });

  it('승인할 수 없으면 이유 name · description 을 그대로 보이고 거절만 둔다', () => {
    const html = render(
      detail({
        returnDecision: decision({
          approvable: false,
          reason: {
            code: 'REFUND_NOT_OPEN',
            name: '자동 환불 꺼짐',
            description: '자동 환불 처리가 꺼져 있습니다.',
          },
        }),
      }),
      true,
    );
    expect(html).toContain('지금은 승인할 수 없습니다 — 자동 환불 꺼짐');
    expect(html).toContain('자동 환불 처리가 꺼져 있습니다.');
    expect(html).toContain('정책 환불액 (판매자 귀책)');
    expect(html).not.toContain('>반품 승인</button>');
    expect(html).toContain('>반품 거절</button>');
  });

  it('주문 관리 권한이 없으면(STAFF) 버튼 대신 안내한다 · 역할을 모르면 숨기지 않는다', () => {
    const staff = render(detail({ returnDecision: decision() }), false);
    expect(staff).toContain('주문 관리 권한이 있는 운영자');
    expect(staff).not.toContain('반품 승인</button>');
    expect(staff).not.toContain('반품 거절</button>');

    const unknown = render(detail({ returnDecision: decision() }), null);
    expect(unknown).toContain('반품 승인</button>');
  });

  it('승인된 반품은 승인 내용을 읽기 전용으로 보인다 — 처리 상황은 처리 상황 칸이 맡는다', () => {
    const html = render(
      detail({
        status: { code: 'REFUND_FAILED', name: '환불 실패', description: '' },
        expectedRefund: { amount: 93000, currency: 'KRW' },
        recovery: {
          situation: { code: 'REFUND_REJECTED', name: '환불 거절', description: '결제사 거절' },
          nextAutomaticAttemptAt: null,
          retryable: false,
          refundConfirmable: true,
          completable: false,
          lastDecision: null,
          version: 6,
        },
        returnApproval: {
          fault: fault('CUSTOMER', '고객 귀책'),
          restock: false,
          deduction: { amount: 3000, currency: 'KRW' },
          approvedAt: '2026-11-02T01:00:00Z',
        },
      }),
      true,
    );
    expect(html).toContain('반품 승인 내용');
    expect(html).toContain('고객 귀책');
    expect(html).toContain('되돌리지 않음');
    expect(html).toContain('3,000원');
    expect(html).toContain('93,000원');
    expect(html).toContain(formatClaimTime('2026-11-02T01:00:00Z'));
    expect(html).not.toContain('결제사 거절');
    expect(html).not.toContain('</button>');
  });

  it('거절된 반품은 거절 사유 · 시각을 보인다', () => {
    const html = render(
      detail({
        status: { code: 'REJECTED', name: '거절', description: '' },
        recovery: {
          situation: { code: 'NO_ACTION', name: '처리할 일 없음', description: '' },
          nextAutomaticAttemptAt: null,
          retryable: false,
          refundConfirmable: false,
          completable: false,
          lastDecision: { reason: '착용 흔적', decidedAt: '2026-11-03T01:00:00Z' },
          version: 7,
        },
      }),
      true,
    );
    expect(html).toContain('반품 거절 내용');
    expect(html).toContain('착용 흔적');
  });
});

describe('ClaimRecovery · 승인된 반품(#17 B2b)', () => {
  const approvedReturn = (recovery: Partial<ClaimDetail['recovery']>) =>
    detail({
      status: { code: 'REFUND_FAILED', name: '환불 실패', description: '' },
      recovery: {
        situation: { code: 'REFUND_UNCONFIRMED', name: '환불 미확정', description: '확인 필요' },
        nextAutomaticAttemptAt: null,
        retryable: false,
        refundConfirmable: false,
        completable: false,
        lastDecision: null,
        version: 8,
        ...recovery,
      },
      returnApproval: {
        fault: { code: 'SELLER', name: '판매자 귀책', description: '' },
        restock: true,
        deduction: { amount: 0, currency: 'KRW' },
        approvedAt: '2026-11-05T00:00:00Z',
      },
    });
  const render = (claim: ClaimDetail, canSettle: boolean | null = true) =>
    renderToStaticMarkup(
      <ClaimRecovery
        claim={claim}
        canSettle={canSettle}
        onUpdated={vi.fn()}
        onReload={vi.fn()}
        now={Date.parse('2026-11-10T00:00:00Z')}
      />,
    );

  it('환불 실패 반품은 서버 retryable 대로 다시 시도 버튼과 승인 + 14일 기한을 보인다', () => {
    const html = render(approvedReturn({ retryable: true }));
    expect(html).toContain('data-tour="claim-recovery"');
    expect(html).toContain('환불 미확정');
    expect(html).toContain('(승인 + 14일)');
    expect(html).toContain(formatClaimTime('2026-11-19T00:00:00Z'));
    expect(html).toContain('9일 남음');
    expect(html).toContain('>다시 시도</button>');

    expect(render(approvedReturn({ retryable: false }))).not.toContain('>다시 시도</button>');
  });

  it('반품도 확정 · 종결 버튼은 서버 플래그대로, 대표 관리자에게만 보인다', () => {
    const confirmable = render(
      approvedReturn({
        situation: { code: 'REFUND_REJECTED', name: '환불 거절', description: '' },
        refundConfirmable: true,
      }),
    );
    expect(confirmable).toContain('>환불 완료로 확정</button>');
    expect(confirmable).not.toContain('재고 복원 없이 종결');

    const completable = approvedReturn({
      situation: { code: 'RESTORATION_REJECTED', name: '복원 거절', description: '' },
      completable: true,
    });
    expect(render(completable)).toContain('>재고 복원 없이 종결</button>');
    const manager = render(completable, false);
    expect(manager).not.toContain('</button>');
    expect(manager).toContain('대표 관리자만');
  });

  it('접수 · 거절 반품은 서버 플래그가 모두 false 면 처리 상황 칸을 그리지 않는다(반품 결정 칸과 겹치지 않는다)', () => {
    expect(render(detail())).toBe('');
    expect(render(detail({ status: { code: 'REJECTED', name: '거절', description: '' } }))).toBe(
      '',
    );
  });
});

describe('ClaimRecovery · 다시 시도 권한', () => {
  const retryable = detail({
    type: { code: 'CANCELLATION', name: '결제 후 취소', description: '' },
    status: { code: 'REFUND_FAILED', name: '환불 실패', description: '' },
    recovery: {
      situation: { code: 'RESTORATION_UNCONFIRMED', name: '복원 미확정', description: '' },
      nextAutomaticAttemptAt: null,
      retryable: true,
      refundConfirmable: false,
      completable: false,
      lastDecision: null,
      version: 2,
    },
  });
  const render = (canManageOrders: boolean | null) =>
    renderToStaticMarkup(
      <ClaimRecovery
        claim={retryable}
        canManageOrders={canManageOrders}
        onUpdated={vi.fn()}
        onReload={vi.fn()}
      />,
    );

  it('주문 관리 권한이 없으면(STAFF) 다시 시도 버튼 대신 안내한다 · 역할을 모르면 숨기지 않는다', () => {
    const staff = render(false);
    expect(staff).not.toContain('>다시 시도</button>');
    expect(staff).toContain('주문 관리 권한이 있는 운영자');
    expect(render(null)).toContain('>다시 시도</button>');
    expect(render(true)).toContain('>다시 시도</button>');
  });
});

describe('ClaimReturnDecision · 표기', () => {
  const fault = (code: string, name: string, description = '') => ({ code, name, description });

  it('차감액 칸 이름은 통화 코드 대신 단위 말로, 귀책 설명은 툴팁이 아니라 글자로 보인다', () => {
    const pending = renderToStaticMarkup(
      <ClaimReturnDecision
        claim={detail({
          returnDecision: {
            approvable: true,
            rejectable: true,
            reason: null,
            defaultFault: fault('SELLER', '판매자 귀책'),
            refundByFault: [
              {
                fault: fault('SELLER', '판매자 귀책', '상품 하자 · 오배송'),
                amount: { amount: 8950, currency: 'USD' },
              },
            ],
          },
        })}
        canManage
        onUpdated={vi.fn()}
        onReload={vi.fn()}
      />,
    );
    expect(pending).toContain('차감액 (달러, 센트까지)');
    expect(pending).not.toContain('(USD)');
    expect(pending).toContain('상품 하자 · 오배송');

    const approved = renderToStaticMarkup(
      <ClaimReturnDecision
        claim={detail({
          status: { code: 'COMPLETED', name: '완료', description: '' },
          returnApproval: {
            fault: fault('CUSTOMER', '고객 귀책', '단순 변심'),
            restock: true,
            deduction: { amount: 0, currency: 'KRW' },
            approvedAt: '2026-11-02T01:00:00Z',
          },
        })}
        canManage
        onUpdated={vi.fn()}
        onReload={vi.fn()}
      />,
    );
    expect(approved).toContain('>단순 변심<');
    expect(approved).not.toContain('title="단순 변심"');
  });
});
