package com.commercefoundry.domainlayer.member.adapter.out.client.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.global.properties.AdminPasswordResetProperties;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

class AdminPasswordResetSmtpAdapterTest {

    private final AdminPasswordResetProperties properties = new AdminPasswordResetProperties(true,
        "https://admin.example.com/reset", "smtp.example.com", 587, "fixture-user", "test-only-not-a-credential",
        "no-reply@example.com", null);

    @Test
    void actualSenderRequiresAuthenticatedStartTlsWithHostnameVerificationAndBoundedIo() {
        var adapter = new AdminPasswordResetSmtpAdapter(properties);
        Object configured = ReflectionTestUtils.getField(adapter, "sender");
        assertThat(configured).isInstanceOf(JavaMailSenderImpl.class);
        var sender = (JavaMailSenderImpl) configured;
        assertThat(sender.getHost()).isEqualTo(properties.smtpHost());
        assertThat(sender.getPort()).isEqualTo(properties.smtpPort());
        assertThat(sender.getUsername()).isEqualTo(properties.smtpUsername());
        assertThat(sender.getPassword()).isEqualTo(properties.smtpPassword());
        assertThat(sender.getDefaultEncoding()).isEqualTo("UTF-8");
        assertThat(sender.getJavaMailProperties())
            .containsEntry("mail.smtp.auth", "true")
            .containsEntry("mail.smtp.starttls.enable", "true")
            .containsEntry("mail.smtp.starttls.required", "true")
            .containsEntry("mail.smtp.ssl.checkserveridentity", "true")
            .containsEntry("mail.smtp.connectiontimeout", "3000")
            .containsEntry("mail.smtp.timeout", "5000")
            .containsEntry("mail.smtp.writetimeout", "5000")
            .containsEntry("mail.debug", "false");
        assertThat(sender.getJavaMailProperties()).doesNotContainKey("mail.smtp.ssl.trust");
        assertThat(sender.getSession().getDebug()).isFalse();
    }

    @Test
    void sendsOnlyIntendedRecipientAndResetLinkWithoutSmtpCredentials() {
        JavaMailSender sender = mock(JavaMailSender.class);
        var adapter = adapterWith(sender);
        String recipient = "operator@example.com";
        String link = "https://admin.example.com/reset#token=test-only-token";

        adapter.send(recipient, link);

        var captured = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captured.capture());
        SimpleMailMessage message = captured.getValue();
        assertThat(message.getFrom()).isEqualTo(properties.sender());
        assertThat(message.getTo()).containsExactly(recipient);
        assertThat(message.getCc()).isNull();
        assertThat(message.getBcc()).isNull();
        assertThat(message.getReplyTo()).isNull();
        assertThat(message.getSubject()).isEqualTo("운영자 비밀번호 재설정 안내");
        assertThat(message.getText()).isEqualTo("아래 링크에서 새 비밀번호를 정해 주세요. 링크는 한 번만 사용할 수 있습니다.\n\n"
            + link + "\n\n요청하지 않았다면 대표 관리자에게 알려 주세요.");
        assertThat(message.getText()).doesNotContain(properties.smtpUsername(), properties.smtpPassword(), recipient);
    }

    @Test
    void mailFailureExposesOnlyGenericAuth063WithoutRawCauseOrSuppressedFailure() {
        JavaMailSender sender = mock(JavaMailSender.class);
        String raw = "operator@example.com #token=test-only-token test-only-not-a-credential";
        doThrow(new MailSendException(raw, new IllegalStateException(raw))).when(sender).send(any(SimpleMailMessage.class));

        MemberException failure = catchThrowableOfType(() -> adapterWith(sender).send("operator@example.com",
            "https://admin.example.com/reset#token=test-only-token"), MemberException.class);

        assertThat(failure).isNotNull().hasMessage(MemberErrorCode.ADMIN_PASSWORD_RESET_UNAVAILABLE.getMessage()).hasNoCause();
        assertThat(failure.getErrorCode()).isEqualTo(MemberErrorCode.ADMIN_PASSWORD_RESET_UNAVAILABLE);
        assertThat(failure.getErrorCode().getCode()).isEqualTo("AUTH_063");
        assertThat(failure.getSuppressed()).isEmpty();
        var trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        assertThat(trace.toString()).doesNotContain(raw, "operator@example.com", "test-only-token", properties.smtpPassword());
    }

    private AdminPasswordResetSmtpAdapter adapterWith(JavaMailSender sender) {
        var adapter = new AdminPasswordResetSmtpAdapter(properties);
        // 발송만 대체해 네트워크에 접근하지 않고 운영 생성자와 SMTP 설정은 그대로 검증한다.
        ReflectionTestUtils.setField(adapter, "sender", sender);
        return adapter;
    }
}
