package com.commercefoundry.common.web.servlet;

import com.commercefoundry.common.exception.RedactedThrowable;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.DisconnectedClientHelper;

/**
 * 필터 체인의 가장 바깥에서 처리되지 않은 예외를 받아 원래 메시지가 컨테이너 로그에 남지 않게 한다 (#283, {@code coding-conventions.md} §8).
 *
 * <p>필터 · {@code DispatcherServlet} 이 예외를 던지면 Tomcat {@code StandardWrapperValve} 가 에러 디스패치 <b>전에</b>
 * {@code Servlet.service() ... threw exception} 을 원래 예외(메시지 · 원인 사슬)째 ERROR 로 남긴다 — {@link FallbackErrorController} 가
 * 메시지를 버리고 남기는 로그({@link RedactedThrowable}) 밖의 누출이다. 로거 레벨로 끄지 않는다(§8) — 로거 이름이 서블릿 이름에 묶이고 다른 진단까지 사라진다.
 *
 * <ul>
 *   <li><b>응답이 커밋되지 않았으면</b> 예외를 {@link RequestDispatcher#ERROR_EXCEPTION} 에 두고 {@code sendError(500)} 으로 끝낸다.
 *       컨테이너는 상태 기반 에러 디스패치로 {@code /error} 를 부르고, {@link FallbackErrorController} 가 그 속성에서 원인을 찾아 타입 · 스택만 남긴다.
 *       응답은 전과 같은 500 · {@code COMMON_000} 봉투다.</li>
 *   <li><b>이미 커밋됐으면</b> 봉투를 쓸 수 없다. 메시지 없는 사본({@link CommittedResponseFailure}, 원인은 {@link RedactedThrowable})을 <b>다시 던진다</b> —
 *       컨테이너가 응답을 오류로 끝내 연결을 끊으므로 클라이언트가 잘린 본문을 정상 응답으로 받지 않는다. Tomcat 은 그 사본을 남긴다(메시지 없음).</li>
 *   <li><b>클라이언트 연결 끊김</b>({@link DisconnectedClientHelper#isClientDisconnectedException})은 손대지 않고 그대로 던진다 —
 *       Tomcat 이 DEBUG 로만 남기는 정상 소음이고, 메시지는 소켓 오류뿐이다.</li>
 *   <li>{@link VirtualMachineError}(메모리 부족 · 스택 넘침)는 삼키지 않는다.</li>
 *   <li>ERROR · ASYNC 디스패치는 거르지 않는다({@link OncePerRequestFilter} 기본값). 에러 디스패치 중의 예외를 다시 에러 페이지로 보내면 되돌아온다.
 *       ASYNC 디스패치의 예외는 여전히 Tomcat 이 남긴다 — 지금 서비스는 비동기 MVC 를 쓰지 않는다(§8 남은 경로).</li>
 * </ul>
 *
 * <p>순서는 {@link Ordered#HIGHEST_PRECEDENCE} — Spring Security({@code -100}) 등 서비스 필터보다 바깥이다. 같은 순서의 필터(Boot 의 문자 인코딩 필터)와는
 * 앞뒤가 보장되지 않으므로 서비스 필터는 {@code HIGHEST_PRECEDENCE} 를 쓰지 않는다.
 * 컴포넌트 스캔 대상이 아니다. {@link FallbackErrorAutoConfiguration} 이 {@link FallbackErrorController} 와 함께 등록한다.
 */
public class UnhandledFilterExceptionFilter extends OncePerRequestFilter implements Ordered {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable exception) {
            // 검사 예외를 몰래 던지는(sneaky throw) 코드까지 받는다 — 놓치면 그대로 Tomcat 로그로 간다
            handle(request, response, exception);
        }
    }

    private static void handle(HttpServletRequest request, HttpServletResponse response, Throwable exception) throws ServletException, IOException {
        if (DisconnectedClientHelper.isClientDisconnectedException(exception)) {
            rethrow(exception);
        }
        if (response.isCommitted()) {
            throw new CommittedResponseFailure(RedactedThrowable.of(exception));
        }
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, exception);
        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }

    private static void rethrow(Throwable exception) throws ServletException, IOException {
        if (exception instanceof IOException io) {
            throw io;
        }
        if (exception instanceof ServletException servlet) {
            throw servlet;
        }
        if (exception instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (exception instanceof Error error) {
            throw error;
        }
        throw new ServletException(RedactedThrowable.of(exception));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /** 커밋된 응답에서 난 예외의 로그용 사본. 메시지는 고정 문구이고 원인은 타입 · 스택만 남은 {@link RedactedThrowable} 이다. */
    static final class CommittedResponseFailure extends RuntimeException {

        CommittedResponseFailure(RedactedThrowable cause) {
            super("unhandled exception after response committed", cause);
        }
    }
}
