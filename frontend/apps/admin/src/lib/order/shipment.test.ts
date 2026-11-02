import { ApiError } from '@cf/api-client';
import { describe, expect, it, vi } from 'vitest';
import { LOAD_FAILED, SESSION_EXPIRED_ON_SAVE } from '@/lib/api-errors';
import {
  addSelectedOrders,
  ADMIN_DELIVERIES_PATH,
  carrierOf,
  isOrderId,
  isRetryable,
  normalizeTrackingNumber,
  parseShipmentPaste,
  remainingDrafts,
  runShipmentRegistration,
  SHIPMENT_NOT_SENT_AFTER_FAILURE,
  SHIPMENT_UNKNOWN,
  shipmentNotice,
  shipmentNoticeWithoutReload,
  shipmentSummary,
  TRACKING_NUMBER_INVALID,
  validateDrafts,
  type ShipmentItem,
  type ShipmentRegisterResponse,
  type ShipmentRow,
} from './shipment';
import type { Call } from './status-change';
import { orderItem } from './test-fixtures';

const item = (orderId: string, trackingNumber = `T${orderId}`): ShipmentItem => ({
  orderId,
  carrier: 'CJ_LOGISTICS',
  trackingNumber,
});

/** 보낸 줄을 모두 등록으로 돌려주는 응답 */
const registered = (items: ShipmentItem[]): ShipmentRegisterResponse => ({
  registeredCount: items.length,
  alreadyRegisteredCount: 0,
  failedCount: 0,
  results: items.map((row) => ({
    orderId: row.orderId,
    deliveryId: `d${row.orderId}`,
    result: 'REGISTERED',
    errorCode: null,
    message: null,
  })),
});

const row = (overrides: Partial<ShipmentRow>): ShipmentRow => ({
  item: item('1'),
  result: 'REGISTERED',
  errorCode: null,
  message: null,
  ...overrides,
});

describe('normalizeTrackingNumber', () => {
  it('앞뒤 공백만 빼고 대소문자 · 하이픈은 그대로 둔다', () => {
    expect(normalizeTrackingNumber('  Ab-12 ')).toBe('Ab-12');
  });

  it('영문 · 숫자 · 하이픈 1~50자가 아니면 받지 않는다', () => {
    expect(normalizeTrackingNumber('')).toBeNull();
    expect(normalizeTrackingNumber('12 34')).toBeNull();
    expect(normalizeTrackingNumber('송장1')).toBeNull();
    expect(normalizeTrackingNumber('1'.repeat(50))).toBe('1'.repeat(50));
    expect(normalizeTrackingNumber('1'.repeat(51))).toBeNull();
  });
});

describe('carrierOf', () => {
  it('코드(대소문자 무관)와 화면 이름(공백 무관)을 읽는다', () => {
    expect(carrierOf('cj_logistics')).toBe('CJ_LOGISTICS');
    expect(carrierOf(' CJ 대한통운 ')).toBe('CJ_LOGISTICS');
    expect(carrierOf('fedex')).toBe('FEDEX');
    expect(carrierOf('모르는 택배')).toBeNull();
  });
});

describe('validateDrafts', () => {
  it('택배사 · 송장 번호 형식을 칸마다 알린다', () => {
    const result = validateDrafts([
      { orderId: '1', label: '1', carrier: '', trackingText: '12 34' },
      { orderId: '2', label: '2', carrier: 'HANJIN', trackingText: ' 99 ' },
    ]);
    expect(result).toEqual({
      ok: false,
      errors: {
        '1': { carrier: '택배사를 골라 주세요.', trackingNumber: TRACKING_NUMBER_INVALID },
      },
    });
  });

  it('문제없으면 다듬은 본문이다', () => {
    expect(
      validateDrafts([{ orderId: '2', label: '이름', carrier: 'HANJIN', trackingText: ' 99 ' }]),
    ).toEqual({ ok: true, items: [{ orderId: '2', carrier: 'HANJIN', trackingNumber: '99' }] });
  });
});

