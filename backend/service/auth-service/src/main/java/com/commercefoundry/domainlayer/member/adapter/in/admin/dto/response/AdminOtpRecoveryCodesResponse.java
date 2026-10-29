package com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "새로 발급한 OTP 복구 코드. 이 응답에서 한 번만 보여 준다 — 서버에는 해시만 남아 다시 볼 수 없다")
public record AdminOtpRecoveryCodesResponse(

    @Schema(description = "복구 코드 8개 (xxxxx-xxxxx). 각각 한 번만 쓸 수 있다", example = "[\"ABCDE-FGH23\", \"JKLMN-PQR45\"]")
    List<String> recoveryCodes
) {

    /** 코드 원문을 가린다. */
    @Override
    public String toString() {
        return "AdminOtpRecoveryCodesResponse[count=" + (recoveryCodes == null ? 0 : recoveryCodes.size()) + "]";
    }
}
