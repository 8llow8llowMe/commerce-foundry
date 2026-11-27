package com.commercefoundry.domainlayer.storenavigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.storenavigation.application.exception.StoreNavigationErrorCode;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigation;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationContent;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationRevision;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationContent.Item;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationContent.PublicNode;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationContent.Text;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationRuleException;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationRuleException.Reason;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationUrlPolicy;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationValidator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class StoreNavigationDomainTest {
    private final StoreNavigationUrlPolicy urls = new StoreNavigationUrlPolicy(Set.of("links.example.com"));

    private static Item internal(String key, String parent, int order, boolean visible) {
        return new Item(key, parent, "INTERNAL", new Text("국문-" + key, "en-" + key), "/" + key, null, order, visible);
    }

    private void validate(List<Item> items) {
        StoreNavigationValidator.validate(new StoreNavigationContent(items), urls);
    }

    private void rejects(List<Item> items, Reason reason) {
        assertThatThrownBy(() -> validate(items))
            .isInstanceOfSatisfying(StoreNavigationRuleException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }

    @Test
    void publicTreeIsLocalizedOrderedAndHidesHiddenSubtrees() {
        List<Item> items = List.of(
            internal("b", null, 2, true),
            internal("a", null, 1, true),
            internal("a-2", "a", 2, true),
            internal("a-1", "a", 1, true),
            internal("hidden", null, 3, false),
            internal("hidden-child", "hidden", 1, true),
            new Item("cat", "a", "CATEGORY", new Text("치마", "Skirts"), null, 42L, 3, true));
        validate(items);

        List<PublicNode> ko = new StoreNavigationContent(items).publicTree("ko");
        assertThat(ko).extracting(PublicNode::label).containsExactly("국문-a", "국문-b");
        assertThat(ko.get(0).children()).extracting(PublicNode::label).containsExactly("국문-a-1", "국문-a-2", "치마");
        assertThat(ko.get(0).children().get(2).categoryId()).isEqualTo(42L);

        List<PublicNode> en = new StoreNavigationContent(items).publicTree("en");
        assertThat(en.get(0).children()).extracting(PublicNode::label).containsExactly("en-a-1", "en-a-2", "Skirts");
    }

    @Test
    void orderedListsParentsBeforeChildrenWithSiblingsByDisplayOrderIncludingHidden() {
        List<Item> items = List.of(
            internal("b", null, 2, true),
            internal("a-2", "a", 2, true),
            internal("hidden-child", "hidden", 1, true),
            internal("a", null, 1, true),
            internal("hidden", null, 3, false),
            internal("a-1", "a", 1, true));
        validate(items);

        assertThat(new StoreNavigationContent(items).ordered().items()).extracting(Item::key)
            .containsExactly("a", "a-1", "a-2", "b", "hidden", "hidden-child");
    }

    @Test
    void emptyMenuIsValid() {
        assertThatCode(() -> validate(List.of())).doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidTrees() {
        rejects(null, Reason.ITEMS);
        rejects(List.of(internal("a", null, 1, true), internal("a", null, 2, true)), Reason.KEY);
        rejects(List.of(internal("A", null, 1, true)), Reason.KEY);
        rejects(List.of(internal("a", "missing", 1, true)), Reason.PARENT);
        rejects(List.of(internal("a", "a", 1, true)), Reason.PARENT);
        rejects(List.of(internal("a", "b", 1, true), internal("b", "a", 1, true)), Reason.CYCLE);
        rejects(List.of(internal("a", null, 1, true), internal("b", "a", 1, true), internal("c", "b", 1, true),
            internal("d", "c", 1, true)), Reason.DEPTH);
        rejects(List.of(internal("a", null, 1, true), internal("b", null, 1, false)), Reason.ORDER);
        rejects(List.of(internal("a", null, -1, true)), Reason.ORDER);
        rejects(List.of(new Item("a", null, "INTERNAL", new Text("a", "a"), "/a", null, 1, null)), Reason.VISIBLE);
        rejects(List.of(new Item("a", null, "MAILTO", new Text("a", "a"), "/a", null, 1, true)), Reason.TYPE);
        rejects(List.of(new Item("a", null, "INTERNAL", new Text(" ", "a"), "/a", null, 1, true)), Reason.LABELS);
        rejects(List.of(new Item("a", null, "INTERNAL", null, "/a", null, 1, true)), Reason.LABELS);
    }

    @Test
    void sameOrderUnderDifferentParentsIsAllowed() {
        assertThatCode(() -> validate(List.of(internal("a", null, 1, true), internal("b", null, 2, true),
            internal("a-1", "a", 1, true), internal("b-1", "b", 1, true)))).doesNotThrowAnyException();
    }

    @Test
    void acceptsExactUpperBounds() {
        assertThatCode(() -> validate(List.of(internal("a", null, 1, true), internal("b", "a", 1, true), internal("c", "b", 1, true))))
            .doesNotThrowAnyException();

        List<Item> maxRoots = IntStream.range(0, StoreNavigationValidator.MAX_CHILDREN)
            .mapToObj(i -> internal("r" + i, null, i, true)).toList();
        assertThat(maxRoots).hasSize(20);
        assertThatCode(() -> validate(maxRoots)).doesNotThrowAnyException();

        List<Item> maxItems = new ArrayList<>();
        for (int root = 0; root < 3; root++) {
            maxItems.add(internal("r" + root, null, root, true));
            for (int child = 0; child < 19; child++) {
                maxItems.add(internal("r" + root + "-" + child, "r" + root, child, true));
            }
        }
        assertThat(maxItems).hasSize(StoreNavigationValidator.MAX_ITEMS);
        assertThatCode(() -> validate(maxItems)).doesNotThrowAnyException();
    }

    @Test
    void enforcesCountLimits() {
        List<Item> tooManyRoots = IntStream.range(0, StoreNavigationValidator.MAX_CHILDREN + 1)
            .mapToObj(i -> internal("r" + i, null, i, true)).toList();
        rejects(tooManyRoots, Reason.ITEMS);

        List<Item> tooMany = new ArrayList<>();
        for (int root = 0; root < 4; root++) {
            tooMany.add(internal("r" + root, null, root, true));
            for (int child = 0; child < 15; child++) {
                tooMany.add(internal("r" + root + "-" + child, "r" + root, child, true));
            }
        }
        assertThat(tooMany).hasSizeGreaterThan(StoreNavigationValidator.MAX_ITEMS);
        rejects(tooMany, Reason.ITEMS);
    }

    @Test
    void categoryAndNoneItemsCarryNoUrl() {
        rejects(List.of(new Item("c", null, "CATEGORY", new Text("c", "c"), "/c", 1L, 1, true)), Reason.URL);
        rejects(List.of(new Item("c", null, "CATEGORY", new Text("c", "c"), null, null, 1, true)), Reason.CATEGORY);
        rejects(List.of(new Item("c", null, "CATEGORY", new Text("c", "c"), null, 0L, 1, true)), Reason.CATEGORY);
        rejects(List.of(new Item("c", null, "CATEGORY", new Text("c", "c"), null, -1L, 1, true)), Reason.CATEGORY);
        assertThatCode(() -> validate(List.of(new Item("c", null, "CATEGORY", new Text("c", "c"), null, Long.MAX_VALUE, 1, true))))
            .doesNotThrowAnyException();
        rejects(List.of(new Item("c", null, "INTERNAL", new Text("c", "c"), "/c", 1L, 1, true)), Reason.CATEGORY);
        rejects(List.of(new Item("n", null, "NONE", new Text("n", "n"), "/n", null, 1, true)), Reason.URL);
        assertThatCode(() -> validate(List.of(new Item("n", null, "NONE", new Text("n", "n"), null, null, 1, true))))
            .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/products", "/collections/new-arrivals", "/a/b_c.d~e"})
    void acceptsInternalPaths(String path) {
        assertThatCode(() -> urls.validateInternal(path)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "products", "//evil.example/a", "/\\evil.example", "https://links.example.com/a", "javascript:alert(1)",
        "/a/../b", "/./a", "/a?next=//evil.example", "/a#x", "/%2f%2fevil.example", "/a b", "/한글"
    })
    void rejectsInternalPathBypasses(String path) {
        assertThatThrownBy(() -> urls.validateInternal(path)).isInstanceOf(StoreNavigationRuleException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://links.example.com", "HTTPS://LINKS.EXAMPLE.COM/help/about"})
    void acceptsAllowedExternalUrls(String url) {
        assertThatCode(() -> urls.validateExternal(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "http://links.example.com/a", "javascript:alert(1)", "/relative", "//links.example.com/a",
        "https://links.example.com.evil.example/a", "https://sub.links.example.com/a",
        "https://links.example.com@other.example/a", "https://other.example@links.example.com/a",
        "https://links.example.com:443/a", "https://links.example.com/a?x=1", "https://links.example.com/a#f",
        "https://links.example.com/%2e%2e/a", "https://links.example.com/../a", "https://links.example.com\\@other.example/a"
    })
    void rejectsExternalUrlBypasses(String url) {
        assertThatThrownBy(() -> urls.validateExternal(url)).isInstanceOf(StoreNavigationRuleException.class);
    }

    @Test
    void rootAppendsAndPublishesOnlyLatestWithCurrentVersion() {
        StoreNavigation root = new StoreNavigation(2, 1, 5L);
        assertThat(root.append(5L)).isEqualTo(new StoreNavigation(3, 1, 5L));
        assertThat(root.publish(5L, 2)).isEqualTo(new StoreNavigation(2, 2, 5L));
        assertThat(root.publish(1L, 1)).isSameAs(root);
        assertThatThrownBy(() -> root.append(4L)).isInstanceOfSatisfying(StoreNavigationRuleException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.CONFLICT));
        assertThatThrownBy(() -> root.publish(4L, 2)).isInstanceOf(StoreNavigationRuleException.class);
        assertThatThrownBy(() -> new StoreNavigation(3, 1, 5L).publish(5L, 2)).isInstanceOf(StoreNavigationRuleException.class);
    }

    @Test
    void revisionKeepsFirstPublication() {
        Instant first = Instant.parse("2026-11-26T00:00:00Z");
        StoreNavigationRevision published = new StoreNavigationRevision(1, 1, new StoreNavigationContent(List.of()), 7, first,
            null, null).publish(8, first);
        assertThat(published.publish(9, first.plusSeconds(60))).isEqualTo(published);
        assertThat(published.publishedBy()).isEqualTo(8L);
    }

    @Test
    void rejectsInvalidAllowedHostConfiguration() {
        assertThatThrownBy(() -> new StoreNavigationUrlPolicy(Set.of("localhost")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(Reason.class)
    void everyRuleReasonHasMatchingErrorCodeForProcessorTranslation(Reason reason) {
        // Processor 는 StoreNavigationErrorCode.valueOf(reason.name()) 로 바꾼다. 이름이 어긋나면 400·409 대신 500 이 난다.
        var code = StoreNavigationErrorCode.valueOf(reason.name());
        assertThat(code.getHttpStatus()).isEqualTo(reason == Reason.CONFLICT ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST);
    }

    private static Item placed(String key, String parent, int order, Text descriptions, boolean visible, boolean primary, boolean shortcut) {
        return new Item(key, parent, "NONE", new Text("국문-" + key, "en-" + key), descriptions, null, null, order, visible, primary, shortcut);
    }

    @Test
    void descriptionsAreOptionalButBothLanguagesWhenPresent() {
        assertThatCode(() -> validate(List.of(placed("a", null, 1, new Text("전체 상품", "All products"), true, false, false))))
            .doesNotThrowAnyException();
        assertThatCode(() -> validate(List.of(placed("a", null, 1, new Text("가".repeat(80), "a".repeat(80)), true, false, false))))
            .doesNotThrowAnyException();
        rejects(List.of(placed("a", null, 1, new Text("전체 상품", null), true, false, false)), Reason.DESCRIPTION);
        rejects(List.of(placed("a", null, 1, new Text(null, "All"), true, false, false)), Reason.DESCRIPTION);
        rejects(List.of(placed("a", null, 1, new Text(" ", "All"), true, false, false)), Reason.DESCRIPTION);
        rejects(List.of(placed("a", null, 1, new Text("가".repeat(81), "All"), true, false, false)), Reason.DESCRIPTION);
        rejects(List.of(placed("a", null, 1, new Text("전체\n상품", "All"), true, false, false)), Reason.DESCRIPTION);
        // 숨김 항목도 같은 규칙이다.
        rejects(List.of(placed("a", null, 1, new Text("전체", "a".repeat(81)), false, false, false)), Reason.DESCRIPTION);
    }

    @Test
    void primaryAndShortcutOnlyOnTopLevelItemsIncludingHidden() {
        assertThatCode(() -> validate(List.of(placed("a", null, 1, null, true, true, true), placed("b", null, 2, null, false, true, false))))
            .doesNotThrowAnyException();
        rejects(List.of(placed("a", null, 1, null, true, false, false), placed("b", "a", 1, null, true, true, false)), Reason.PLACEMENT);
        rejects(List.of(placed("a", null, 1, null, true, false, false), placed("b", "a", 1, null, true, false, true)), Reason.PLACEMENT);
        rejects(List.of(placed("a", null, 1, null, false, false, false), placed("b", "a", 1, null, false, true, true)), Reason.PLACEMENT);
    }

    @Test
    void publicTreeCarriesLocalizedDescriptionAndPlacementFlagsAndDropsHiddenFlags() {
        List<Item> items = List.of(
            placed("shop", null, 1, new Text("전체 상품 / 카테고리", "All products / Categories"), true, true, false),
            placed("sale", null, 2, null, true, false, true),
            placed("hidden", null, 3, new Text("숨김 설명", "Hidden description"), false, true, true),
            new Item("shop-new", "shop", "INTERNAL", new Text("신상", "New"), "/collections/new", null, 1, true));
        validate(items);

        List<PublicNode> ko = new StoreNavigationContent(items).publicTree("ko");
        assertThat(ko).extracting(PublicNode::label).containsExactly("국문-shop", "국문-sale");
        assertThat(ko.get(0).description()).isEqualTo("전체 상품 / 카테고리");
        assertThat(ko.get(0).primary()).isTrue();
        assertThat(ko.get(0).shortcut()).isFalse();
        assertThat(ko.get(1).description()).isNull();
        assertThat(ko.get(1).shortcut()).isTrue();
        assertThat(ko.get(0).children()).singleElement().satisfies(child -> {
            assertThat(child.description()).isNull();
            assertThat(child.primary()).isFalse();
            assertThat(child.shortcut()).isFalse();
        });
        assertThat(new StoreNavigationContent(items).publicTree("en").get(0).description()).isEqualTo("All products / Categories");
    }
}
