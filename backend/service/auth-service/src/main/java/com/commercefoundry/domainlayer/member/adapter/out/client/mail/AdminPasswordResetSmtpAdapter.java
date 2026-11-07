package com.commercefoundry.domainlayer.member.adapter.out.client.mail;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.AdminPasswordResetMailPort;
import com.commercefoundry.global.properties.AdminPasswordResetProperties;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/** 인증 SMTP(SES 호환). 이메일과 본문을 담을 수 있는 메일 예외 원문을 외부에 넘기지 않는다. */
@Component
public class AdminPasswordResetSmtpAdapter implements AdminPasswordResetMailPort {
    private final JavaMailSender sender;
    private final AdminPasswordResetProperties properties;

    public AdminPasswordResetSmtpAdapter(AdminPasswordResetProperties properties) {
        this.properties = properties;
        JavaMailSenderImpl smtp = new JavaMailSenderImpl();
        smtp.setHost(properties.smtpHost());
        smtp.setPort(properties.smtpPort());
        smtp.setUsername(properties.smtpUsername());
        smtp.setPassword(properties.smtpPassword());
        smtp.setDefaultEncoding("UTF-8");
        var settings = smtp.getJavaMailProperties();
        settings.setProperty("mail.smtp.auth", "true");
        settings.setProperty("mail.smtp.starttls.enable", "true");
        settings.setProperty("mail.smtp.starttls.required", "true");
        settings.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        settings.setProperty("mail.smtp.connectiontimeout", "3000");
        settings.setProperty("mail.smtp.timeout", "5000");
        settings.setProperty("mail.smtp.writetimeout", "5000");
        settings.setProperty("mail.debug", "false");
        this.sender = smtp;
    }

    @Override
    public void send(String recipient, String resetLink) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.sender());
        message.setTo(recipient);
        message.setSubject("운영자 비밀번호 재설정 안내");
        message.setText("아래 링크에서 새 비밀번호를 정해 주세요. 링크는 한 번만 사용할 수 있습니다.\n\n" + resetLink
            + "\n\n요청하지 않았다면 대표 관리자에게 알려 주세요.");
        try { sender.send(message); } catch (MailException failure) {
            throw new MemberException(MemberErrorCode.ADMIN_PASSWORD_RESET_UNAVAILABLE);
        }
    }
}