describe('parseShipmentPaste', () => {
  it('탭 · 쉼표 구분 줄을 읽고 머리글 · 빈 줄은 건너뛴다', () => {
    const text = '주문 아이디\t택배사\t송장 번호\n11\tCJ대한통운\t111\n\n12,"HANJIN",222\r\n';
    expect(parseShipmentPaste(text, new Set())).toEqual({
      drafts: [
        { orderId: '11', label: '11', carrier: 'CJ_LOGISTICS', trackingText: '111' },
        { orderId: '12', label: '12', carrier: 'HANJIN', trackingText: '222' },
      ],
      skipped: [],
    });
  });

  it('읽지 못한 주문 · 이미 넣은 주문 · 같은 주문의 두 번째 줄은 줄 번호와 함께 뺀다', () => {
    const text = '11\tCJ대한통운\t1\nabc1\tCJ대한통운\t2\n10\t\t3\n11\tLOTTE\t4\n13\t모르는 곳\t5';
    const result = parseShipmentPaste(text, new Set(['10']));
    expect(result.drafts.map((draft) => draft.orderId)).toEqual(['11', '13']);
    // 모르는 택배사는 비워 넣는다 — 화면에서 고른다
    expect(result.drafts[1]?.carrier).toBe('');
    expect(result.skipped).toEqual([
      { line: 2, reason: '주문 아이디(숫자)를 읽지 못했습니다.' },
      { line: 3, reason: '이미 넣은 주문입니다.' },
      { line: 4, reason: '이미 넣은 주문입니다.' },
    ]);
  });
});

