package com.commercefoundry.domainlayer.storefooter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooter;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterContent;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterContent.Link;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterContent.Text;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterRevision;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterRuleException;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterRuleException.Reason;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterUrlPolicy;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterValidator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class StoreFooterDomainTest {
    private final StoreFooterUrlPolicy urls = new StoreFooterUrlPolicy(Set.of("links.example.com"));

    @ParameterizedTest
    @CsvSource({
        "INSTAGRAM, https://instagram.com/example_account",
        "INSTAGRAM, HTTPS://WWW.INSTAGRAM.COM/example.account/",
        "KAKAO_CHANNEL, https://pf.kakao.com/_example-channel",
        "KAKAO_CHANNEL, https://pf.kakao.com/_example-channel/chat",
        "CUSTOM, HTTPS://LINKS.EXAMPLE.COM/help/%61bout",
        "CUSTOM, https://links.example.com"
    })
    void acceptsStructuredHostAndChannelPaths(String type, String url) {
        assertThatCode(() -> urls.validate(type, url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "http://links.example.com/a", "javascript:alert(1)", "data:text/plain,example", "file:///example",
        "mailto:help@example.com", "tel:123", "/relative", "//links.example.com/a",
        "https://links.example.com.evil.example/a", "https://sub.links.example.com/a", "https://example.com/a",
        "https://links.example.com@other.example/a", "https://other.example@links.example.com/a",
        "https://links.example.com:443/a", "https://links.example.com./a", "https://*.example.com/a",
        "https://127.0.0.1/a", "https://[::1]/a", "https://localhost/a", "https://service.internal/a",
        "https://links.example.com/a?next=https://other.example", "https://links.example.com/a#fragment",
        "https://links.example.com\\@other.example/a", "https://links.example.com/a b", "https://links.example.com/a\nb",
        "https://links.example.com/%2fother", "https://links.example.com/%5cother", "https://links.example.com/%252fother",
        "https://links.example.com/%3fquery", "https://links.example.com/%23fragment", "https://links.example.com/%00",
        "https://links.example.com/../a", "https://links.example.com/%2e%2e/a", "https://links.example.com/./a",
        "https://links.example.com/한글", "https://línks.example.com/a", "https://links.example.com/%FF"
    })
    void rejectsUrlBypassesEvenForHiddenLinks(String url) {
        error(() -> validate(List.of(link(new Text("예시", "Example"), url, 0, false))), Reason.URL);
    }

    @ParameterizedTest
    @CsvSource({
        "INSTAGRAM, https://instagram.com/", "INSTAGRAM, https://instagram.com/example/media",
        "INSTAGRAM, https://www.instagram.com.evil.example/example", "INSTAGRAM, https://pf.kakao.com/_example",
        "KAKAO_CHANNEL, https://pf.kakao.com/example", "KAKAO_CHANNEL, https://pf.kakao.com/_example/other",
        "KAKAO_CHANNEL, https://pf.kakao.com/_example/chat/extra", "KAKAO_CHANNEL, https://instagram.com/example"
    })
    void channelTypeCannotBypassItsOwnHostOrPath(String type, String url) {
        error(() -> urls.validate(type, url), Reason.URL);
    }

    @Test
    void customHostsAreClosedByDefaultAndConfigurationCannotApproveInternalOrWildcardHosts() {
        error(() -> new StoreFooterUrlPolicy(Set.of()).validate("CUSTOM", "https://links.example.com/a"), Reason.URL);
        for (String host : List.of("*.example.com", "localhost", "service.internal", "127.0.0.1", "https://example.com", "-bad.example.com")) {
            assertThatThrownBy(() -> new StoreFooterUrlPolicy(Set.of(host))).isInstanceOf(IllegalArgumentException.class);
        }
        error(() -> urls.validate("CUSTOM", "https://links.example.com/" + "a".repeat(2048)), Reason.URL);
    }

    @Test
    void bothLanguagesAreMandatoryOnHiddenLinksAndMeasuredInUtf16Units() {
        for (String bad : Arrays.asList(null, "", " ", "a\nb", "a\u0085b", "a".repeat(81), "😀".repeat(41))) {
            error(() -> validate(List.of(link(new Text(bad, "Example"), "https://links.example.com", 0, false))), Reason.LABELS);
            error(() -> validate(List.of(link(new Text("예시", bad), "https://links.example.com", 0, false))), Reason.LABELS);
        }
        assertThatCode(() -> validate(List.of(link(new Text("가".repeat(80), "😀".repeat(40)), "https://links.example.com", 999, false))))
            .doesNotThrowAnyException();
    }

    @Test
    void copyrightIsIndependentlyNullableButRejectsBlankControlsAndOverlongText() {
        assertThatCode(() -> StoreFooterValidator.validate(new StoreFooterContent(new Text(null, null), List.of()), urls)).doesNotThrowAnyException();
        for (String bad : List.of(" ", "a\tb", "a".repeat(201))) {
            error(() -> StoreFooterValidator.validate(new StoreFooterContent(new Text(bad, null), List.of()), urls), Reason.COPYRIGHT);
            error(() -> StoreFooterValidator.validate(new StoreFooterContent(new Text(null, bad), List.of()), urls), Reason.COPYRIGHT);
        }
        assertThatCode(() -> StoreFooterValidator.validate(new StoreFooterContent(new Text("가".repeat(200), "a".repeat(200)), List.of()), urls))
            .doesNotThrowAnyException();
    }

    @Test
    void listOrderVisibilityAndTypeRulesIncludeHiddenRows() {
        var valid = link(new Text("예시", "Example"), "https://links.example.com", 0, true);
        error(() -> validate(null), Reason.LINKS);
        error(() -> validate(Arrays.asList(valid, null)), Reason.LINKS);
        error(() -> validate(IntStream.range(0, 21).mapToObj(i -> link(valid.labels(), valid.url(), i, false)).toList()), Reason.LINKS);
        assertThatCode(() -> validate(IntStream.range(0, 20).mapToObj(i -> link(valid.labels(), valid.url(), i, false)).toList()))
            .doesNotThrowAnyException();
        error(() -> validate(List.of(valid, link(valid.labels(), valid.url(), 0, false))), Reason.ORDER);
        for (Integer order : Arrays.asList(null, -1, 1000)) {
            error(() -> validate(List.of(link(valid.labels(), valid.url(), order, false))), Reason.ORDER);
        }
        error(() -> validate(List.of(link(valid.labels(), valid.url(), 0, null))), Reason.VISIBLE);
        error(() -> validate(List.of(new Link("custom", valid.labels(), valid.url(), 0, true))), Reason.TYPE);
        var mutable = new ArrayList<>(List.of(valid));
        var snapshot = new StoreFooterContent(new Text(null, null), mutable);
        mutable.clear();
        assertThat(snapshot.links()).containsExactly(valid);
        assertThatThrownBy(() -> snapshot.links().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void transitionsRejectStaleEditsAndOldRevisionsButRetryKeepsOriginalPublication() {
        var initial = new StoreFooter(0, null, null);
        assertThat(initial.append(null)).isEqualTo(new StoreFooter(1, null, null));
        error(() -> initial.append(0L), Reason.CONFLICT);
        var current = new StoreFooter(3, 1, 5L);
        assertThat(current.publish(0L, 1)).isSameAs(current);
        error(() -> current.append(null), Reason.CONFLICT);
        error(() -> current.append(4L), Reason.CONFLICT);
        error(() -> current.publish(4L, 3), Reason.CONFLICT);
        error(() -> current.publish(5L, 2), Reason.CONFLICT);
        assertThat(current.publish(5L, 3).publishedRevision()).isEqualTo(3);
        assertThat(current.append(5L)).isEqualTo(new StoreFooter(4, 1, 5L));
        error(() -> new StoreFooter(Integer.MAX_VALUE, null, 0L).append(0L), Reason.CONFLICT);
        var revision = new StoreFooterRevision(1, 1, new StoreFooterContent(new Text(null, null), List.of()), 7,
            Instant.EPOCH, null, null).publish(8, Instant.EPOCH.plusSeconds(1));
        assertThat(revision.publish(9, Instant.EPOCH.plusSeconds(2))).isSameAs(revision);
    }

    private Link link(Text labels, String url, Integer order, Boolean visible) {
        return new Link("CUSTOM", labels, url, order, visible);
    }

    private void validate(List<Link> links) {
        StoreFooterValidator.validate(new StoreFooterContent(new Text(null, null), links), urls);
    }

    private static void error(Runnable action, Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(StoreFooterRuleException.class,
            exception -> assertThat(exception.reason()).isEqualTo(reason));
    }
}
