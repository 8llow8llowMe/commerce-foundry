'use client';

import { useEffect, useEffectEvent, useId, useRef, useState, type ReactNode } from 'react';

/**
 * 상단 바의 펼침 메뉴(알림 · 작업 진행). 버튼이 패널을 열고 닫는다 — Esc · 바깥 누르기로 닫고, Esc 면 버튼으로 초점을 돌린다.
 * 패널은 닫혀도 DOM 에 남는다(`hidden`) — 안의 작업 행이 닫힌 동안에도 상태를 계속 읽게.
 */
export function HeaderMenu({
  label,
  buttonLabel,
  title,
  onOpen,
  open: controlledOpen,
  onOpenChange,
  children,
}: {
  /** 버튼 안 내용(아이콘 · 숫자) */
  label: ReactNode;
  /** 버튼의 접근성 이름(숫자 포함 문장) */
  buttonLabel: string;
  /** 패널 제목 */
  title: string;
  onOpen?: () => void;
  /** 바깥에서 열고 닫을 때(토스트의 "알림 보기"). 주지 않으면 스스로 정한다 */
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
  children: (close: () => void) => ReactNode;
}) {
  const [innerOpen, setInnerOpen] = useState(false);
  const open = controlledOpen ?? innerOpen;
  const setOpen = (next: boolean) => {
    if (controlledOpen === undefined) setInnerOpen(next);
    onOpenChange?.(next);
  };
  const rootRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const panelId = useId();

  const close = useEffectEvent(() => setOpen(false));

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) close();
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      close();
      buttonRef.current?.focus();
    };
    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  const toggle = () => {
    if (!open) onOpen?.();
    setOpen(!open);
  };

  return (
    <div ref={rootRef} className="relative">
      <button
        ref={buttonRef}
        type="button"
        aria-label={buttonLabel}
        aria-expanded={open}
        aria-controls={panelId}
        onClick={toggle}
        className="relative flex h-9 items-center gap-1.5 rounded-md px-2.5 text-sm hover:bg-primary-fg/10 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary-fg"
      >
        {label}
      </button>
      <div
        id={panelId}
        role="region"
        aria-label={title}
        hidden={!open}
        // 좁은 화면에서는 버튼 위치와 무관하게 화면 너비에 맞춘다(왼쪽으로 넘치지 않게)
        className="fixed inset-x-4 top-14 z-40 mt-2 rounded-md border border-border bg-bg text-fg shadow-lg sm:absolute sm:inset-x-auto sm:right-0 sm:top-full sm:w-96"
      >
        {children(() => setOpen(false))}
      </div>
    </div>
  );
}
