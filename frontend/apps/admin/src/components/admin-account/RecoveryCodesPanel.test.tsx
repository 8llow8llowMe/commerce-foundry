import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { OtpQrCode } from './OtpQrCode';
import { RecoveryCodesPanel } from './RecoveryCodesPanel';

const codes = ['aaaaa-bbbbb', 'ccccc-ddddd'];

describe('RecoveryCodesPanel', () => {
  it('코드를 모두 보이고, 보관 체크 전에는 완료를 막는다', () => {
    const html = renderToStaticMarkup(
      <RecoveryCodesPanel codes={codes} regenerated={false} onClose={() => {}} />,
    );
    for (const code of codes) expect(html).toContain(code);
    expect(html).toContain('한 번만 보여 드립니다');
    expect(html).not.toContain('이전에 받은 복구 코드');
    expect(html).toMatch(/<button[^>]*disabled=""[^>]*>완료<\/button>/);
  });

  it('다시 받은 코드면 이전 코드가 무효라고 알린다', () => {
    const html = renderToStaticMarkup(
      <RecoveryCodesPanel codes={codes} regenerated onClose={() => {}} />,
    );
    expect(html).toContain('이전에 받은 복구 코드는 이제 쓸 수 없습니다');
  });
});

describe('OtpQrCode', () => {
  it('URI 를 글자로 싣지 않고 칸 그림으로만 그린다', () => {
    const uri = 'otpauth://totp/Shop:a%40b.test?secret=JBSWY3DPEHPK3PXP&issuer=Shop';
    const html = renderToStaticMarkup(<OtpQrCode value={uri} />);
    expect(html).toContain('role="img"');
    expect(html).toContain('<path');
    expect(html).not.toContain('JBSWY3DPEHPK3PXP');
  });
});
