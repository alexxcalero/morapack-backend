package pe.edu.pucp.morapack.websockets;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

// Configuración WebSocket en Spring
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // WebSocket directo sin SockJS (más simple, evita problemas CORS con /info)
        registry.addEndpoint("/ws-planificacion")
                .setAllowedOrigins(
                        "http://localhost:3000",
                        "https://1inf54-981-5e.inf.pucp.edu.pe",
                        "http://1inf54-981-5e.inf.pucp.edu.pe");

        // Alternativa con SockJS (comentada, si prefieres fallback)
        // registry.addEndpoint("/ws-planificacion-sockjs")
        // .setAllowedOrigins("http://localhost:3000", "...")
        // .withSockJS();
    }
}
