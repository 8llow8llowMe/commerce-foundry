import { useMemo } from 'react';
import { qrPath } from '@/lib/admin-account/otp';

/** 인증 앱으로 찍을 QR. BE 가 준 otpauth URI 를 화면에서 그린다(외부 서비스로 보내지 않는다) */
export function OtpQrCode({ value }: { value: string }) {
  const { size, d } = useMemo(() => qrPath(value), [value]);
  return (
    <svg
      viewBox={`0 0 ${size} ${size}`}
      role="img"
      aria-label="인증 앱으로 찍을 QR 코드"
      shapeRendering="crispEdges"
      className="size-48 bg-bg text-fg"
    >
      <path d={d} fill="currentColor" />
    </svg>
  );
}
