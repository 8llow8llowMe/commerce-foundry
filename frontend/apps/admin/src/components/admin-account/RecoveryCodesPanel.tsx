'use client';

import { Button, Checkbox } from '@cf/ui';
import { useEffect, useRef, useState } from 'react';
import { recoveryCodesText } from '@/lib/admin-account/otp';

const COPY_FAILED = '복사하지 못했습니다. 화면의 코드를 직접 옮겨 적어 주세요.';

/**
 * 복구 코드 한 번 보여 주기(#417). 코드는 이 컴포넌트의 props(화면 상태)에만 있고, 닫으면 사라진다 — 다시 볼 수 없다.
 * 보관했다고 체크해야 닫을 수 있고, 보이는 동안 창을 닫으려 하면 브라우저가 한 번 묻는다.
 */
export function RecoveryCodesPanel({
  codes,
  regenerated,
  onClose,
}: {
  codes: readonly string[];
  /** 다시 받은 코드면 이전 코드가 무효라는 안내를 더한다 */
  regenerated: boolean;
  onClose: () => void;
}) {
  const [kept, setKept] = useState(false);
  const [copy, setCopy] = useState<'idle' | 'done' | 'failed'>('idle');
  const headingRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    headingRef.current?.focus();
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, []);

  const copyAll = () => {
    navigator.clipboard.writeText(recoveryCodesText(codes)).then(
      () => setCopy('done'),
      () => setCopy('failed'),
    );
  };

  return (
    <section aria-labelledby="otp-recovery-heading" className="flex max-w-xl flex-col gap-4">
      <div>
        <h2
          id="otp-recovery-heading"
          ref={headingRef}
          tabIndex={-1}
          className="text-lg font-semibold outline-none"
        >
          복구 코드
        </h2>
        <p className="mt-1 text-sm text-muted-fg">
          휴대폰을 잃어버리거나 인증 앱을 쓸 수 없을 때 인증 코드 대신 입력합니다. 코드마다 한 번만
          쓸 수 있습니다.
        </p>
      </div>
      <p role="alert" className="rounded-md bg-muted px-4 py-3 text-sm">
        이 코드는 지금 한 번만 보여 드립니다. 비밀번호 관리 앱이나 인쇄본처럼 다른 사람이 볼 수 없는
        곳에 보관해 주세요.
        {regenerated && ' 이전에 받은 복구 코드는 이제 쓸 수 없습니다.'}
      </p>
      <ol className="grid grid-cols-2 gap-2 rounded-md border border-border p-4 font-mono text-sm">
        {codes.map((code) => (
          <li key={code}>{code}</li>
        ))}
      </ol>
      <div className="flex flex-wrap items-center gap-3">
        <Button variant="secondary" onClick={copyAll}>
          모두 복사
        </Button>
        <span role="status" className="text-sm text-muted-fg">
          {copy === 'done' ? '복사했습니다.' : copy === 'failed' ? COPY_FAILED : ''}
        </span>
      </div>
      <Checkbox
        label="복구 코드를 안전한 곳에 보관했습니다."
        checked={kept}
        onChange={(event) => setKept(event.currentTarget.checked)}
      />
      <Button onClick={onClose} disabled={!kept} className="self-start">
        완료
      </Button>
    </section>
  );
}
