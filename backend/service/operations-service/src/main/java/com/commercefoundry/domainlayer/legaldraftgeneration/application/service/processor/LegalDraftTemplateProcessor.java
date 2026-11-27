package com.commercefoundry.domainlayer.legaldraftgeneration.application.service.processor;

import com.commercefoundry.domainlayer.legaldocument.application.info.LegalDocumentInfos.Template;
import com.commercefoundry.domainlayer.legaldocument.application.service.processor.LegalDocumentTemplateProcessor;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.exception.LegalDraftErrorCode;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.exception.LegalDraftException;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.info.LegalDraftInfo.Candidate;
import com.commercefoundry.domainlayer.legaldraftgeneration.domain.model.LegalDraftInput;
import com.commercefoundry.domainlayer.legaldraftgeneration.domain.model.LegalDraftPlan;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LegalDraftTemplateProcessor {
    private static final String COMPANY = "COMPANY_NAME";
    private static final Pattern SECTION = Pattern.compile("^\\d+\\. (.+)\\n\\{\\{([A-Z_]+)}}: (.+)$");
    private final LegalDocumentTemplateProcessor templates;

    public Outline prepare(LegalDraftInput input) {
        Template template = templates.get(input.document());
        if (template.version() != input.templateVersion()) {
            throw new LegalDraftException(LegalDraftErrorCode.TEMPLATE_CONFLICT);
        }
        // #569 템플릿의 버전별 절과 질문을 서버에서 읽는다. 형식이 달라지면 새 버전의 파서를 추가한다.
        String[] blocks = template.body().strip().split("\\n\\n", 2);
        var sections = new LinkedHashMap<String, Section>();
        if (blocks.length != 2 || !blocks[0].startsWith("{{" + COMPANY + "}}")) {
            throw new IllegalStateException("Unsupported legal template layout");
        }
        String[] lines = blocks[1].split("\\n");
        for (int index = 0; index < lines.length; index += 2) {
            if (index + 1 == lines.length) {
                throw new IllegalStateException("Incomplete legal template section");
            }
            var matcher = SECTION.matcher(lines[index] + "\n" + lines[index + 1]);
            if (!matcher.matches() || sections.putIfAbsent(matcher.group(2), new Section(matcher.group(1), matcher.group(3))) != null) {
                throw new IllegalStateException("Invalid legal template section");
            }
        }
        for (String fact : input.confirmedFacts().keySet()) {
            if (!COMPANY.equals(fact) && !sections.containsKey(fact)) {
                throw new LegalDraftException(LegalDraftErrorCode.INVALID_REQUEST);
            }
        }
        return new Outline(template, blocks[0], Map.copyOf(sections), List.copyOf(sections.keySet()));
    }

    public Candidate assemble(LegalDraftInput input, Outline outline, LegalDraftPlan plan) {
        List<String> order = plan == null ? null : plan.sectionOrder();
        if (order == null || order.size() != outline.sectionIds().size() || !new HashSet<>(order).equals(outline.sections().keySet())) {
            throw new IllegalArgumentException("Invalid generated legal draft plan");
        }
        var facts = input.confirmedFacts();
        var unresolved = new ArrayList<String>();
        if (!facts.containsKey(COMPANY)) {
            unresolved.add(COMPANY);
        }
        String heading = outline.heading().replace(placeholder(COMPANY), facts.getOrDefault(COMPANY, placeholder(COMPANY)));
        var body = new StringBuilder(heading);
        int index = 0;
        for (String key : order) {
            Section section = outline.sections().get(key);
            String fact = facts.get(key);
            if (fact == null) {
                unresolved.add(key);
                fact = placeholder(key) + ": " + section.question();
            }
            body.append("\n\n").append(++index).append(". ").append(section.title()).append("\n").append(fact);
        }
        Template template = outline.template();
        return new Candidate(template.type(), template.locale(), template.title(), body.toString(), template.templateId(),
            template.version(), List.copyOf(unresolved), template.checklist());
    }

    private static String placeholder(String key) {
        return "{{" + key + "}}";
    }

    public record Outline(Template template, String heading, Map<String, Section> sections, List<String> sectionIds) {}

    public record Section(String title, String question) {}
}
