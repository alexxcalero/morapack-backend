package pe.edu.pucp.morapack.websockets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Interceptor para loguear el proceso de handshake WebSocket/STOMP y ayudar a
 * diagnosticar fallos (CORS, proxy, etc.).
 */
public class LoggingHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoggingHandshakeInterceptor.class);

    @Override
    public boolean beforeHandshake(@NonNull ServerHttpRequest request,
            @NonNull ServerHttpResponse response,
            @NonNull WebSocketHandler wsHandler,
            @NonNull Map<String, Object> attributes) throws Exception {
        try {
            String origin = request.getHeaders().getFirst("Origin");
            log.info("[WS-HANDSHAKE] Inicio handshake: URI={} Origin={} Headers={}", request.getURI(), origin,
                    request.getHeaders());
        } catch (Exception e) {
            log.warn("[WS-HANDSHAKE] Error log beforeHandshake", e);
        }
        // Permitir continuar; si necesitas bloquear puedes validar aquí Origin o auth
        return true;
    }

    @Override
    public void afterHandshake(@NonNull ServerHttpRequest request,
            @NonNull ServerHttpResponse response,
            @NonNull WebSocketHandler wsHandler,
            @Nullable Exception exception) {
        if (exception != null) {
            String origin = request.getHeaders().getFirst("Origin");
            log.error("[WS-HANDSHAKE] FALLO: URI={} Origin={} Exception={}", request.getURI(), origin,
                    exception.toString(), exception);
        } else {
            int status = -1;
            if (response instanceof ServletServerHttpResponse servletResp) {
                status = servletResp.getServletResponse().getStatus();
            }
            String origin = request.getHeaders().getFirst("Origin");
            log.info("[WS-HANDSHAKE] Éxito: URI={} Origin={} ResponseHeaders={} Status={}", request.getURI(), origin,
                    response.getHeaders(), status);
        }
    }
}
