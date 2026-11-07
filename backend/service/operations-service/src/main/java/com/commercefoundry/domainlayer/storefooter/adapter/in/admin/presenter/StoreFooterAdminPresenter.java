package com.commercefoundry.domainlayer.storefooter.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.storefooter.adapter.in.admin.dto.response.StoreFooterAdminResponses;
import com.commercefoundry.domainlayer.storefooter.application.info.StoreFooterInfos;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterRevision;
import java.util.Comparator;
import org.springframework.stereotype.Component;

@Component
public class StoreFooterAdminPresenter {
    public StoreFooterAdminResponses.Detail detail(StoreFooterInfos.Detail info) {
        var footer = info.footer();
        return new StoreFooterAdminResponses.Detail(footer.version(), footer.latestRevision(), footer.publishedRevision(), revision(info.revision()));
    }

    public StoreFooterAdminResponses.Revision revision(StoreFooterRevision revision) {
        var text = revision.content().copyrightText();
        var links = revision.content().links().stream().sorted(Comparator.comparingInt(link -> link.displayOrder()))
            .map(link -> new StoreFooterAdminResponses.Link(link.type(), new StoreFooterAdminResponses.Text(link.labels().ko(), link.labels().en()),
                link.url(), link.displayOrder(), link.visible())).toList();
        return new StoreFooterAdminResponses.Revision(revision.revisionNumber(), new StoreFooterAdminResponses.Text(text.ko(), text.en()), links,
            Long.toString(revision.createdBy()), revision.createdAt(), revision.publishedBy() == null ? null : Long.toString(revision.publishedBy()), revision.publishedAt());
    }
}
