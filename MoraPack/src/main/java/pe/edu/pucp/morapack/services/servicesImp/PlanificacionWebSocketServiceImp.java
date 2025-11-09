package pe.edu.pucp.morapack.services.servicesImp;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import pe.edu.pucp.morapack.models.*;
import pe.edu.pucp.morapack.services.AeropuertoService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PlanificacionWebSocketServiceImp {

    private final AeropuertoService aeropuertoService;
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
            payload.put("envios", convertirEnviosParaFrontend(solucion));  // ← Agregar esto

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
    private List<Map<String, Object>> convertirEnviosParaFrontend(Solucion solucion) {
        List<Map<String, Object>> enviosFrontend = new ArrayList<>();

        if (solucion == null || solucion.getEnvios() == null) {
            return enviosFrontend;
        }

        for (Envio envio : solucion.getEnvios()) {
            Map<String, Object> envioFrontend = new HashMap<>();
            envioFrontend.put("envioId", envio.getId());
            envioFrontend.put("destino", envio.getAeropuertoDestino().getCodigo());
            envioFrontend.put("cantidadTotal", envio.getNumProductos());
            envioFrontend.put("cantidadAsignada", envio.cantidadAsignada());
            envioFrontend.put("completo", envio.estaCompleto());
            envioFrontend.put("origenesPosibles", envio.getAeropuertosOrigen().stream()
                    .map(Aeropuerto::getCodigo)
                    .collect(Collectors.toList()));
            envioFrontend.put("aparicion", envio.getZonedFechaIngreso().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

            // Partes del envío
            List<Map<String, Object>> partesFrontend = new ArrayList<>();

            if (envio.getParteAsignadas() != null) {
                for (ParteAsignada parte : envio.getParteAsignadas()) {
                    Map<String, Object> parteFrontend = new HashMap<>();
                    parteFrontend.put("cantidad", parte.getCantidad());
                    parteFrontend.put("origen", parte.getAeropuertoOrigen().getCodigo());
                    parteFrontend.put("llegadaFinal", parte.getLlegadaFinal().format(
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

                    // Tramos de la parte
                    List<Map<String, Object>> tramosFrontend = new ArrayList<>();

                    if(parte.getRuta() != null) {
                        for(PlanDeVuelo vuelo : parte.getRuta()) {
                            Map<String, Object> tramoFrontend = new HashMap<>();
                            tramoFrontend.put("origen", aeropuertoService.obtenerAeropuertoPorId(vuelo.getCiudadOrigen()).get().getCodigo());
                            tramoFrontend.put("destino", aeropuertoService.obtenerAeropuertoPorId(vuelo.getCiudadDestino()).get().getCodigo());
                            tramoFrontend.put("salida", vuelo.getZonedHoraOrigen().format(
                                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
                            tramoFrontend.put("llegada", vuelo.getZonedHoraDestino().format(
                                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
                            tramoFrontend.put("capacidadOcupada", vuelo.getCapacidadOcupada());
                            tramoFrontend.put("capacidadMaxima", vuelo.getCapacidadMaxima());

                            tramosFrontend.add(tramoFrontend);
                        }
                    }

                    parteFrontend.put("tramos", tramosFrontend);
                    partesFrontend.add(parteFrontend);
                }
            }

            envioFrontend.put("partes", partesFrontend);
            enviosFrontend.add(envioFrontend);
        }

        return enviosFrontend;
    }
}
