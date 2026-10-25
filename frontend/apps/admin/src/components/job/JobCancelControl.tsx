import { Button } from '@cf/ui';
import type { JobItem } from '@/lib/job/types';

/**
 * 작업 취소 버튼 · 확인(#272). 버튼은 서버가 준 `cancellable` 하나로만 보인다 — 상태로 추론하지 않는다.
 * 취소 요청됨 · 이미 끝남 같은 사정은 서버 안내 문구(`notice`)가 알린다.
 */
export function JobCancelControl({
  job,
  confirming,
  cancelling,
  onAsk,
  onDismiss,
  onConfirm,
}: {
  job: Pick<JobItem, 'cancellable'>;
  confirming: boolean;
  cancelling: boolean;
  onAsk: () => void;
  onDismiss: () => void;
  onConfirm: () => void;
}) {
  if (!job.cancellable) return null;
  if (!confirming) {
    return (
      <Button variant="secondary" className="self-start" onClick={onAsk}>
        작업 취소
      </Button>
    );
  }
  return (
    <div className="flex flex-col gap-3 rounded-md bg-muted px-4 py-3">
      <p className="text-sm">
        이 작업을 취소할까요? 기다리는 작업은 바로 취소되고, 실행 중인 작업은 하던 일을 마무리할 수
        있는 지점에서 멈춥니다. 되돌릴 수 없습니다.
      </p>
      <div className="flex gap-2">
        <Button disabled={cancelling} onClick={onConfirm}>
          {cancelling ? '취소하는 중…' : '취소 확인'}
        </Button>
        <Button variant="secondary" disabled={cancelling} onClick={onDismiss}>
          그만두기
        </Button>
      </div>
    </div>
  );
}
