package pe.edu.pucp.morapack.control;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.edu.pucp.morapack.model.Envio;
import pe.edu.pucp.morapack.repo.EnvioRepository;
import pe.edu.pucp.morapack.service.EnvioImportService;

import java.util.List;

@RestController
@RequestMapping("/api/envios")
@RequiredArgsConstructor
public class EnvioController {

    private final EnvioRepository envioRepository;
    private final EnvioImportService envioImportService;

    @GetMapping
    public List<Envio> listarTodos() {
        return envioRepository.findAll();
    }

    /**
     * Importa los pedidos de un aeropuerto destino dado su código IATA.
     * Lee el archivo: src/main/resources/data/pedidos/_pedidos_{IATA}_ .txt
     *
     * Ejemplo:
     *   POST /api/envios/importar/VIDP
     */
    @PostMapping("/importar/{codigoIataDestino}")
    public ResponseEntity<String> importarPedidos(@PathVariable String codigoIataDestino) {
        try {
            envioImportService.importarPedidosDestino(codigoIataDestino.toUpperCase());
            return ResponseEntity.ok("Importación de pedidos para destino " + codigoIataDestino + " completada.");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body("Error al importar pedidos: " + e.getMessage());
        }
    }
}
