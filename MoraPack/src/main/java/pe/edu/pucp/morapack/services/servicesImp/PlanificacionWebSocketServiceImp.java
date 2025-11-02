package pe.edu.pucp.morapack.services.servicesImp;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import pe.edu.pucp.morapack.models.Solucion;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PlanificacionWebSocketServiceImp {

    private final SimpMessagingTemplate messagingTemplate;

    // Envia actualizacion cuando se completa un ciclo de planificación
    public void enviarActualizacionCiclo(Solucion solucion, int ciclo) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("tipo", "update_ciclo");
            payload.put("ciclo", ciclo);
            payload.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            payload.put("estadisticas", generarEstadisticas(solucion));
            payload.put("totalRutas", calcularTotalRutas(solucion));
            payload.put("rutas", convertirRutasParaFrontend(solucion));  // ← Agregar esto

            messagingTemplate.convertAndSend("/topic/planificacion", payload);
            System.out.println("📤 WebSocket: Update enviado para ciclo " + ciclo);

        } catch(Exception e) {
            System.err.println("❌ Error enviando WebSocket: " + e.getMessage());
        }
    }

    // Envia errores al frontend
    public void enviarError(String mensajeError, int ciclo) {
        Map<String, Object> errorPayload = new HashMap<>();
        errorPayload.put("tipo", "error");
        errorPayload.put("ciclo", ciclo);
        errorPayload.put("mensaje", mensajeError);
        errorPayload.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        messagingTemplate.convertAndSend("/topic/errores", errorPayload);
        System.out.println("📤 WebSocket: Error enviado para ciclo " + ciclo);
    }

    // Envia estado del planificador
    public void enviarEstadoPlanificador(boolean activo, int cicloActual, String proximoCiclo) {
        Map<String, Object> estado = new HashMap<>();
        estado.put("tipo", "estado_planificador");
        estado.put("activo", activo);
        estado.put("cicloActual", cicloActual);
        estado.put("proximoCiclo", proximoCiclo);
        estado.put("timestamp", LocalDateTime.now().toString());

        messagingTemplate.convertAndSend("/topic/estado", estado);
    }

    private Map<String, Object> generarEstadisticas(Solucion solucion) {
        Map<String, Object> stats = new HashMap<>();
        if (solucion != null) {
            stats.put("totalEnvios", solucion.getEnvios().size());
            stats.put("enviosCompletados", solucion.getEnviosCompletados());
            stats.put("tasaExito", solucion.getEnvios().size() > 0 ?
                    (solucion.getEnviosCompletados() * 100.0 / solucion.getEnvios().size()) : 0);
        }
        return stats;
    }

    private int calcularTotalRutas(Solucion solucion) {
        if (solucion == null || solucion.getEnvios() == null) return 0;

        return solucion.getEnvios().stream()
                .mapToInt(envio -> envio.getParteAsignadas() != null ?
                        envio.getParteAsignadas().size() : 0)
                .sum();
    }

    // Convierte las rutas al formato que espera el frontend
    private Object convertirRutasParaFrontend(Solucion solucion) {
        if (solucion == null || solucion.getEnvios() == null) {
            return new HashMap<>();
        }

        Map<String, Object> rutasFrontend = new HashMap<>();
        // Aquí puedes agregar la lógica específica para tu frontend
        // Por ejemplo, las rutas en formato para el mapa

        return rutasFrontend;
    }
}
