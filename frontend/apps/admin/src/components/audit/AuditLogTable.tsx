import { cx } from '@cf/ui';
import type { AdminDirectory } from '@/lib/audit/admins';
import type { AuditLogItem } from '@/lib/audit/types';
import { describeAuditLog, formatAuditTime } from '@/lib/audit/view';

/**
 * 관리자 감사 로그 표(#320 · #305). 운영자 문구만 보인다 — 행위 · 결과는 업무 이름, 대상은 문장(`targetText`),
 * 관리자는 마스킹한 이름(이름표에 없으면 "관리자"). 행위 · 결과 코드, 대상 원문, 회원 · 감사 로그 아이디는 내지 않는다.
 */
export function AuditLogTable({
  logs,
  admins,
}: {
  logs: AuditLogItem[];
  admins: AdminDirectory | null;
}) {
  return (
    // sr-only 같은 absolute 요소가 스크롤 상자 밖으로 나가지 않게 relative 를 준다
    <div className="relative overflow-x-auto">
      <table className="w-full min-w-[48rem] text-left text-sm">
        <caption className="sr-only">관리자 감사 로그</caption>
        <thead className="text-muted-fg">
          <tr>
            <th scope="col" className="py-2 pr-4 font-medium">
              시각
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              행위
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              대상
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              관리자
            </th>
            <th scope="col" className="py-2 font-medium">
              결과
            </th>
          </tr>
        </thead>
        <tbody>
          {logs.map((log) => {
            const view = describeAuditLog(log, admins);
            return (
              <tr key={log.auditLogId} className="border-t border-border align-top">
                <th
                  scope="row"
                  className="py-3 pr-4 font-normal whitespace-nowrap text-muted-fg tabular-nums"
                >
                  {formatAuditTime(log.createdAt)}
                </th>
                <td className="py-3 pr-4 font-medium whitespace-nowrap">{view.action}</td>
                <td className="py-3 pr-4">{view.target}</td>
                <td className="py-3 pr-4 whitespace-nowrap">
                  <span
                    title={view.actorHint ?? undefined}
                    className={cx(view.actorHint && 'text-muted-fg')}
                  >
                    {view.actor}
                  </span>
                </td>
                <td className={cx('py-3 whitespace-nowrap', view.failed && 'text-danger')}>
                  {view.result}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
