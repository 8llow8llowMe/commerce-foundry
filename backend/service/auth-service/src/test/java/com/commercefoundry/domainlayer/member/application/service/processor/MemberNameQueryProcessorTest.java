package com.commercefoundry.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.application.port.out.query.MemberNameQueryResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberNameQueryProcessorTest {

    @Mock
    private MemberRepositoryPort memberRepositoryPort;

    @InjectMocks
    private MemberNameQueryProcessor processor;

    @Test
    @DisplayName("아이디가 없으면 저장소를 부르지 않는다")
    void emptySkipsRepository() {
        assertThat(processor.findNamesByIds(List.of())).isEmpty();
        verifyNoInteractions(memberRepositoryPort);
    }

    @Test
    @DisplayName("중복이 있어도 이름 조회는 한 번이고, 없는 이름은 null 로 남긴다")
    void looksUpOnce() {
        given(memberRepositoryPort.findNamesByIds(List.of(1L, 2L))).willReturn(List.of(
            new MemberNameQueryResult(1L, "홍길동"),
            new MemberNameQueryResult(2L, null)
        ));

        Map<Long, String> names = processor.findNamesByIds(List.of(1L, 2L, 1L));

        assertThat(names).containsEntry(1L, "홍길동").containsKey(2L);
        assertThat(names.get(2L)).isNull();
        verify(memberRepositoryPort, times(1)).findNamesByIds(List.of(1L, 2L));
    }
}
