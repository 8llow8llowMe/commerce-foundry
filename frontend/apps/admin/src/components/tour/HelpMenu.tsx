'use client';

import { usePathname } from 'next/navigation';
import { useState } from 'react';
import { HeaderMenu } from '@/components/HeaderMenu';
import { tourProgress } from '@/lib/tour/progress';
import { CONSOLE_TOUR, helpTopicFor } from '@/lib/tour/tours';

/**
 * 상단 바 도움말(#228) — 지금 화면의 짧은 설명 · 자주 묻는 질문, 그리고 안내(투어) 다시 보기.
 * 다시 보기는 진행 상태에 요청만 남기고 패널을 닫는다 — 투어는 `TourRunner` 가 시작한다.
 */
export function HelpMenu() {
  const pathname = usePathname();
  const topic = helpTopicFor(pathname);
  const screenTour = topic.tour;
  const [open, setOpen] = useState(false);

  const replay = (tourId: string) => {
    setOpen(false);
    tourProgress.request(tourId);
  };

  return (
    <HeaderMenu
      title="도움말"
      buttonLabel="도움말"
      open={open}
      onOpenChange={setOpen}
      label={<HelpIcon />}
    >
      {() => (
        <div className="flex max-h-[70dvh] flex-col gap-4 overflow-y-auto p-4 text-sm">
          <div>
            <p className="font-semibold">{topic.title}</p>
            <p className="mt-1 text-muted-fg">{topic.summary}</p>
          </div>

          {topic.faq.length > 0 && (
            <div>
              <p className="text-xs font-medium text-muted-fg">자주 묻는 질문</p>
              <ul className="mt-1 flex flex-col">
                {topic.faq.map((item) => (
                  <li key={item.question}>
                    <details className="group">
                      <summary className="cursor-pointer py-1.5 font-medium">
                        {item.question}
                      </summary>
                      <p className="pb-2 text-muted-fg">{item.answer}</p>
                    </details>
                  </li>
                ))}
              </ul>
            </div>
          )}

          <div className="flex flex-col gap-1 border-t border-border pt-3">
            {screenTour && (
              <MenuButton onClick={() => replay(screenTour.id)}>이 화면 안내 다시 보기</MenuButton>
            )}
            <MenuButton onClick={() => replay(CONSOLE_TOUR.id)}>처음 안내 다시 보기</MenuButton>
            <MenuButton
              onClick={() => {
                // 지금 화면부터 처음처럼 — 패널을 닫으면 처음 안내가 바로 시작된다
                setOpen(false);
                tourProgress.reset();
              }}
            >
              모든 화면 안내 다시 켜기
            </MenuButton>
            <p className="text-xs text-muted-fg">
              본 안내는 이 브라우저에 기억합니다. 다른 컴퓨터에서는 처음처럼 다시 보입니다.
            </p>
          </div>
        </div>
      )}
    </HeaderMenu>
  );
}

function MenuButton({ onClick, children }: { onClick: () => void; children: string }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="rounded-md px-2 py-1.5 text-left hover:bg-muted focus-visible:outline-2 focus-visible:outline-primary"
    >
      {children}
    </button>
  );
}

function HelpIcon() {
  return (
    <svg
      aria-hidden
      viewBox="0 0 24 24"
      className="size-5"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <circle cx="12" cy="12" r="9" />
      <path d="M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6v.6" />
      <path d="M12 17h.01" />
    </svg>
  );
}
