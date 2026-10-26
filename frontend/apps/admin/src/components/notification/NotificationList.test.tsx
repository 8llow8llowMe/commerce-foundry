import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { NotificationItem } from '@/lib/notification/types';
import { unreadBadgeText } from './NotificationCenter';
import { NotificationList } from './NotificationList';
import { NotificationToasts } from './NotificationToasts';

const noop = () => {};

function item(overrides: Partial<NotificationItem> = {}): NotificationItem {
  return {
    notificationId: '7',
    kind: { code: 'JOB_FAILED', name: '작업 실패', description: '' },
    severity: { code: 'WARNING', name: '주의', description: '' },
    title: '월 매출 리포트 내보내기가 실패했습니다',
    linkPath: '/admin/jobs/42',
    read: false,
    createdAt: '2026-10-26T01:00:00Z',
    ...overrides,
  };
}

describe('NotificationList', () => {
  it('제목을 콘솔 경로 링크로, 종류 · 심각도 name · 한국 시각과 읽음 버튼을 보인다(코드는 보이지 않는다)', () => {
    const html = renderToStaticMarkup(
      <NotificationList items={[item()]} onOpen={noop} onMarkRead={noop} />,
    );
    expect(html).toContain('href="/jobs/42"');
    expect(html).toContain('월 매출 리포트 내보내기가 실패했습니다');
    expect(html).toContain('작업 실패');
    expect(html).toContain('주의');
    expect(html).toContain('10:00:00');
    expect(html).toContain('읽지 않음');
    expect(html).toContain('읽음으로 표시');
    expect(html).not.toContain('JOB_FAILED');
    expect(html).not.toContain('WARNING');
  });

  it('읽은 알림은 읽음 버튼이 없고, 안내 심각도는 이름을 따로 보이지 않으며, 링크가 없거나 쓸 수 없으면 글자만', () => {
    const html = renderToStaticMarkup(
      <NotificationList
        items={[
          item({
            notificationId: '1',
            read: true,
            severity: { code: 'INFO', name: '안내', description: '' },
            linkPath: null,
          }),
          item({ notificationId: '2', read: true, linkPath: 'https://evil.test' }),
        ]}
        onOpen={noop}
        onMarkRead={noop}
      />,
    );
    expect(html).not.toContain('href=');
    expect(html).not.toContain('읽음으로 표시');
    expect(html).not.toContain('안내');
  });

  it('비어 있으면 안내 문구', () => {
    expect(
      renderToStaticMarkup(<NotificationList items={[]} onOpen={noop} onMarkRead={noop} />),
    ).toContain('알림이 없습니다.');
  });
});

describe('NotificationToasts', () => {
  it('알림 토스트는 보기 링크를, 요약 토스트는 건수와 알림 보기를 보인다', () => {
    const html = renderToStaticMarkup(
      <NotificationToasts
        toasts={[
          { key: 'a', kind: 'item', item: item() },
          { key: 'b', kind: 'summary', count: 5 },
        ]}
        onOpen={noop}
        onDismiss={noop}
        onShowAll={noop}
        onHold={noop}
      />,
    );
    // 읽어 주는 곳에는 문장만 — 링크 · 버튼은 그 밖에
    expect(html).toContain(
      '<div role="status" class="sr-only">작업 실패: 월 매출 리포트 내보내기가 실패했습니다 새 알림 5건이 있습니다.</div>',
    );
    expect(html).toContain('href="/jobs/42"');
    expect(html).toContain('보기');
    expect(html).toContain('새 알림 5건이 있습니다.');
    expect(html).toContain('알림 보기');
    expect(html).toContain('aria-label="알림 닫기"');
  });

  it('긴급은 강조 테두리다', () => {
    const html = renderToStaticMarkup(
      <NotificationToasts
        toasts={[
          {
            key: 'a',
            kind: 'item',
            item: item({ severity: { code: 'CRITICAL', name: '긴급', description: '' } }),
          },
        ]}
        onOpen={noop}
        onDismiss={noop}
        onShowAll={noop}
        onHold={noop}
      />,
    );
    expect(html).toContain('border-danger');
    expect(html).toContain('<div role="alert" class="sr-only">작업 실패: ');
    expect(html).toContain('긴급');
  });
});

describe('unreadBadgeText', () => {
  it.each([
    [null, ''],
    [0, ''],
    [3, '3'],
    [99, '99'],
    [100, '99+'],
  ])('%s → %j', (count, text) => {
    expect(unreadBadgeText(count)).toBe(text);
  });
});
