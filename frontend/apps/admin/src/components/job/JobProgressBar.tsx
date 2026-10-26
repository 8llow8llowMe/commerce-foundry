import { progressText } from '@/lib/job/detail';
import type { JobItem } from '@/lib/job/types';

/** 작업 진행률 막대 + 숫자. 보고 전(null)은 빈 막대와 대시다. 상세(#272)와 상단 바 작업 진행 표시(#214)가 같이 쓴다 */
export function JobProgressBar({
  job,
  label = '진행률',
  className = 'max-w-sm',
}: {
  job: Pick<JobItem, 'progressPercent'>;
  label?: string;
  className?: string;
}) {
  return (
    <div className="flex items-center gap-3">
      <div
        role="progressbar"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={job.progressPercent ?? undefined}
        className={`h-2 w-full overflow-hidden rounded-full bg-muted ${className}`}
      >
        <div
          className="h-full bg-primary"
          style={{ width: `${Math.min(100, Math.max(0, job.progressPercent ?? 0))}%` }}
        />
      </div>
      <span className="text-sm tabular-nums">{progressText(job.progressPercent)}</span>
    </div>
  );
}
