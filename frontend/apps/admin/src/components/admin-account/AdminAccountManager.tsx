'use client';

import { apiFetch, type PageResponse } from '@cf/api-client';
import { Button, cx } from '@cf/ui';
import Link from 'next/link';
import { useEffect, useRef, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import {
  passwordResetLinkPath,
  resetLinkFailure,
  resetLinkRequested,
} from '@/lib/admin-account/password-reset';
import { canRecoverPeer } from '@/lib/admin-account/peer-otp';
import {
  adminsApiPath,
  adminsHref,
  rolePath,
  statusPath,
  type ActiveFilter,
  type AdminAccountItem,
} from '@/lib/admin-account/query';
import {
  ASSIGNABLE_ROLES,
  changeFailure,
  isAssignableRole,
  ROLE_HINT,
  ROLE_LABEL,
  type AssignableRole,
} from '@/lib/admin-account/roles';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied } from '@/lib/api-errors';
import { useLoginCooldown } from '@/lib/auth/use-login-cooldown';
import { AdminCreateForm } from './AdminCreateForm';
import { PeerOtpRecoveryForm } from './PeerOtpRecoveryForm';
import { RecoveryCodesPanel } from './RecoveryCodesPanel';

type Loaded =
  | { key: string; kind: 'ok'; data: PageResponse<AdminAccountItem> }
  | { key: string; kind: 'failed' }
  | { key: string; kind: 'denied'; denied: AccessDenied };

/** 동료 OTP 복구(#428) 진행. 새 복구 코드(`codes`)는 여기(화면 상태)에만 있고 패널을 닫으면 버린다 */
type Recovery =
  | { kind: 'form'; member: AdminAccountItem }
  | { kind: 'codes'; member: AdminAccountItem; codes: string[] };

/** 줄 안에서 여는 확인 · 편집. 한 번에 한 줄 하나만 연다 */
type Editing =
  | { kind: 'status'; memberId: string; active: boolean }
  | { kind: 'role'; memberId: string; role: AssignableRole };

const linkButton = 'text-sm underline-offset-4 hover:underline';
const badge = 'ml-2 rounded bg-muted px-1.5 py-0.5 text-xs font-normal';

const FILTERS: readonly { active: ActiveFilter; label: string }[] = [
  { active: null, label: '전체' },
  { active: true, label: '사용 중' },
  { active: false, label: '꺼짐' },
];

const dateTime = new Intl.DateTimeFormat('ko-KR', {
  dateStyle: 'short',
  timeStyle: 'short',
  timeZone: 'Asia/Seoul',
});

/**
 * 가게 운영자 계정(#352 · #237) — 목록 · 추가 · 역할 변경 · 끄기/다시 켜기 · 동료 OTP 복구(#428) · 비밀번호 재설정 링크(#589).
 * 목록은 운영자 전용 `GET /admins`(BE #647)이고 페이지 · 사용 여부 거르기는 주소가 정본이다.
 * 역할 변경 · 끄기 · 다시 켜기는 서버가 본인 계정을 `AUTH_047` 로 거절하므로 내 계정 줄에는 두지 않는다(내 아이디를 모르면 서버 거절에 맡긴다).
 * 역할을 바꾸거나 끈 계정의 기존 로그인은 서버가 끊는다 — 확인 문구로 미리 알린다.
 * 재설정 링크는 서버가 대상이 없거나 메일이 실패해도 같은 성공으로 답하므로 "요청했다" 까지만 말한다.
 */
