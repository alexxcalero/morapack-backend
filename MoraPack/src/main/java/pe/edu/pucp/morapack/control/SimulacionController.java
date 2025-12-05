// src/main/java/pe/edu/pucp/morapack/control/SimulacionController.java
package pe.edu.pucp.morapack.control;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.edu.pucp.morapack.dto.SimulacionSemanalRequest;
import pe.edu.pucp.morapack.model.Simulacion;
import pe.edu.pucp.morapack.service.SimulacionService;

@RestController
@RequestMapping("/api/simulaciones/semanal")
@RequiredArgsConstructor
public class SimulacionController {

    private final SimulacionService simulacionService;

    // Si quieres, este POST puede incluso desaparecer,
    // o dejarse para "crear en pausa" sin fechas.
    @PostMapping
    public ResponseEntity<Simulacion> crearPorDefecto() {
        // opcional: crear una simulación en pausa con now / now+7
        return ResponseEntity.ok(
                simulacionService.crearNuevaSimulacionSemanalPorDefecto());
    }

    @PostMapping("/iniciar")
    public ResponseEntity<Simulacion> iniciar(@RequestBody SimulacionSemanalRequest request) {
        return ResponseEntity.ok(
                simulacionService.iniciarSimulacionSemanal(
                        request.tiempoSimuladoInicio(),
                        request.tiempoSimuladoFin()));
    }
}
