package com.commercefoundry.domainlayer.display.application.port.in;

import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.response.DisplayPreviewAdminResponse;
import com.commercefoundry.domainlayer.display.application.command.DisplaySectionUpsertCommand;
import java.util.List;

public interface DisplayPreviewAdminUseCase {

    DisplayPreviewAdminResponse preview(List<DisplaySectionUpsertCommand> sections);
}