describe('runShipmentRegistration', () => {
  it('20건씩 차례로 보내고 결과를 보낸 순서로 잇는다', async () => {
    const items = Array.from({ length: 25 }, (_, index) => item(String(index + 1)));
    const call = vi.fn(async (_path: string, init?: { json?: unknown }) => {
      const body = init?.json as { items: ShipmentItem[] };
      return registered(body.items);
    }) as unknown as Call;
    const run = await runShipmentRegistration(call, items);
    expect(call).toHaveBeenCalledTimes(2);
    expect(vi.mocked(call).mock.calls[0]?.[0]).toBe(ADMIN_DELIVERIES_PATH);
    expect(vi.mocked(call).mock.calls[0]?.[1]).toMatchObject({ method: 'POST' });
    expect(
      (vi.mocked(call).mock.calls[1]?.[1]?.json as { items: ShipmentItem[] }).items,
    ).toHaveLength(5);
    expect(run.denied).toBeNull();
    expect(run.rows.map((one) => one.item.orderId)).toEqual(items.map((one) => one.orderId));
    expect(run.rows.every((one) => one.result === 'REGISTERED')).toBe(true);
  });

  it('행별 실패는 서버 코드 · 문구 그대로다', async () => {
    const call = vi.fn().mockResolvedValue({
      registeredCount: 0,
      alreadyRegisteredCount: 1,
      failedCount: 1,
      results: [
        {
          orderId: '1',
          deliveryId: 'd1',
          result: 'ALREADY_REGISTERED',
          errorCode: null,
          message: null,
        },
        {
          orderId: '2',
          deliveryId: null,
          result: 'FAILED',
          errorCode: 'DELIVERY_015',
          message: '취소 요청 상태라 출고할 수 없습니다.',
        },
      ],
    } satisfies ShipmentRegisterResponse) as unknown as Call;
    const run = await runShipmentRegistration(call, [item('1'), item('2')]);
    expect(run.rows.map((one) => [one.result, one.errorCode, one.message])).toEqual([
      ['ALREADY_REGISTERED', null, null],
      ['FAILED', 'DELIVERY_015', '취소 요청 상태라 출고할 수 없습니다.'],
    ]);
  });

  it('결과를 모르는 실패면 그 요청 줄은 결과 모름, 뒤 요청은 보내지 않는다(자동 재시도 없음)', async () => {
    const items = Array.from({ length: 45 }, (_, index) => item(String(index + 1)));
    const call = vi
      .fn()
      .mockImplementationOnce(async (_path: string, init: { json: { items: ShipmentItem[] } }) =>
        registered(init.json.items),
      )
      .mockRejectedValueOnce(new ApiError('unknownOutcome', 504, null, null)) as unknown as Call;
    const run = await runShipmentRegistration(call, items);
    expect(call).toHaveBeenCalledTimes(2);
    expect(run.rows.slice(0, 20).every((one) => one.result === 'REGISTERED')).toBe(true);
    expect(run.rows.slice(20, 40).every((one) => one.result === 'UNKNOWN')).toBe(true);
    expect(run.rows[20]?.message).toBe(SHIPMENT_UNKNOWN);
    expect(run.rows.slice(40).every((one) => one.result === 'NOT_SENT')).toBe(true);
    expect(run.rows[40]?.message).toBe(SHIPMENT_NOT_SENT_AFTER_FAILURE);
  });

  it('연결 끊김도 결과 모름이다', async () => {
    const call = vi
      .fn()
      .mockRejectedValue(new ApiError('network', null, null, null)) as unknown as Call;
    const run = await runShipmentRegistration(call, [item('1')]);
    expect(run.rows[0]?.result).toBe('UNKNOWN');
  });

  it('400 · 429 · 503 은 처리하지 않은 요청이라 보내지 않음 + 서버 문구다', async () => {
    for (const cause of [
      new ApiError('server', 400, 'DELIVERY_143', '같은 주문(orderId)을 두 번 넣을 수 없습니다.'),
      new ApiError('rateLimited', 429, 'GATEWAY_006', '요청이 너무 많습니다.'),
      new ApiError('unavailable', 503, 'BFF_002', '잠시 후 다시 시도해 주세요.'),
    ]) {
      const call = vi.fn().mockRejectedValue(cause) as unknown as Call;
      const run = await runShipmentRegistration(call, [item('1')]);
      expect(run.rows[0]).toMatchObject({ result: 'NOT_SENT', message: cause.message });
    }
  });

  it('저장 중 401 은 입력을 남기는 세션 만료 문구, 403 은 권한 상태다', async () => {
    const expired = await runShipmentRegistration(
      vi.fn().mockRejectedValue(new ApiError('unauthorized', 401, null, null)) as unknown as Call,
      [item('1')],
    );
    expect(expired.denied).toBeNull();
    expect(expired.rows[0]).toMatchObject({ result: 'NOT_SENT', message: SESSION_EXPIRED_ON_SAVE });

    const forbidden = await runShipmentRegistration(
      vi.fn().mockRejectedValue(new ApiError('forbidden', 403, null, null)) as unknown as Call,
      [item('1')],
    );
    expect(forbidden.denied).toBe('forbidden');
  });

  it('응답에 빠진 줄은 결과 모름이다', async () => {
    const call = vi.fn().mockResolvedValue(registered([item('1')])) as unknown as Call;
    const run = await runShipmentRegistration(call, [item('1'), item('2')]);
    expect(run.rows.map((one) => one.result)).toEqual(['REGISTERED', 'UNKNOWN']);
  });
});

describe('isRetryable', () => {
  it('결과 모름 · 보내지 않음 · 017 · 018 · 020 · 021 · 022 만 다시 보낸다', () => {
    expect(isRetryable(row({ result: 'UNKNOWN' }))).toBe(true);
    expect(isRetryable(row({ result: 'NOT_SENT' }))).toBe(true);
    for (const code of [
      'DELIVERY_017',
      'DELIVERY_018',
      'DELIVERY_020',
      'DELIVERY_021',
      'DELIVERY_022',
    ])
      expect(isRetryable(row({ result: 'FAILED', errorCode: code }))).toBe(true);
    for (const code of ['DELIVERY_014', 'DELIVERY_015', 'DELIVERY_016', 'DELIVERY_019'])
      expect(isRetryable(row({ result: 'FAILED', errorCode: code }))).toBe(false);
    expect(isRetryable(row({ result: 'REGISTERED' }))).toBe(false);
  });
});

