package pe.edu.pucp.morapack.websockets;

import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

// Configuración WebSocket en Spring
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(@NonNull MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(@NonNull StompEndpointRegistry registry) {
        // WebSocket nativo (requiere configuración de proxy para Upgrade)
        // Para diagnóstico temporal: usar patrones en lugar de origins exactos.
        // IMPORTANTE: Revertir a lista específica cuando se solucione el proxy.
        registry.addEndpoint("/ws-planificacion")
                .addInterceptors(new LoggingHandshakeInterceptor())
                .setAllowedOriginPatterns("*");

        // FALLBACK TEMPORAL: SockJS para funcionar mientras se configura el proxy
        // SockJS no requiere WebSocket Upgrade y funciona sobre HTTP normal
        registry.addEndpoint("/ws-planificacion-sockjs")
                .addInterceptors(new LoggingHandshakeInterceptor())
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}
