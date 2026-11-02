'use client';

import { Button, Checkbox } from '@cf/ui';
import { useEffect, useId, useRef, useState } from 'react';
import { recoveryCodesText } from '@/lib/admin-account/otp';
import { PEER_MIN_CODES_TO_SEND } from '@/lib/admin-account/peer-otp';

const COPY_FAILED = '복사하지 못했습니다. 화면의 코드를 직접 옮겨 적어 주세요.';

/**
 * 복구 코드 한 번 보여 주기(#417). 코드는 이 컴포넌트의 props(화면 상태)에만 있고, 닫으면 사라진다 — 다시 볼 수 없다.
 * 보관(전달)했다고 체크해야 닫을 수 있고, 보이는 동안 창을 닫으려 하면 브라우저가 한 번 묻는다.
 * `recipient` 가 있으면 동료 OTP 복구(#428)로 받은 대상의 코드다 — 전달 방법과 대상의 다음 절차를 안내한다.
 */
export function RecoveryCodesPanel({
  codes,
  regenerated,
  recipient,
  onClose,
}: {
  codes: readonly string[];
  /** 다시 받은 코드면 이전 코드가 무효라는 안내를 더한다 */
  regenerated: boolean;
  /** 동료 복구 대상의 이름(마스킹 값). 없으면 내 코드다 */
  recipient?: string;
  onClose: () => void;
}) {
  const [kept, setKept] = useState(false);
  const [copy, setCopy] = useState<'idle' | 'done' | 'failed'>('idle');
  const headingRef = useRef<HTMLHeadingElement>(null);
  const headingId = useId();

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
    <section aria-labelledby={headingId} className="flex max-w-xl flex-col gap-4">
      <div>
        <h2
          id={headingId}
          ref={headingRef}
          tabIndex={-1}
          className="text-lg font-semibold outline-none"
        >
          {recipient ? `${recipient} 님의 새 복구 코드` : '복구 코드'}
        </h2>
        <p className="mt-1 text-sm text-muted-fg">
          {recipient
            ? '이 관리자의 2단계 인증을 새로 바꿨습니다. 이전 인증 앱 등록과 복구 코드는 이제 쓸 수 없고, 기존 로그인도 끊겼습니다.'
            : '휴대폰을 잃어버리거나 인증 앱을 쓸 수 없을 때 인증 코드 대신 입력합니다. 코드마다 한 번만 쓸 수 있습니다.'}
        </p>
      </div>
      {recipient ? (
        <div role="alert" className="flex flex-col gap-2 rounded-md bg-muted px-4 py-3 text-sm">
          <p>
            이 코드는 지금 한 번만 보여 드립니다. 이 중 {PEER_MIN_CODES_TO_SEND}개 이상을 대상
            관리자에게 전해 주세요. 직접 만나거나, 미리 알고 있는 번호로 전화해 본인임을 확인한 뒤
            불러 주는 것처럼 확인된 별도 경로로만 전합니다.
          </p>
          <p>단체 대화방 · 이메일 · 업무 티켓 · 이슈에는 붙여 넣지 마세요.</p>
        </div>
      ) : (
        <p role="alert" className="rounded-md bg-muted px-4 py-3 text-sm">
          이 코드는 지금 한 번만 보여 드립니다. 비밀번호 관리 앱이나 인쇄본처럼 다른 사람이 볼 수
          없는 곳에 보관해 주세요.
          {regenerated && ' 이전에 받은 복구 코드는 이제 쓸 수 없습니다.'}
        </p>
      )}
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
      {recipient && (
        <div className="flex flex-col gap-2 text-sm">
          <h3 className="font-medium">대상 관리자에게 알려 줄 다음 순서</h3>
          <ol className="list-decimal space-y-1 pl-5">
            <li>
              이메일 · 비밀번호를 입력한 뒤 인증 코드 칸에 받은 복구 코드 하나를 넣어 로그인합니다.
            </li>
            <li>
              계정 메뉴의 &quot;2단계 인증&quot; 에서 &quot;2단계 인증 끄기&quot; 를 누르고,
              로그인에 쓰지 않은 다른 복구 코드로 기존 2단계 인증을 끕니다. 한 번 쓴 코드는 다시 쓸
              수 없습니다.
            </li>
            <li>같은 화면에서 새 휴대폰의 인증 앱으로 2단계 인증을 다시 등록합니다.</li>
          </ol>
        </div>
      )}
      <Checkbox
        label={
          recipient
            ? '대상 관리자에게 코드를 전했거나, 전할 때까지 안전하게 옮겨 적었습니다.'
            : '복구 코드를 안전한 곳에 보관했습니다.'
        }
        checked={kept}
        onChange={(event) => setKept(event.currentTarget.checked)}
      />
      <Button onClick={onClose} disabled={!kept} className="self-start">
        완료
      </Button>
    </section>
  );
}
