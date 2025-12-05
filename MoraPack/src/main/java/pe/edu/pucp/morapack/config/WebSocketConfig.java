// src/main/java/pe/edu/pucp/morapack/config/WebSocketConfig.java
package pe.edu.pucp.morapack.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // El front se suscribirá a destinos tipo /topic/...
        config.enableSimpleBroker("/topic");
        // Prefijo para mensajes que envía el cliente al servidor (si lo necesitas)
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-sim")
                .setAllowedOrigins("http://localhost:3000") // o setAllowedOriginPatterns
                .withSockJS();
    }

}
