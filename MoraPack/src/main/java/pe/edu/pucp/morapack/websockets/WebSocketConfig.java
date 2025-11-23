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
        // Definimos orígenes explícitos para evitar problemas CORS en el handshake
        // SockJS /info
        registry.addEndpoint("/ws-planificacion")
                .setAllowedOrigins(
                        "http://localhost:3000",
                        "https://1inf54-981-5e.inf.pucp.edu.pe",
                        "http://1inf54-981-5e.inf.pucp.edu.pe")
                .withSockJS();
    }
}