describe('shipmentSummary', () => {
  it('모두 출고 · 일부 · 하나도 못 함을 가른다', () => {
    expect(shipmentSummary([row({}), row({ result: 'ALREADY_REGISTERED' })])).toBe(
      '모두 출고했습니다 — 등록 1건 · 이미 같은 송장 1건 · 실패 0건',
    );
    expect(shipmentSummary([row({}), row({ result: 'UNKNOWN' })])).toBe(
      '일부 주문을 출고하지 못했습니다 — 등록 1건 · 실패 0건 · 결과 모름 1건',
    );
    expect(shipmentSummary([row({ result: 'FAILED' }), row({ result: 'NOT_SENT' })])).toBe(
      '출고하지 못했습니다 — 등록 0건 · 실패 1건 · 보내지 않음 1건',
    );
  });
});

describe('shipmentNotice', () => {
  const shipping = { code: 'SHIPPING', name: '배송 중' };
  const preparing = { code: 'PREPARING', name: '상품 준비중' };

  it('등록 뒤 다시 읽은 주문이 배송 중이면 성공이다', () => {
    expect(shipmentNotice(row({}), shipping)).toEqual({
      tone: 'ok',
      message: '송장(CJ대한통운 T1)을 등록해 배송 중으로 바꿨습니다.',
    });
    expect(shipmentNotice(row({ result: 'ALREADY_REGISTERED' }), shipping).tone).toBe('ok');
  });

  it('등록됐어도 다시 읽은 상태가 배송 중이 아니면 경고다', () => {
    expect(shipmentNotice(row({}), preparing)).toMatchObject({ tone: 'warn' });
  });

  it('결과 모름이어도 다시 읽은 주문이 배송 중이면 반영된 것이다', () => {
    expect(
      shipmentNotice(row({ result: 'UNKNOWN', message: SHIPMENT_UNKNOWN }), shipping).tone,
    ).toBe('ok');
    expect(
      shipmentNotice(row({ result: 'UNKNOWN', message: SHIPMENT_UNKNOWN }), preparing),
    ).toEqual({
      tone: 'warn',
      message: SHIPMENT_UNKNOWN,
    });
  });

  it('행별 실패는 서버 문구다', () => {
    expect(
      shipmentNotice(
        row({
          result: 'FAILED',
          errorCode: 'DELIVERY_019',
          message: '이미 다른 송장으로 출고된 주문입니다.',
        }),
        shipping,
      ),
    ).toEqual({ tone: 'warn', message: '이미 다른 송장으로 출고된 주문입니다.' });
  });
});

describe('addSelectedOrders · remainingDrafts', () => {
  it('상품 준비중 주문만 기본 택배사로 넣고, 이미 넣은 주문은 다시 넣지 않는다', () => {
    const preparing = { code: 'PREPARING', name: '상품 준비중', description: '' };
    const result = addSelectedOrders(
      [{ orderId: '1', label: '1', carrier: '', trackingText: 'x' }],
      [
        orderItem({ orderId: '1', status: preparing }),
        orderItem({ orderId: '2', orderName: '둘째', status: preparing }),
        orderItem({ orderId: '3' }),
      ],
      'LOTTE',
    );
    expect(result.notPreparing).toBe(1);
    expect(result.drafts).toEqual([
      { orderId: '1', label: '1', carrier: '', trackingText: 'x' },
      { orderId: '2', label: '둘째', carrier: 'LOTTE', trackingText: '' },
    ]);
  });

  it('보낸 뒤에는 다시 보낼 만한 줄만 입력값 그대로 남긴다', () => {
    const drafts = ['1', '2', '3', '4'].map((orderId) => ({
      orderId,
      label: orderId,
      carrier: 'CJ_LOGISTICS' as const,
      trackingText: `T${orderId}`,
    }));
    const rows = [
      row({ item: item('1') }),
      row({ item: item('2'), result: 'FAILED', errorCode: 'DELIVERY_018' }),
      row({ item: item('3'), result: 'FAILED', errorCode: 'DELIVERY_015' }),
    ];
    expect(remainingDrafts(drafts, rows).map((draft) => draft.orderId)).toEqual(['2', '4']);
  });
});

