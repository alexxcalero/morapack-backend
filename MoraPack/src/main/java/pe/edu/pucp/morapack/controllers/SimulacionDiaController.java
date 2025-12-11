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

    // 🔹 RESET MANUAL DEL RELOJ (botón "Iniciar" del front)
    @PostMapping("/reloj/reset")
    public Map<String, Object> resetReloj(@RequestBody Map<String, Object> request) {
        Map<String, Object> resp = new HashMap<>();

        try {
            Object fechaInicioObj = request.get("fechaInicio"); // "2025-12-11T10:00:00"

            if (fechaInicioObj == null) {
                resp.put("estado", "error");
                resp.put("mensaje", "Se requiere 'fechaInicio' en formato ISO: yyyy-MM-ddTHH:mm:ss");
                return resp;
            }

            String fechaInicioStr = fechaInicioObj.toString().trim();

            LocalDateTime ldt = LocalDateTime.parse(fechaInicioStr);
            Instant simInstant = ldt.toInstant(ZoneOffset.UTC);

            relojSimulacionDiaService.resetTo(simInstant);

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
}
