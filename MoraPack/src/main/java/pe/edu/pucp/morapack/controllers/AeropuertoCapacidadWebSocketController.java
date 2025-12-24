package pe.edu.pucp.morapack.controllers;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import pe.edu.pucp.morapack.services.AeropuertoService;
import pe.edu.pucp.morapack.services.EnvioService;
import pe.edu.pucp.morapack.services.LiberacionCapacidadService;

import java.util.Map;

/**
 * Controlador WebSocket para recibir notificaciones de despegues y aterrizajes
 * desde el frontend y actualizar las capacidades de los aeropuertos.
 */
@Controller
@RequiredArgsConstructor
public class AeropuertoCapacidadWebSocketController {

    private static final Logger logger = LoggerFactory.getLogger(AeropuertoCapacidadWebSocketController.class);

    private final AeropuertoService aeropuertoService;
    private final SimpMessagingTemplate messagingTemplate;
    private final LiberacionCapacidadService liberacionCapacidadService;
    private final EnvioService envioService;

    /**
     * Maneja notificaciones de despegue de vuelos.
     * Cuando un vuelo despega con carga, se disminuye la capacidad ocupada del aeropuerto de origen.
     *
     * Destino WebSocket: /app/aeropuerto/despegue
     *
     * Payload esperado:
     * {
     *   "tipo": "despegue",
     *   "aeropuertoId": 123,
     *   "vueloId": 456,
     *   "capacidadOcupada": 50,
     *   "timestamp": "2025-01-20T10:30:00Z"
     * }
     */
    @MessageMapping("/aeropuerto/despegue")
    public void manejarDespegue(@Payload Map<String, Object> payload) {
        try {
            logger.info("🛫 Recibida notificación de despegue: {}", payload);

            // Validar que el payload tenga los campos necesarios
            if (!payload.containsKey("aeropuertoId") || !payload.containsKey("capacidadOcupada")) {
                logger.warn("⚠️ Payload de despegue incompleto: {}", payload);
                return;
            }

            Integer aeropuertoId = getIntegerValue(payload.get("aeropuertoId"));
            Integer vueloId = getIntegerValue(payload.get("vueloId"));
            Integer capacidadOcupada = getIntegerValue(payload.get("capacidadOcupada"));

            if (aeropuertoId == null || capacidadOcupada == null || capacidadOcupada <= 0) {
                logger.warn("⚠️ Datos inválidos en despegue: aeropuertoId={}, capacidadOcupada={}",
                    aeropuertoId, capacidadOcupada);
                return;
            }

            // Disminuir la capacidad ocupada del aeropuerto
            boolean exito = aeropuertoService.disminuirCapacidadOcupada(aeropuertoId, capacidadOcupada);

            if (exito) {
                logger.info("✅ Capacidad disminuida en aeropuerto {}: -{} unidades (vuelo {})",
                    aeropuertoId, capacidadOcupada, vueloId);

                // ⚡ Actualizar estados de envíos cuando el vuelo despega
                if (vueloId != null) {
                    envioService.actualizarEstadosPorDespegue(vueloId);
                }

                // Opcional: Notificar a otros clientes conectados sobre el cambio
                Map<String, Object> notificacion = Map.of(
                    "tipo", "capacidad_actualizada",
                    "aeropuertoId", aeropuertoId,
                    "evento", "despegue",
                    "vueloId", vueloId != null ? vueloId : 0,
                    "capacidadCambio", -capacidadOcupada,
                    "timestamp", payload.getOrDefault("timestamp", System.currentTimeMillis())
                );
                messagingTemplate.convertAndSend("/topic/aeropuertos", notificacion);
            } else {
                logger.error("❌ No se pudo disminuir la capacidad del aeropuerto {}", aeropuertoId);
            }

        } catch (Exception e) {
            logger.error("❌ Error al procesar notificación de despegue: {}", e.getMessage(), e);
        }
    }

    /**
     * Maneja notificaciones de aterrizaje de vuelos.
     * Cuando un vuelo aterriza con carga, se aumenta la capacidad ocupada del aeropuerto de destino.
     *
     * Destino WebSocket: /app/aeropuerto/aterrizaje
     *
     * Payload esperado:
     * {
     *   "tipo": "aterrizaje",
     *   "aeropuertoId": 123,
     *   "vueloId": 456,
     *   "capacidadOcupada": 50,
     *   "timestamp": "2025-01-20T10:30:00Z"
     * }
     */
    @MessageMapping("/aeropuerto/aterrizaje")
    public void manejarAterrizaje(@Payload Map<String, Object> payload) {
        try {
            logger.info("🛬 Recibida notificación de aterrizaje: {}", payload);

            // Validar que el payload tenga los campos necesarios
            if (!payload.containsKey("aeropuertoId") || !payload.containsKey("capacidadOcupada")) {
                logger.warn("⚠️ Payload de aterrizaje incompleto: {}", payload);
                return;
            }

            Integer aeropuertoId = getIntegerValue(payload.get("aeropuertoId"));
            Integer vueloId = getIntegerValue(payload.get("vueloId"));
            Integer capacidadOcupada = getIntegerValue(payload.get("capacidadOcupada"));

            if (aeropuertoId == null || capacidadOcupada == null || capacidadOcupada <= 0) {
                logger.warn("⚠️ Datos inválidos en aterrizaje: aeropuertoId={}, capacidadOcupada={}",
                    aeropuertoId, capacidadOcupada);
                return;
            }

            // Aumentar la capacidad ocupada del aeropuerto
            boolean exito = aeropuertoService.aumentarCapacidadOcupada(aeropuertoId, capacidadOcupada);

            if (exito) {
                logger.info("✅ Capacidad aumentada en aeropuerto {}: +{} unidades (vuelo {})",
                    aeropuertoId, capacidadOcupada, vueloId);

                // ⚡ Actualizar estados de envíos cuando el vuelo aterriza
                if (vueloId != null) {
                    envioService.actualizarEstadosPorAterrizaje(vueloId, aeropuertoId);
                }

                // ⚡ Verificar qué pedidos llegaron a su destino final y programar liberación
                if (vueloId != null) {
                    liberacionCapacidadService.verificarYProgramarLiberacion(vueloId, aeropuertoId);
                }

                // Opcional: Notificar a otros clientes conectados sobre el cambio
                Map<String, Object> notificacion = Map.of(
                    "tipo", "capacidad_actualizada",
                    "aeropuertoId", aeropuertoId,
                    "evento", "aterrizaje",
                    "vueloId", vueloId != null ? vueloId : 0,
                    "capacidadCambio", capacidadOcupada,
                    "timestamp", payload.getOrDefault("timestamp", System.currentTimeMillis())
                );
                messagingTemplate.convertAndSend("/topic/aeropuertos", notificacion);
            } else {
                logger.error("❌ No se pudo aumentar la capacidad del aeropuerto {}", aeropuertoId);
            }

        } catch (Exception e) {
            logger.error("❌ Error al procesar notificación de aterrizaje: {}", e.getMessage(), e);
        }
    }

    /**
     * Helper para convertir valores a Integer de forma segura
     */
    private Integer getIntegerValue(Object value) {
        if (value == null) return null;
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
