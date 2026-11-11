package com.commercefoundry.global.config;

import com.commercefoundry.common.config.SwaggerPropertiesConfig;
import com.commercefoundry.global.properties.PaymentCancelVoidProperties;
import com.commercefoundry.global.properties.PaymentGateProperties;
import com.commercefoundry.global.properties.PaymentAutoCancelSchedulerProperties;
import com.commercefoundry.global.properties.PaymentRecoveryProperties;
import com.commercefoundry.global.properties.PaymentOrderReconciliationProperties;
import com.commercefoundry.global.properties.PaymentPrepareProperties;
import com.commercefoundry.global.properties.TossPaymentsProperties;
import com.commercefoundry.messaging.config.MessagingPropertiesConfig;
import com.commercefoundry.persistence.config.SnowflakePropertiesConfig;
import com.commercefoundry.security.internal.InternalTokenPropertiesConfig;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({
    InternalTokenPropertiesConfig.class,
    JwtVerificationPropertiesConfig.class,
    MessagingPropertiesConfig.class,
    SnowflakePropertiesConfig.class,
    SwaggerPropertiesConfig.class
})
@EnableConfigurationProperties({PaymentAutoCancelSchedulerProperties.class, PaymentGateProperties.class, PaymentRecoveryProperties.class,
    PaymentCancelVoidProperties.class, PaymentOrderReconciliationProperties.class,
    PaymentPrepareProperties.class, TossPaymentsProperties.class})
public class PaymentServicePropertiesConfig {

}
