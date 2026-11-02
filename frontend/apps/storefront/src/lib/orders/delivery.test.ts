import { describe, expect, it, vi } from 'vitest';
import { deliveryLabel, orderDeliveryPath, readOrderDelivery, trackingUrl } from './delivery';

const carrier = (code: string) => ({ code, name: '택배사', description: '택배사' });

describe('orderDeliveryPath', () => {
  it('주문 아이디를 쿼리로 싣는다', () => {
    expect(orderDeliveryPath('42')).toBe('/api/v1/deliveries?orderId=42');
  });
});

describe('trackingUrl', () => {
  it('택배사 조회 주소에 송장 번호를 붙인다', () => {
    expect(trackingUrl({ carrier: carrier('CJ_LOGISTICS'), trackingNumber: '123456789012' })).toBe(
      'https://trace.cjlogistics.com/next/tracking.html?wblNo=123456789012',
    );
    expect(trackingUrl({ carrier: carrier('UPS'), trackingNumber: '1Z999AA1' })).toBe(
      'https://www.ups.com/track?tracknum=1Z999AA1',
    );
  });

  it('기타 · 모르는 택배사는 링크가 없다', () => {
    expect(trackingUrl({ carrier: carrier('OTHER'), trackingNumber: '123' })).toBeNull();
    expect(trackingUrl({ carrier: carrier('NEW_CARRIER'), trackingNumber: '123' })).toBeNull();
  });

  it('서버 형식이 아닌 송장 번호는 주소에 싣지 않는다', () => {
    expect(trackingUrl({ carrier: carrier('LOGEN'), trackingNumber: '../x?y=1' })).toBeNull();
    expect(trackingUrl({ carrier: carrier('LOGEN'), trackingNumber: '' })).toBeNull();
  });
});

describe('deliveryLabel', () => {
  const status = { code: 'IN_TRANSIT', name: '배송 중', description: '' };
  it('국문은 서버 이름, 영문은 사전(없으면 서버 이름)', () => {
    expect(deliveryLabel(status, { IN_TRANSIT: 'In transit' }, 'ko')).toBe('배송 중');
    expect(deliveryLabel(status, { IN_TRANSIT: 'In transit' }, 'en')).toBe('In transit');
    expect(deliveryLabel(status, {}, 'en')).toBe('배송 중');
  });
});

describe('readOrderDelivery', () => {
  const envelope = (body: unknown, status = 200, success = true) =>
    new Response(
      JSON.stringify({
        dataHeader: {
          success,
          resultCode: success ? null : 'DELIVERY_198',
          resultMessage: null,
          fieldErrors: null,
        },
        dataBody: body,
      }),
      { status, headers: { 'content-type': 'application/json' } },
    );

  it('송장이 있으면 ready, 없으면(null) ready + null', async () => {
    const found = { deliveryId: '7', trackingNumber: '1' };
    await expect(
      readOrderDelivery('42', vi.fn<typeof fetch>().mockResolvedValue(envelope(found))),
    ).resolves.toEqual({ status: 'ready', delivery: found });
    const fetchNull = vi.fn<typeof fetch>().mockResolvedValue(envelope(null));
    await expect(readOrderDelivery('42', fetchNull)).resolves.toEqual({
      status: 'ready',
      delivery: null,
    });
    expect(fetchNull.mock.calls[0]![0]).toBe('/api/v1/deliveries?orderId=42');
  });

  it('오류 · 연결 실패는 모두 failed(다시 읽는 중 아님)', async () => {
    const failed = { status: 'failed', retrying: false };
    await expect(
      readOrderDelivery('42', vi.fn<typeof fetch>().mockResolvedValue(envelope(null, 401, false))),
    ).resolves.toEqual(failed);
    await expect(
      readOrderDelivery('42', vi.fn<typeof fetch>().mockRejectedValue(new TypeError('offline'))),
    ).resolves.toEqual(failed);
  });
});