export function AdminAccountManager({
  page,
  active,
  selfMemberId,
}: {
  page: number;
  /** 사용 여부 거르기(주소 `?active=`). null 이면 전체 */
  active: ActiveFilter;
  /** 지금 세션 관리자의 아이디(토큰 `sub`). 화면 힌트일 뿐이고 본인 대상 거절의 정본은 BE(`AUTH_045` · `AUTH_047`)다 */
  selfMemberId: string | null;
}) {
  const path = adminsApiPath(page, active);
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [reloads, setReloads] = useState(0);
  const [adding, setAdding] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [editing, setEditing] = useState<Editing | null>(null);
  const [changing, setChanging] = useState<string | null>(null);
  const [sendingReset, setSendingReset] = useState<string | null>(null);
  const [rowError, setRowError] = useState<{ memberId: string; message: string } | null>(null);
  // 서버의 역할 나누기가 꺼져 있다(AUTH_049) — 이번 방문에서는 역할 변경 버튼을 거둔다
  const [roleSplitOff, setRoleSplitOff] = useState(false);
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
    apiFetch<PageResponse<AdminAccountItem>>(path).then(
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

  // 다시 읽는 동안에는 이전 목록을 그대로 보인다(깜빡이지 않게). 페이지 · 거르기가 바뀌면 "불러오는 중"
  const current = loaded?.key.startsWith(`${path}#`) ? loaded : null;
  const reload = () => setReloads((n) => n + 1);

  /** 역할 변경 · 끄기 · 다시 켜기. 성공하면 목록을 다시 읽어 서버 상태를 그린다 */
  const change = async (
    member: AdminAccountItem,
    request: { path: string; json: Record<string, unknown> },
    done: string,
  ) => {
    if (changing) return;
    setChanging(member.memberId);
    setRowError(null);
    setNotice(null);
    try {
      await apiFetch<null>(request.path, { method: 'PATCH', json: request.json });
      setEditing(null);
      setFocusKey('add');
      setNotice(done);
      reload();
    } catch (cause) {
      const failure = changeFailure(cause);
      if (failure.kind === 'access') {
        setDenied(failure.denied);
        return;
      }
      setRowError({ memberId: member.memberId, message: failure.message });
      if (failure.roleSplitOff) {
        setRoleSplitOff(true);
        setEditing(null);
        setFocusKey(`status:${member.memberId}`);
      }
      if (failure.reload) reload();
    } finally {
      setChanging(null);
    }
  };

  const changeStatus = (member: AdminAccountItem, next: boolean) =>
    change(
      member,
      { path: statusPath(member.memberId), json: { active: next } },
      next
        ? `${member.name} 계정을 다시 켰습니다. 끄기 전 비밀번호 · 2단계 인증으로 로그인합니다.`
        : `${member.name} 계정을 껐습니다. 이제 이 계정으로 로그인할 수 없습니다.`,
    );

  const changeRole = (member: AdminAccountItem, role: AssignableRole) =>
    change(
      member,
      { path: rolePath(member.memberId), json: { role } },
      `${member.name} 님의 역할을 ${ROLE_LABEL[role]}(으)로 바꿨습니다. 다시 로그인하면 새 역할이 적용됩니다.`,
    );

  const sendResetLink = async (member: AdminAccountItem) => {
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

  const closeEditing = (memberId: string, kind: Editing['kind']) => {
    if (changing) return;
    setEditing(null);
    setRowError(null);
    setFocusKey(`${kind}:${memberId}`);
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

  // 폼 · 확인이 열린 동안에는 다른 줄 버튼을 막는다 — 두 작업을 같이 열지 않고, 401 이 화면을 로그인 안내로 바꿔 입력을 잃지 않게
  const locked = changing !== null || adding || recovery !== null || editing !== null;

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
            disabled={recovery !== null || editing !== null}
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
            // 오류 문구가 그 줄에 붙는다 — 포커스도 그 줄(끄기 버튼)에 둔다
            setFocusKey(`status:${memberId}`);
            reload();
          }}
          onDenied={setDenied}
          onCancel={() => {
            setFocusKey(`recover:${recovery.member.memberId}`);
            setRecovery(null);
          }}
        />
      )}

      <nav aria-label="사용 여부" className="flex flex-wrap gap-2 text-sm">
        {FILTERS.map((filter) => {
          const selected = filter.active === active;
          return (
            <Link
              key={filter.label}
              href={adminsHref(0, filter.active)}
              // 2페이지 이상이면 링크(첫 페이지)와 지금 주소가 달라 'page' 가 아니라 "선택됨" 이다(회원 목록과 같다)
              aria-current={selected ? 'true' : undefined}
              className={cx(
                'rounded-md px-3 py-1.5',
                selected ? 'bg-primary font-medium text-primary-fg' : 'bg-muted hover:opacity-90',
              )}
            >
              {filter.label}
            </Link>
          );
        })}
      </nav>

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
          {active === false ? '꺼진 계정이 없습니다.' : '이 페이지에는 관리자가 없습니다.'}{' '}
          {page > 0 && (
            <Link href={adminsHref(0, active)} className="text-fg underline underline-offset-4">
              첫 페이지로
            </Link>
          )}
        </p>
      )}
      {current?.kind === 'ok' && current.data.contents.length > 0 && (
        <>
          <p className="text-sm text-muted-fg">{current.data.totalElements}명</p>
          <div className="relative overflow-x-auto">
            <table className="w-full min-w-[48rem] text-left text-sm">
              <caption className="sr-only">관리자 목록</caption>
              <thead className="text-muted-fg">
                <tr>
                  <th scope="col" className="py-2 pr-4 font-medium whitespace-nowrap">
                    이름
                  </th>
                  <th scope="col" className="py-2 pr-4 font-medium whitespace-nowrap">
                    이메일
                  </th>
                  <th scope="col" className="py-2 pr-4 font-medium whitespace-nowrap">
                    역할
                  </th>
                  <th scope="col" className="py-2 pr-4 font-medium whitespace-nowrap">
                    2단계 인증
                  </th>
                  <th scope="col" className="py-2 pr-4 font-medium whitespace-nowrap">
                    추가일
                  </th>
                  <th scope="col" className="py-2 font-medium">
                    <span className="sr-only">관리</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {current.data.contents.map((member) => {
                  const self = selfMemberId !== null && member.memberId === selfMemberId;
                  // 꺼진 계정 · 등록하지 않은 계정 · 내 계정 · 내 아이디를 모를 때 · 서버가 복구할 수 없다고 한 계정에는 두지 않는다
                  const recoverable =
                    member.active &&
                    member.otpEnrolled &&
                    canRecoverPeer(member.memberId, selfMemberId, unrecoverable);
                  const error = rowError?.memberId === member.memberId ? rowError.message : null;
                  const open = editing?.memberId === member.memberId ? editing : null;
                  return (
                    <tr
                      key={member.memberId}
                      className={cx(
                        'border-t border-border align-top',
                        !member.active && 'text-muted-fg',
                      )}
                    >
                      <th scope="row" className="py-3 pr-4 font-medium whitespace-nowrap">
                        {member.name}
                        {self && <span className={badge}>내 계정</span>}
                        {!member.active && <span className={badge}>꺼짐</span>}
                      </th>
                      <td className="max-w-[16rem] truncate py-3 pr-4" title={member.email}>
                        {member.email}
                      </td>
                      <td className="py-3 pr-4 whitespace-nowrap">{ROLE_LABEL[member.role]}</td>
                      <td className="py-3 pr-4 whitespace-nowrap">
                        {member.otpEnrolled ? '등록' : '미등록'}
                      </td>
                      <td className="py-3 pr-4 whitespace-nowrap text-muted-fg">
                        {dateTime.format(new Date(member.createdAt))}
                      </td>
                      <td className="py-3">
                        <div className="flex flex-col items-end gap-1">
                          {open?.kind === 'status' ? (
                            <div className="flex flex-wrap items-center justify-end gap-3">
                              <span className="text-sm text-fg">
                                {open.active
                                  ? '다시 켤까요? 끄기 전 비밀번호 · 2단계 인증으로 로그인할 수 있게 됩니다.'
                                  : '끌까요? 이 계정은 바로 로그아웃되고 다시 로그인할 수 없습니다.'}
                              </span>
                              {/* 처리 중에도 disabled 로 두지 않는다 — 포커스가 body 로 빠진다. 겹친 요청은 change 가 막는다 */}
                              <button
                                type="button"
                                className={cx(
                                  linkButton,
                                  'font-medium',
                                  !open.active && 'text-danger',
                                )}
                                aria-label={`${member.name} ${open.active ? '다시 켜기' : '끄기'} 확인`}
                                aria-disabled={changing !== null || undefined}
                                onClick={() => void changeStatus(member, open.active)}
                              >
                                {changing === member.memberId
                                  ? '바꾸는 중…'
                                  : open.active
                                    ? '다시 켜기'
                                    : '끄기'}
                              </button>
                              <button
                                type="button"
                                className={linkButton}
                                aria-disabled={changing !== null || undefined}
                                data-focus={`cancel:${member.memberId}`}
                                onClick={() => closeEditing(member.memberId, 'status')}
                              >
                                취소
                              </button>
                            </div>
                          ) : open?.kind === 'role' ? (
                            <RoleEditor
                              member={member}
                              value={open.role}
                              saving={changing === member.memberId}
                              busy={changing !== null}
                              onChange={(role) => setEditing({ ...open, role })}
                              onSave={() => void changeRole(member, open.role)}
                              onCancel={() => closeEditing(member.memberId, 'role')}
                            />
                          ) : self ? null : (
                            <div className="flex flex-wrap items-center justify-end gap-4">
                              {recoverable && (
                                <button
                                  type="button"
                                  className={linkButton}
                                  aria-label={`${member.name} 2단계 인증 복구`}
                                  disabled={locked}
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
                              {member.active && (
                                // 보내는 중에도 disabled 로 두지 않는다 — 포커스가 body 로 빠진다. 겹친 요청은 sendResetLink 가 막는다
                                <button
                                  type="button"
                                  className={linkButton}
                                  aria-label={`${member.name} 비밀번호 재설정 링크 보내기`}
                                  aria-disabled={sendingReset !== null || undefined}
                                  disabled={locked}
                                  onClick={() => void sendResetLink(member)}
                                >
                                  {sendingReset === member.memberId
                                    ? '요청하는 중…'
                                    : '재설정 링크'}
                                </button>
                              )}
                              {member.active && !roleSplitOff && (
                                <button
                                  type="button"
                                  className={linkButton}
                                  aria-label={`${member.name} 역할 변경`}
                                  disabled={locked}
                                  data-focus={`role:${member.memberId}`}
                                  onClick={() => {
                                    setEditing({
                                      kind: 'role',
                                      memberId: member.memberId,
                                      // 이행용 ADMIN 은 고를 수 없다 — 같은 권한인 대표 관리자를 먼저 보인다
                                      role: isAssignableRole(member.role) ? member.role : 'OWNER',
                                    });
                                    setRowError(null);
                                    setNotice(null);
                                    setFocusKey(`role-select:${member.memberId}`);
                                  }}
                                >
                                  역할 변경
                                </button>
                              )}
                              <button
                                type="button"
                                className={linkButton}
                                aria-label={`${member.name} ${member.active ? '끄기' : '다시 켜기'}`}
                                disabled={locked}
                                data-focus={`status:${member.memberId}`}
                                onClick={() => {
                                  setEditing({
                                    kind: 'status',
                                    memberId: member.memberId,
                                    active: !member.active,
                                  });
                                  setRowError(null);
                                  setNotice(null);
                                  // 누른 버튼이 확인으로 바뀌어 사라진다 — 포커스를 안전한 쪽(취소)에 둔다
                                  setFocusKey(`cancel:${member.memberId}`);
                                }}
                              >
                                {member.active ? '끄기' : '다시 켜기'}
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
              <PageLink href={adminsHref(page - 1, active)} disabled={page === 0}>
                이전
              </PageLink>
              <span>
                {page + 1} / {current.data.totalPages}
              </span>
              <PageLink
                href={adminsHref(page + 1, active)}
                disabled={page + 1 >= current.data.totalPages}
              >
                다음
              </PageLink>
            </nav>
          )}
        </>
      )}
    </div>
  );
}

/** 줄 안의 역할 고르기. 저장하면 대상 계정의 기존 로그인이 끊긴다 */
function RoleEditor({
  member,
  value,
  saving,
  busy,
  onChange,
  onSave,
  onCancel,
}: {
  member: AdminAccountItem;
  value: AssignableRole;
  saving: boolean;
  busy: boolean;
  onChange: (role: AssignableRole) => void;
  onSave: () => void;
  onCancel: () => void;
}) {
  const id = `role-${member.memberId}`;
  return (
    <div className="flex max-w-md flex-col items-end gap-2">
      <div className="flex flex-wrap items-center justify-end gap-3">
        <label htmlFor={id} className="text-sm text-fg">
          {member.name} 님의 역할
        </label>
        <select
          id={id}
          value={value}
          aria-describedby={`${id}-hint`}
          data-focus={`role-select:${member.memberId}`}
          onChange={(event) => {
            const next = event.currentTarget.value;
            if (isAssignableRole(next)) onChange(next);
          }}
          className="h-10 min-w-0 rounded-md border border-border bg-bg px-3 text-fg"
        >
          {ASSIGNABLE_ROLES.map((role) => (
            <option key={role} value={role}>
              {ROLE_LABEL[role]}
            </option>
          ))}
        </select>
      </div>
      <p id={`${id}-hint`} className="text-right text-xs text-muted-fg">
        {ROLE_HINT[value]}. 바꾸면 이 계정은 바로 로그아웃되고, 다시 로그인하면 새 역할이
        적용됩니다.
      </p>
      <div className="flex gap-3">
        {/* 처리 중에도 disabled 로 두지 않는다 — 포커스가 body 로 빠진다. 겹친 요청은 change 가 막는다 */}
        <button
          type="button"
          className={cx(linkButton, 'font-medium text-fg')}
          aria-label={`${member.name} 역할 저장`}
          aria-disabled={busy || undefined}
          onClick={onSave}
        >
          {saving ? '바꾸는 중…' : '저장'}
        </button>
        <button
          type="button"
          className={cx(linkButton, 'text-fg')}
          aria-disabled={busy || undefined}
          onClick={onCancel}
        >
          취소
        </button>
      </div>
    </div>
  );
}

function PageLink({
  href,
  disabled,
  children,
}: {
  href: string;
  disabled: boolean;
  children: string;
}) {
  if (disabled) return <span className="text-muted-fg">{children}</span>;
  return (
    <Link href={href} className="rounded-md bg-accent px-3 py-1.5 text-accent-fg hover:opacity-90">
      {children}
    </Link>
  );
}
