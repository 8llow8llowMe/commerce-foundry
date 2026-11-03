package com.commercefoundry.domainlayer.notification.adapter.out.client.feign;


import com.commercefoundry.common.dto.Response;
import com.commercefoundry.security.internal.client.InternalFeignClientConfiguration;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "${feign-client.target-services.auth-service:auth-service}", contextId = "notificationRecipientClient",
    configuration = InternalFeignClientConfiguration.class)
public interface NotificationRecipientClient {
    @GetMapping("/internal/v1/admin-notification-recipients")
    Response<RecipientPage> page(@RequestParam("afterId") Long afterId, @RequestParam("size") int size);

    record RecipientPage(List<String> memberIds) {
    }
}