describe('isOrderId', () => {
  it('long 범위 안의 양수 숫자 문자열만 받는다', () => {
    expect(isOrderId('4611686018427387904')).toBe(true);
    expect(isOrderId('9223372036854775807')).toBe(true);
    expect(isOrderId('9223372036854775808')).toBe(false);
    expect(isOrderId('9999999999999999999')).toBe(false);
    expect(isOrderId('0')).toBe(false);
    expect(isOrderId('4.61169E+18')).toBe(false);
  });

  it('붙여넣기에서 범위를 넘는 아이디는 줄 번호와 함께 뺀다', () => {
    expect(
      parseShipmentPaste('1\tLOTTE\t1\n9999999999999999999\tLOTTE\t2', new Set()).skipped,
    ).toEqual([{ line: 2, reason: '없는 주문 아이디입니다(범위를 넘는 숫자).' }]);
  });
});

describe('runShipmentRegistration — 요청 실패 분류', () => {
  it('500 · 봉투 아닌 응답 · ApiError 가 아닌 값은 결과 모름이다', async () => {
    for (const cause of [
      new ApiError('server', 500, 'COMMON_500', '서버 오류'),
      new ApiError('invalidResponse', 200, null, null),
      new TypeError('boom'),
    ]) {
      const run = await runShipmentRegistration(
        vi.fn().mockRejectedValue(cause) as unknown as Call,
        [item('1')],
      );
      expect(run.rows[0]).toMatchObject({ result: 'UNKNOWN', message: SHIPMENT_UNKNOWN });
      expect(run.sessionExpired).toBe(false);
    }
  });

  it('400 은 서버 코드를 줄에 싣는다', async () => {
    const run = await runShipmentRegistration(
      vi
        .fn()
        .mockRejectedValue(
          new ApiError(
            'server',
            400,
            'DELIVERY_142',
            'orderId 는 1 이상의 숫자 문자열이어야 합니다.',
          ),
        ) as unknown as Call,
      [item('1')],
    );
    expect(run.rows[0]).toMatchObject({ result: 'NOT_SENT', errorCode: 'DELIVERY_142' });
  });

  it('뒤 요청에서 401 이면 앞 결과는 두고 세션 만료로 표시한다(화면은 다시 읽지 않는다)', async () => {
    const items = Array.from({ length: 21 }, (_, index) => item(String(index + 1)));
    const call = vi
      .fn()
      .mockImplementationOnce(async (_path: string, init: { json: { items: ShipmentItem[] } }) =>
        registered(init.json.items),
      )
      .mockRejectedValueOnce(new ApiError('unauthorized', 401, null, null)) as unknown as Call;
    const run = await runShipmentRegistration(call, items);
    expect(run.sessionExpired).toBe(true);
    expect(run.denied).toBeNull();
    expect(run.rows.slice(0, 20).every((one) => one.result === 'REGISTERED')).toBe(true);
    expect(run.rows[20]).toMatchObject({ result: 'NOT_SENT', message: SESSION_EXPIRED_ON_SAVE });
  });
});

describe('shipmentNoticeWithoutReload', () => {
  it('등록된 송장은 다시 읽지 못해도 등록했다고 알린다', () => {
    expect(shipmentNoticeWithoutReload(row({}), LOAD_FAILED).message).toBe(
      `송장(CJ대한통운 T1)을 등록했습니다. 최신 상세는 ${LOAD_FAILED}`,
    );
  });

  it('실패는 서버 문구 뒤에 불러오기 실패를 붙인다', () => {
    expect(
      shipmentNoticeWithoutReload(row({ result: 'FAILED', message: '실패 이유' }), LOAD_FAILED),
    ).toEqual({ tone: 'warn', message: `실패 이유 ${LOAD_FAILED}` });
  });
});
