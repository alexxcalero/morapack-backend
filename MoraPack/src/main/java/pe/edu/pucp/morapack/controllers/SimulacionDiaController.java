package pe.edu.pucp.morapack.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.edu.pucp.morapack.services.RelojSimulacionDiaService;
import pe.edu.pucp.morapack.models.Aeropuerto;
import pe.edu.pucp.morapack.models.Continente;
import pe.edu.pucp.morapack.models.Grasp;
import pe.edu.pucp.morapack.models.Pais;
import pe.edu.pucp.morapack.models.Planificador;
import pe.edu.pucp.morapack.services.EnvioSimulacionDiaService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/simulacion-dia")
@RequiredArgsConstructor
public class SimulacionDiaController {

    private final RelojSimulacionDiaService relojSimulacionDiaService;
    private final Planificador planificador;

    // 🔹 RESET MANUAL DEL RELOJ (botón "Iniciar" del front)
    @PostMapping("/reloj/reset")
    public Map<String, Object> resetReloj(@RequestBody Map<String, Object> request) {
        Map<String, Object> resp = new HashMap<>();

        try {
            Object fechaInicioObj = request.get("fechaInicio");

            if (fechaInicioObj == null) {
                resp.put("estado", "error");
                resp.put("mensaje", "Se requiere 'fechaInicio' en formato ISO: yyyy-MM-ddTHH:mm:ss");
                return resp;
            }

            String fechaInicioStr = fechaInicioObj.toString().trim();

            LocalDateTime ldt = LocalDateTime.parse(fechaInicioStr);
            Instant simInstant = ldt.toInstant(ZoneOffset.UTC);

            relojSimulacionDiaService.resetTo(simInstant);
            // 👇 sincroniza planificador (inicio de horizonte)
            planificador.resetYReiniciarOperacionesDiarias(simInstant);

            Instant current = relojSimulacionDiaService.getCurrentSimInstant();

            resp.put("estado", "éxito");
            resp.put("mensaje", "Reloj de simulación día reiniciado");
            resp.put("simMs", current.toEpochMilli());
            resp.put("isoUtc", current.toString());
            return resp;
        } catch (Exception e) {
            resp.put("estado", "error");
            resp.put("mensaje", "Error al reiniciar reloj día a día: " + e.getMessage());
            return resp;
        }
    }

    /**
     * 🔹 GET HORA ACTUAL DEL RELOJ (para el front, debugging o health-check)
     * Devuelve el instante actual de simulación (simMs e isoUtc) y si está
     * corriendo.
     */
    @GetMapping("/reloj/ahora")
    public Map<String, Object> getHoraActualReloj(
            @RequestParam(value = "cached", required = false, defaultValue = "false") boolean cached) {
        Map<String, Object> resp = new HashMap<>();
        try {
            // cached=false: recalcula usando simStartInstant + elapsed real
            // cached=true : devuelve el último instante que se llegó a emitir (tick
            // anterior)
            Instant simInstant = cached
                    ? relojSimulacionDiaService.getUltimoSimInstant()
                    : relojSimulacionDiaService.getCurrentSimInstant();

            if (simInstant == null) {
                resp.put("estado", "error");
                resp.put("mensaje", "Reloj aún no inicializado (simInstant es null).");
                resp.put("running", relojSimulacionDiaService.isRunning());
                return resp;
            }

            resp.put("estado", "éxito");
            resp.put("running", relojSimulacionDiaService.isRunning());
            resp.put("simMs", simInstant.toEpochMilli());
            resp.put("isoUtc", simInstant.toString());

            // útil para comparar en front/back y depurar desfases
            resp.put("serverNowUtc", Instant.now().toString());

            // opcional, pero consistente con tu service
            resp.put("tickSeconds", 1);

            return resp;
        } catch (Exception e) {
            resp.put("estado", "error");
            resp.put("mensaje", "Error al obtener hora actual del reloj: " + e.getMessage());
            resp.put("running", relojSimulacionDiaService.isRunning());
            return resp;
        }
    }
}
