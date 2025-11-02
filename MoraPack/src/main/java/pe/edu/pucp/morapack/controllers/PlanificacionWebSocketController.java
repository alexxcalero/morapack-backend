package pe.edu.pucp.morapack.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class PlanificacionWebSocketController {

    // Endpoint para que el frontend se suscriba
    @MessageMapping("/suscribir-planificacion")
    @SendTo("/topic/estado-suscripcion")
    public Map<String, Object> manejarSuscripcion() {
        Map<String, Object> response = new HashMap<>();
        response.put("estado", "suscripcion_activa");
        response.put("timestamp", LocalDateTime.now().toString());
        response.put("mensaje", "Te has suscrito a las actualizaciones de planificación");
        return response;
    }

    // Endpoint para que el frontend solicite el estado actual
    @MessageMapping("/solicitar-estado")
    @SendTo("/topic/estado-actual")
    public Map<String, Object> manejarSolicitudEstado() {
        Map<String, Object> respuesta = new HashMap<>();
        respuesta.put("tipo", "estado_actual");
        respuesta.put("timestamp", LocalDateTime.now().toString());
        respuesta.put("mensaje", "Estado solicitado correctamente");
        return respuesta;
    }
}
