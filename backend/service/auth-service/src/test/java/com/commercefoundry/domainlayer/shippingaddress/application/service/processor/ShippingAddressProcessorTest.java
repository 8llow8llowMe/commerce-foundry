package com.commercefoundry.domainlayer.shippingaddress.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.shippingaddress.application.command.ShippingAddressCommand;
import com.commercefoundry.domainlayer.shippingaddress.application.exception.ShippingAddressErrorCode;
import com.commercefoundry.domainlayer.shippingaddress.application.exception.ShippingAddressException;
import com.commercefoundry.domainlayer.shippingaddress.application.info.ShippingAddressListInfo;
import com.commercefoundry.domainlayer.shippingaddress.application.port.out.ShippingAddressRepositoryPort;
import com.commercefoundry.domainlayer.shippingaddress.domain.model.ShippingAddress;
import com.commercefoundry.domainlayer.shippingaddress.domain.model.ShippingAddressContent;
import com.commercefoundry.global.properties.ShippingAddressProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ShippingAddressProcessorTest {

    private static final long MEMBER_ID = 7L;
    private static final long ADDRESS_ID = 100L;
    private static final ShippingAddressCommand COMMAND =
        new ShippingAddressCommand("집", " 홍길동 ", "010-1234-5678", "KR", "06236", "서울특별시 강남구 테헤란로 123", null, null, null);
    private static final ShippingAddressContent CONTENT = COMMAND.toContent();

    @Mock
    private ShippingAddressRepositoryPort shippingAddressRepositoryPort;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    private ShippingAddressProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ShippingAddressProcessor(shippingAddressRepositoryPort, snowflakeIdGenerator, new ShippingAddressProperties(3));
    }

    @Test
    @DisplayName("목록은 저장소 순서(기본 먼저 · 최신순) 그대로 상한과 함께 낸다")
    void list() {
        List<ShippingAddress> addresses = List.of(address(2L, true), address(3L, false));
        given(shippingAddressRepositoryPort.findAllByMemberId(MEMBER_ID)).willReturn(addresses);

        assertThat(processor.getAddresses(MEMBER_ID)).isEqualTo(new ShippingAddressListInfo(addresses, 3));
    }

    @Test
    @DisplayName("주소는 (주소 아이디, 회원 아이디) 로만 찾고, 없거나 남의 것이면 AUTH_020 이다")
    void getNotFound() {
        given(shippingAddressRepositoryPort.findByIdAndMemberId(ADDRESS_ID, MEMBER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> processor.getAddress(MEMBER_ID, ADDRESS_ID))
            .isInstanceOf(ShippingAddressException.class)
            .extracting(exception -> ((ShippingAddressException) exception).getErrorCode())
            .isEqualTo(ShippingAddressErrorCode.NOT_FOUND_SHIPPING_ADDRESS);
    }

    @Test
    @DisplayName("첫 주소는 isDefault=false 여도 기본이 되고, 풀 기존 기본이 없어 해제 UPDATE 를 부르지 않는다")
    void firstAddressBecomesDefault() {
        given(shippingAddressRepositoryPort.countByMemberId(MEMBER_ID)).willReturn(0L);
        given(snowflakeIdGenerator.generateId()).willReturn(ADDRESS_ID);
        given(shippingAddressRepositoryPort.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        ShippingAddress created = processor.create(MEMBER_ID, COMMAND, false);

        assertThat(created).isEqualTo(new ShippingAddress(ADDRESS_ID, MEMBER_ID, CONTENT, true, null));
        assertThat(created.content().recipientName()).isEqualTo("홍길동");
        verify(shippingAddressRepositoryPort, never()).clearDefault(anyLong());
    }

    @Test
    @DisplayName("기본으로 추가하면 기존 기본을 먼저 풀고(유니크 키는 즉시(행 단위) 검사 — deferrable 이 아님) 새 주소를 기본으로 넣는다")
    void createAsDefaultClearsFirst() {
        given(shippingAddressRepositoryPort.countByMemberId(MEMBER_ID)).willReturn(2L);
        given(snowflakeIdGenerator.generateId()).willReturn(ADDRESS_ID);
        given(shippingAddressRepositoryPort.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        ShippingAddress created = processor.create(MEMBER_ID, COMMAND, true);

        assertThat(created.defaultAddress()).isTrue();
        InOrder order = inOrder(shippingAddressRepositoryPort);
        order.verify(shippingAddressRepositoryPort).clearDefault(MEMBER_ID);
        order.verify(shippingAddressRepositoryPort).save(created);
    }

    @Test
    @DisplayName("주소가 있으면 isDefault 없이 추가한 주소는 기본이 아니고 기존 기본을 건드리지 않는다")
    void createNonDefault() {
        given(shippingAddressRepositoryPort.countByMemberId(MEMBER_ID)).willReturn(1L);
        given(snowflakeIdGenerator.generateId()).willReturn(ADDRESS_ID);
        given(shippingAddressRepositoryPort.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        assertThat(processor.create(MEMBER_ID, COMMAND, false).defaultAddress()).isFalse();
        verify(shippingAddressRepositoryPort, never()).clearDefault(anyLong());
    }

    @Test
    @DisplayName("상한에 닿았으면 409 · AUTH_021 이고 메시지에 상한을 싣는다 — 아무것도 쓰지 않는다")
    void limitExceeded() {
        given(shippingAddressRepositoryPort.countByMemberId(MEMBER_ID)).willReturn(3L);

        assertThatThrownBy(() -> processor.create(MEMBER_ID, COMMAND, true))
            .isInstanceOf(ShippingAddressException.class)
            .hasMessage("배송지는 최대 3개까지 저장할 수 있습니다.")
            .extracting(exception -> ((ShippingAddressException) exception).getErrorCode())
            .isEqualTo(ShippingAddressErrorCode.SHIPPING_ADDRESS_LIMIT_EXCEEDED);
        verify(shippingAddressRepositoryPort, never()).save(any());
        verify(shippingAddressRepositoryPort, never()).clearDefault(anyLong());
    }

    @Test
    @DisplayName("전체 교체는 읽은 주소의 아이디 · 기본 여부 · 버전을 지키고 내용만 바꿔 저장한다")
    void update() {
        ShippingAddress stored = ShippingAddress.restore(ADDRESS_ID, MEMBER_ID, CONTENT, true, 4L);
        ShippingAddressCommand command = new ShippingAddressCommand(null, "John", "+14155550100", "US", null, "1 Market St", null, "SF", "CA");
        given(shippingAddressRepositoryPort.findByIdAndMemberId(ADDRESS_ID, MEMBER_ID)).willReturn(Optional.of(stored));
        given(shippingAddressRepositoryPort.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        ShippingAddress updated = processor.update(MEMBER_ID, ADDRESS_ID, command);

        assertThat(updated).isEqualTo(new ShippingAddress(ADDRESS_ID, MEMBER_ID, command.toContent(), true, 4L));
    }

    @Test
    @DisplayName("전체 교체도 남의 주소면 AUTH_020 이고 저장하지 않는다")
    void updateNotFound() {
        given(shippingAddressRepositoryPort.findByIdAndMemberId(ADDRESS_ID, MEMBER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> processor.update(MEMBER_ID, ADDRESS_ID, COMMAND)).isInstanceOf(ShippingAddressException.class);
        verify(shippingAddressRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("기본 지정은 기존 기본 해제 → 새 기본 지정 순서다")
    void setDefaultOrder() {
        given(shippingAddressRepositoryPort.findByIdAndMemberId(ADDRESS_ID, MEMBER_ID)).willReturn(Optional.of(address(ADDRESS_ID, false)));
        given(shippingAddressRepositoryPort.markDefault(ADDRESS_ID, MEMBER_ID)).willReturn(true);

        processor.setDefault(MEMBER_ID, ADDRESS_ID);

        InOrder order = inOrder(shippingAddressRepositoryPort);
        order.verify(shippingAddressRepositoryPort).clearDefault(MEMBER_ID);
        order.verify(shippingAddressRepositoryPort).markDefault(ADDRESS_ID, MEMBER_ID);
    }

    @Test
    @DisplayName("지정 UPDATE 가 바꾼 행이 없으면(지워졌다) AUTH_020 이다 — 404 판정은 Processor 가 한다")
    void setDefaultNoRowUpdated() {
        given(shippingAddressRepositoryPort.findByIdAndMemberId(ADDRESS_ID, MEMBER_ID)).willReturn(Optional.of(address(ADDRESS_ID, false)));
        given(shippingAddressRepositoryPort.markDefault(ADDRESS_ID, MEMBER_ID)).willReturn(false);

        assertThatThrownBy(() -> processor.setDefault(MEMBER_ID, ADDRESS_ID))
            .isInstanceOf(ShippingAddressException.class)
            .extracting(exception -> ((ShippingAddressException) exception).getErrorCode())
            .isEqualTo(ShippingAddressErrorCode.NOT_FOUND_SHIPPING_ADDRESS);
    }

    @Test
    @DisplayName("도메인 규칙 위반(요청 검증을 비켜 온 값)은 500 이 아니라 400 · AUTH_100 이고 쓰지 않는다 — 생성 · 전체 교체 모두")
    void domainRuleViolationBecomesBadRequest() {
        ShippingAddressCommand invalid = new ShippingAddressCommand(null, "　", "01012345678", "KR", "06236", "주소", null, null, null);

        assertThatThrownBy(() -> processor.create(MEMBER_ID, invalid, false))
            .isInstanceOf(ShippingAddressException.class)
            .hasMessage("요청 값이 올바르지 않습니다.")
            .hasCauseInstanceOf(IllegalArgumentException.class)
            .extracting(exception -> ((ShippingAddressException) exception).getErrorCode())
            .isEqualTo(ShippingAddressErrorCode.INVALID_SHIPPING_ADDRESS);
        assertThatThrownBy(() -> processor.update(MEMBER_ID, ADDRESS_ID, invalid))
            .isInstanceOf(ShippingAddressException.class)
            .extracting(exception -> ((ShippingAddressException) exception).getErrorCode())
            .isEqualTo(ShippingAddressErrorCode.INVALID_SHIPPING_ADDRESS);
        verify(shippingAddressRepositoryPort, never()).save(any());
        verify(shippingAddressRepositoryPort, never()).countByMemberId(anyLong());
    }

    @Test
    @DisplayName("이미 기본이면 아무것도 쓰지 않는다 (멱등)")
    void setDefaultIdempotent() {
        given(shippingAddressRepositoryPort.findByIdAndMemberId(ADDRESS_ID, MEMBER_ID)).willReturn(Optional.of(address(ADDRESS_ID, true)));

        processor.setDefault(MEMBER_ID, ADDRESS_ID);

        verify(shippingAddressRepositoryPort, never()).clearDefault(anyLong());
        verify(shippingAddressRepositoryPort, never()).markDefault(anyLong(), anyLong());
    }

    @Test
    @DisplayName("남의 주소를 기본으로 지정하면 AUTH_020 이고 기존 기본을 풀지 않는다")
    void setDefaultNotFound() {
        given(shippingAddressRepositoryPort.findByIdAndMemberId(ADDRESS_ID, MEMBER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> processor.setDefault(MEMBER_ID, ADDRESS_ID)).isInstanceOf(ShippingAddressException.class);
        verify(shippingAddressRepositoryPort, never()).clearDefault(anyLong());
    }

    @Test
    @DisplayName("기본 배송지를 지우면 남은 주소 중 가장 최근 것을 기본으로 올린다")
    void deleteDefaultPromotesLatest() {
        given(shippingAddressRepositoryPort.findByIdAndMemberId(ADDRESS_ID, MEMBER_ID)).willReturn(Optional.of(address(ADDRESS_ID, true)));
        given(shippingAddressRepositoryPort.deleteByIdAndMemberId(ADDRESS_ID, MEMBER_ID)).willReturn(true);
        given(shippingAddressRepositoryPort.findLatestByMemberId(MEMBER_ID)).willReturn(Optional.of(address(300L, false)));
        given(shippingAddressRepositoryPort.markDefault(300L, MEMBER_ID)).willReturn(true);

        processor.delete(MEMBER_ID, ADDRESS_ID);

        InOrder order = inOrder(shippingAddressRepositoryPort);
        order.verify(shippingAddressRepositoryPort).deleteByIdAndMemberId(ADDRESS_ID, MEMBER_ID);
        order.verify(shippingAddressRepositoryPort).markDefault(300L, MEMBER_ID);
    }

    @Test
    @DisplayName("승격할 주소가 그 사이 사라졌으면 AUTH_022 다 — 불변식을 깬 채 커밋하지 않는다")
    void promotionTargetVanished() {
        given(shippingAddressRepositoryPort.findByIdAndMemberId(ADDRESS_ID, MEMBER_ID)).willReturn(Optional.of(address(ADDRESS_ID, true)));
        given(shippingAddressRepositoryPort.deleteByIdAndMemberId(ADDRESS_ID, MEMBER_ID)).willReturn(true);
        given(shippingAddressRepositoryPort.findLatestByMemberId(MEMBER_ID)).willReturn(Optional.of(address(300L, false)));
        given(shippingAddressRepositoryPort.markDefault(300L, MEMBER_ID)).willReturn(false);

        assertThatThrownBy(() -> processor.delete(MEMBER_ID, ADDRESS_ID))
            .isInstanceOf(ShippingAddressException.class)
            .extracting(exception -> ((ShippingAddressException) exception).getErrorCode())
            .isEqualTo(ShippingAddressErrorCode.SHIPPING_ADDRESS_UPDATE_CONFLICT);
    }

    @Test
    @DisplayName("마지막 기본 배송지를 지우면 올릴 주소가 없다")
    void deleteLastAddress() {
        given(shippingAddressRepositoryPort.findByIdAndMemberId(ADDRESS_ID, MEMBER_ID)).willReturn(Optional.of(address(ADDRESS_ID, true)));
        given(shippingAddressRepositoryPort.deleteByIdAndMemberId(ADDRESS_ID, MEMBER_ID)).willReturn(true);
        given(shippingAddressRepositoryPort.findLatestByMemberId(MEMBER_ID)).willReturn(Optional.empty());

        processor.delete(MEMBER_ID, ADDRESS_ID);

        verify(shippingAddressRepositoryPort, never()).markDefault(anyLong(), anyLong());
    }

    @Test
    @DisplayName("기본이 아닌 주소를 지우면 기본을 건드리지 않는다")
    void deleteNonDefault() {
        given(shippingAddressRepositoryPort.findByIdAndMemberId(ADDRESS_ID, MEMBER_ID)).willReturn(Optional.of(address(ADDRESS_ID, false)));
        given(shippingAddressRepositoryPort.deleteByIdAndMemberId(ADDRESS_ID, MEMBER_ID)).willReturn(true);

        processor.delete(MEMBER_ID, ADDRESS_ID);

        verify(shippingAddressRepositoryPort, never()).findLatestByMemberId(anyLong());
        verify(shippingAddressRepositoryPort, never()).markDefault(anyLong(), anyLong());
    }

    @Test
    @DisplayName("남의 주소를 지우면 AUTH_020 이고 지우지 않는다")
    void deleteNotFound() {
        given(shippingAddressRepositoryPort.findByIdAndMemberId(ADDRESS_ID, MEMBER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> processor.delete(MEMBER_ID, ADDRESS_ID)).isInstanceOf(ShippingAddressException.class);
        verify(shippingAddressRepositoryPort, never()).deleteByIdAndMemberId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("저장할 새 주소의 회원 아이디는 인자(JWT)의 것이다")
    void createUsesMemberIdFromArgument() {
        given(shippingAddressRepositoryPort.countByMemberId(MEMBER_ID)).willReturn(0L);
        given(snowflakeIdGenerator.generateId()).willReturn(ADDRESS_ID);
        ArgumentCaptor<ShippingAddress> captor = ArgumentCaptor.forClass(ShippingAddress.class);
        given(shippingAddressRepositoryPort.save(captor.capture())).willAnswer(invocation -> invocation.getArgument(0));

        processor.create(MEMBER_ID, COMMAND, false);

        assertThat(captor.getValue().memberId()).isEqualTo(MEMBER_ID);
    }

    private static ShippingAddress address(long id, boolean defaultAddress) {
        return ShippingAddress.restore(id, MEMBER_ID, CONTENT, defaultAddress, 0L);
    }
}
