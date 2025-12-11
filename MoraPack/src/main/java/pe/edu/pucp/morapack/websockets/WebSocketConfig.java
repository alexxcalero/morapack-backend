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
        // WebSocket nativo con CORS restringido
        registry.addEndpoint("/ws-planificacion")
                .addInterceptors(new LoggingHandshakeInterceptor())
                .setAllowedOrigins(
                        "http://localhost:3000",
                        "https://1inf54-981-5e.inf.pucp.edu.pe",
                        "http://1inf54-981-5e.inf.pucp.edu.pe")
                        .withSockJS();

        // FALLBACK: SockJS (deshabilitado, WebSocket nativo funciona correctamente)
        // Descomentar solo si el proxy falla y se necesita respaldo temporal
        // registry.addEndpoint("/ws-planificacion-sockjs")
        // .addInterceptors(new LoggingHandshakeInterceptor())
        // .setAllowedOrigins(
        // "http://localhost:3000",
        // "https://1inf54-981-5e.inf.pucp.edu.pe",
        // "http://1inf54-981-5e.inf.pucp.edu.pe"
        // )
        // .withSockJS();
    }
}
