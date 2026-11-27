package com.commercefoundry.domainlayer.picking.application.service.processor;

import com.commercefoundry.domainlayer.picking.application.command.PickingOrderCheckCommand;
import com.commercefoundry.domainlayer.picking.application.exception.PickingErrorCode;
import com.commercefoundry.domainlayer.picking.application.exception.PickingException;
import com.commercefoundry.domainlayer.picking.application.port.out.SkuBarcodeQueryPort;
import com.commercefoundry.domainlayer.picking.domain.model.PickingCheckSubmission;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PickingBarcodeProcessor {
    private final SkuBarcodeQueryPort skuBarcodes;

    public PickingCheckSubmission resolve(PickingOrderCheckCommand command) {
        if (command.barcodeQuantities().isEmpty()) {
            return new PickingCheckSubmission(command.quantities(), Map.of());
        }
        var resolved = skuBarcodes.resolve(command.barcodeQuantities().keySet());
        var quantities = new HashMap<>(command.quantities());
        var unknown = new HashMap<String, Long>();
        try {
            command.barcodeQuantities().forEach((barcode, quantity) -> {
                Long skuId = resolved.get(barcode);
                if (skuId == null) {
                    unknown.put(barcode, quantity);
                } else {
                    quantities.merge(skuId, quantity, Math::addExact);
                }
            });
        } catch (ArithmeticException exception) {
            throw new PickingException(PickingErrorCode.INVALID_CHECK, exception);
        }
        return new PickingCheckSubmission(Map.copyOf(quantities), Map.copyOf(unknown));
    }
}
