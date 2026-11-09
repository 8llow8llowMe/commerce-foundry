'use client';

import { apiFetch, type PageResponse } from '@cf/api-client';
import { Button, cx } from '@cf/ui';
import Link from 'next/link';
import { useEffect, useRef, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { deactivateFailure } from '@/lib/admin-account/errors';
import {
  passwordResetLinkPath,
  resetLinkFailure,
  resetLinkRequested,
} from '@/lib/admin-account/password-reset';
import { canRecoverPeer } from '@/lib/admin-account/peer-otp';
import { adminsApiPath, adminsHref, deactivatePath } from '@/lib/admin-account/query';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied } from '@/lib/api-errors';
import { useLoginCooldown } from '@/lib/auth/use-login-cooldown';
import type { MemberListItem } from '@/lib/member/query';
import { AdminCreateForm } from './AdminCreateForm';
import { PeerOtpRecoveryForm } from './PeerOtpRecoveryForm';
import { RecoveryCodesPanel } from './RecoveryCodesPanel';

type Loaded =
  | { key: string; kind: 'ok'; data: PageResponse<MemberListItem> }
  | { key: string; kind: 'failed' }
  | { key: string; kind: 'denied'; denied: AccessDenied };

/** 동료 OTP 복구(#428) 진행. 새 복구 코드(`codes`)는 여기(화면 상태)에만 있고 패널을 닫으면 버린다 */
type Recovery =
  | { kind: 'form'; member: MemberListItem }
  | { kind: 'codes'; member: MemberListItem; codes: string[] };

const linkButton = 'text-sm underline-offset-4 hover:underline';

const dateTime = new Intl.DateTimeFormat('ko-KR', {
  dateStyle: 'short',
  timeStyle: 'short',
  timeZone: 'Asia/Seoul',
});

/**
 * 가게 운영자 계정(#352) — 관리자 목록 · 추가 · 비활성화. 목록은 회원 목록(#241)의 `role=ADMIN` 이고 페이지는 주소가 정본이다.
 * 목록 응답에 활성 여부가 없어 비활성화한 계정도 계속 보인다 — 이번 방문에서 끈 계정만 표시를 붙이고 버튼을 거둔다.
 * 동료 OTP 복구(#428)는 내 계정(`selfMemberId`)이 아닌 줄에만 둔다. 내 아이디를 모르면 어느 줄에도 두지 않는다.
 * 비활성 · 없는 대상은 서버가 `AUTH_033` 으로 거절하고, 그 뒤 그 줄의 복구 버튼을 거둔다.
 * 비밀번호 재설정 링크(#237, BE #589)도 내 계정이 아닌 줄에 둔다 — 내 비밀번호는 내 계정 화면에서 바꾼다.
 * 서버는 대상이 없거나 메일이 실패해도 같은 성공으로 답하므로, 화면은 "요청했다" 까지만 말한다.
 */
export function AdminAccountManager({
  page,
  selfMemberId,
}: {
  page: number;
  /** 지금 세션 관리자의 아이디(토큰 `sub`). 화면 힌트일 뿐이고 본인 대상 거절의 정본은 BE `AUTH_045` 다 */
  selfMemberId: string | null;
}) {
  const path = adminsApiPath(page);
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [reloads, setReloads] = useState(0);
  const [adding, setAdding] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<string | null>(null);
  const [deactivating, setDeactivating] = useState<string | null>(null);
  const [sendingReset, setSendingReset] = useState<string | null>(null);
  const [rowError, setRowError] = useState<{ memberId: string; message: string } | null>(null);
  const [deactivated, setDeactivated] = useState<ReadonlySet<string>>(() => new Set());
  const [denied, setDenied] = useState<AccessDenied | null>(null);
  const [recovery, setRecovery] = useState<Recovery | null>(null);
  const [unrecoverable, setUnrecoverable] = useState<ReadonlySet<string>>(() => new Set());
  // 동료 복구는 행위자별 시도 창(15분 5회)을 쓴다 — 대상이 바뀌어도 같은 쿨다운이다
  const cooldown = useLoginCooldown();
  // 누른 버튼이 사라지는 전환(확인 열기 · 닫기 · 폼 열기 · 닫기) 뒤 포커스를 둘 `data-focus` 키. 그리지 않으면 body 로 빠진다.
  // 늘 상태 변경과 함께 정하므로 ref 로 두고 그다음 커밋에서 옮긴다(카테고리와 같은 방식)
  const pendingFocus = useRef<string | null>(null);
  const rootRef = useRef<HTMLDivElement>(null);
  const setFocusKey = (key: string) => {
    pendingFocus.current = key;
  };

  useEffect(() => {
    const key = pendingFocus.current;
    if (!key) return;
    pendingFocus.current = null;
    rootRef.current?.querySelector<HTMLElement>(`[data-focus="${key}"]`)?.focus();
  });

  // 응답 콜백에서만 상태를 바꾼다. 같은 경로를 다시 읽을 때도 구분하려고 키에 횟수를 붙인다
  const key = `${path}#${reloads}`;
  useEffect(() => {
    let alive = true;
    apiFetch<PageResponse<MemberListItem>>(path).then(
      (data) => alive && setLoaded({ key, kind: 'ok', data }),
      (cause: unknown) => {
        if (!alive) return;
        const access = accessDeniedOf(cause);
        setLoaded(access ? { key, kind: 'denied', denied: access } : { key, kind: 'failed' });
      },
    );
    return () => {
      alive = false;
    };
  }, [path, key]);

  // 다시 읽는 동안에는 이전 목록을 그대로 보인다(깜빡이지 않게). 페이지가 바뀌면 "불러오는 중"
  const current = loaded?.key.startsWith(`${path}#`) ? loaded : null;
  const reload = () => setReloads((n) => n + 1);

  const deactivate = async (member: MemberListItem) => {
    if (deactivating) return;
    setDeactivating(member.memberId);
    setRowError(null);
    setNotice(null);
    try {
      await apiFetch<null>(deactivatePath(member.memberId), { method: 'PATCH' });
      setDeactivated((prev) => new Set(prev).add(member.memberId));
      setConfirming(null);
      setFocusKey('add');
      setNotice(`${member.name} 계정을 비활성화했습니다. 이제 이 계정으로 로그인할 수 없습니다.`);
      reload();
    } catch (cause) {
      const failure = deactivateFailure(cause);
      if (failure.kind === 'access') {
        setDenied(failure.denied);
      } else {
        setRowError({ memberId: member.memberId, message: failure.message });
        if (failure.off) {
          setDeactivated((prev) => new Set(prev).add(member.memberId));
          setConfirming(null);
          setFocusKey('add');
        }
        if (failure.reload) reload();
      }
    } finally {
      setDeactivating(null);
    }
  };

  const sendResetLink = async (member: MemberListItem) => {
    if (sendingReset) return;
    setSendingReset(member.memberId);
    setRowError(null);
    setNotice(null);
    try {
      await apiFetch<null>(passwordResetLinkPath(member.memberId), { method: 'POST' });
      setNotice(resetLinkRequested(member.name));
    } catch (cause) {
      const failure = resetLinkFailure(cause);
      if (failure.kind === 'access') setDenied(failure.denied);
      else setRowError({ memberId: member.memberId, message: failure.message });
    } finally {
      setSendingReset(null);
    }
  };

  if (denied) return <AccessNotice denied={denied} />;

  // 새 복구 코드가 보이는 동안에는 목록 · 페이지 이동을 그리지 않는다 — 눌러서 코드를 잃지 않게(창 닫기는 패널이 묻는다)
  if (recovery?.kind === 'codes') {
    const { member, codes } = recovery;
    return (
      <div ref={rootRef} className="flex max-w-6xl flex-col gap-6">
        <h1 className="text-xl font-semibold">관리자 계정</h1>
        <RecoveryCodesPanel
          codes={codes}
          regenerated={false}
          recipient={member.name}
          onClose={() => {
            setRecovery(null);
            setFocusKey('add');
            setNotice(
              `${member.name} 님의 2단계 인증을 복구했습니다. 대상 관리자가 새 휴대폰으로 2단계 인증을 다시 등록했는지 확인해 주세요. 같은 관리자를 다시 복구하면 방금 코드는 쓸 수 없게 됩니다.`,
            );
          }}
        />
      </div>
    );
  }

  return (
    <div ref={rootRef} className="flex max-w-6xl flex-col gap-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-xl font-semibold">관리자 계정</h1>
          <p className="mt-1 text-sm text-muted-fg">
            어드민에 로그인할 수 있는 운영자입니다. 이메일 · 이름은 일부를 가려 보여 줍니다.
          </p>
        </div>
        {!adding && (
          <Button
            data-focus="add"
            // 복구 폼이 열린 동안에는 막는다 — 두 폼을 같이 열지 않는다
            disabled={recovery !== null}
            onClick={() => {
              setAdding(true);
              setNotice(null);
              setFocusKey('add-email');
            }}
          >
            관리자 추가
          </Button>
        )}
      </div>

      {adding && (
        <AdminCreateForm
          onCreated={() => {
            setAdding(false);
            setFocusKey('add');
            setNotice('관리자를 추가했습니다. 처음 비밀번호를 추가한 분께 따로 전해 주세요.');
            reload();
          }}
          onUncertain={reload}
          onDenied={setDenied}
          onCancel={() => {
            setAdding(false);
            setFocusKey('add');
          }}
        />
      )}

      {recovery?.kind === 'form' && (
        <PeerOtpRecoveryForm
          key={recovery.member.memberId}
          target={recovery.member}
          cooldown={cooldown}
          onRecovered={(codes) => {
            setRecovery({ kind: 'codes', member: recovery.member, codes });
            setNotice(null);
          }}
          onGone={(message) => {
            const { memberId } = recovery.member;
            setUnrecoverable((prev) => new Set(prev).add(memberId));
            setRecovery(null);
            setRowError({ memberId, message });
            // 오류 문구가 그 줄에 붙는다 — 포커스도 그 줄(비활성화 버튼)에 둔다
            setFocusKey(`deactivate:${memberId}`);
          }}
          onDenied={setDenied}
          onCancel={() => {
            setFocusKey(`recover:${recovery.member.memberId}`);
            setRecovery(null);
          }}
        />
      )}

      <p role="status" className="min-h-5 text-sm">
        {notice}
      </p>

      {!current && <p className="text-sm text-muted-fg">불러오는 중…</p>}
      {current?.kind === 'denied' && <AccessNotice denied={current.denied} />}
      {current?.kind === 'failed' && (
        <div role="alert" className="flex items-center gap-4">
          <p className="text-sm text-danger">{LOAD_FAILED}</p>
          <Button variant="secondary" onClick={reload}>
            다시 불러오기
          </Button>
        </div>
      )}
      {current?.kind === 'ok' && current.data.contents.length === 0 && (
        <p className="text-sm text-muted-fg">
          이 페이지에는 관리자가 없습니다.{' '}
          {page > 0 && (
            <Link href={adminsHref(0)} className="text-fg underline underline-offset-4">
              첫 페이지로
            </Link>
          )}
        </p>
      )}
      {current?.kind === 'ok' && current.data.contents.length > 0 && (
        <>
          <p className="text-sm text-muted-fg">
            {current.data.totalElements}명 · 비활성화한 계정도 목록에 남습니다.
          </p>
          <div className="relative overflow-x-auto">
            <table className="w-full min-w-[40rem] text-left text-sm">
              <caption className="sr-only">관리자 목록</caption>
              <thead className="text-muted-fg">
                <tr>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    이름
                  </th>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    이메일
                  </th>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    추가일
                  </th>
                  <th scope="col" className="py-2 font-medium">
                    <span className="sr-only">관리</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {current.data.contents.map((member) => {
                  const off = deactivated.has(member.memberId);
                  const self = selfMemberId !== null && member.memberId === selfMemberId;
                  // 내 계정 · 내 아이디를 모를 때 · 이번 방문에 끈 계정 · 서버가 복구할 수 없다고 한 계정에는 두지 않는다
                  const recoverable =
                    !off && canRecoverPeer(member.memberId, selfMemberId, unrecoverable);
                  const error = rowError?.memberId === member.memberId ? rowError.message : null;
                  return (
                    <tr key={member.memberId} className="border-t border-border align-top">
                      <th scope="row" className="py-3 pr-4 font-medium whitespace-nowrap">
                        {member.name}
                        {self && (
                          <span className="ml-2 rounded bg-muted px-1.5 py-0.5 text-xs font-normal">
                            내 계정
                          </span>
                        )}
                        {off && (
                          <span className="ml-2 rounded bg-muted px-1.5 py-0.5 text-xs font-normal">
                            비활성화함
                          </span>
                        )}
                      </th>
                      <td className="max-w-[20rem] truncate py-3 pr-4" title={member.email}>
                        {member.email}
                      </td>
                      <td className="py-3 pr-4 whitespace-nowrap text-muted-fg">
                        {dateTime.format(new Date(member.createdAt))}
                      </td>
                      <td className="py-3">
                        <div className="flex flex-col items-end gap-1">
                          {off ? null : confirming === member.memberId ? (
                            <div className="flex flex-wrap items-center justify-end gap-3">
                              <span className="text-sm">
                                비활성화할까요? 이 계정은 바로 로그아웃되고 다시 로그인할 수
                                없습니다.
                              </span>
                              {/* 처리 중에도 disabled 로 두지 않는다 — 포커스가 body 로 빠진다. 겹친 요청은 deactivate 가 막는다 */}
                              <button
                                type="button"
                                className={cx(linkButton, 'font-medium text-danger')}
                                aria-label={`${member.name} 비활성화 확인`}
                                aria-disabled={deactivating !== null || undefined}
                                onClick={() => void deactivate(member)}
                              >
                                {deactivating === member.memberId ? '비활성화하는 중…' : '비활성화'}
                              </button>
                              <button
                                type="button"
                                className={linkButton}
                                aria-disabled={deactivating !== null || undefined}
                                data-focus={`cancel:${member.memberId}`}
                                onClick={() => {
                                  if (deactivating) return;
                                  setConfirming(null);
                                  setRowError(null);
                                  setFocusKey(`deactivate:${member.memberId}`);
                                }}
                              >
                                취소
                              </button>
                            </div>
                          ) : (
                            <div className="flex flex-wrap items-center justify-end gap-4">
                              {recoverable && (
                                <button
                                  type="button"
                                  className={linkButton}
                                  aria-label={`${member.name} 2단계 인증 복구`}
                                  disabled={deactivating !== null || adding || recovery !== null}
                                  data-focus={`recover:${member.memberId}`}
                                  onClick={() => {
                                    setRecovery({ kind: 'form', member });
                                    setRowError(null);
                                    setNotice(null);
                                  }}
                                >
                                  OTP 복구
                                </button>
                              )}
                              {!self && (
                                // 보내는 중에도 disabled 로 두지 않는다 — 포커스가 body 로 빠진다. 겹친 요청은 sendResetLink 가 막는다
                                <button
                                  type="button"
                                  className={linkButton}
                                  aria-label={`${member.name} 비밀번호 재설정 링크 보내기`}
                                  aria-disabled={sendingReset !== null || undefined}
                                  disabled={deactivating !== null || adding || recovery !== null}
                                  onClick={() => void sendResetLink(member)}
                                >
                                  {sendingReset === member.memberId
                                    ? '요청하는 중…'
                                    : '재설정 링크'}
                                </button>
                              )}
                              <button
                                type="button"
                                className={linkButton}
                                aria-label={`${member.name} 비활성화`}
                                // 추가 · 복구 폼이 열린 동안에는 막는다 — 비활성화 401 이 화면을 로그인 안내로 바꿔 입력을 잃지 않게
                                disabled={deactivating !== null || adding || recovery !== null}
                                data-focus={`deactivate:${member.memberId}`}
                                onClick={() => {
                                  setConfirming(member.memberId);
                                  // 누른 버튼이 확인으로 바뀌어 사라진다 — 포커스를 안전한 쪽(취소)에 둔다
                                  setFocusKey(`cancel:${member.memberId}`);
                                  setRowError(null);
                                }}
                              >
                                비활성화
                              </button>
                            </div>
                          )}
                          {error && (
                            <p role="alert" className="text-sm text-danger">
                              {error}
                            </p>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {current.data.totalPages > 1 && (
            <nav aria-label="페이지" className="flex items-center gap-3 text-sm">
              <PageLink page={page - 1} disabled={page === 0}>
                이전
              </PageLink>
              <span>
                {page + 1} / {current.data.totalPages}
              </span>
              <PageLink page={page + 1} disabled={page + 1 >= current.data.totalPages}>
                다음
              </PageLink>
            </nav>
          )}
        </>
      )}
    </div>
  );
}

function PageLink({
  page,
  disabled,
  children,
}: {
  page: number;
  disabled: boolean;
  children: string;
}) {
  if (disabled) return <span className="text-muted-fg">{children}</span>;
  return (
    <Link
      href={adminsHref(page)}
      className="rounded-md bg-accent px-3 py-1.5 text-accent-fg hover:opacity-90"
    >
      {children}
    </Link>
  );
}
