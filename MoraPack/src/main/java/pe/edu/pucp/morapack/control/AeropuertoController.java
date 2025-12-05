package pe.edu.pucp.morapack.control;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.edu.pucp.morapack.model.Aeropuerto;
import pe.edu.pucp.morapack.repo.AeropuertoRepository;
import pe.edu.pucp.morapack.service.AeropuertoImportService;
import pe.edu.pucp.morapack.service.AeropuertoService;

import java.util.List;

@RestController
@RequestMapping("/api/aeropuertos")
@RequiredArgsConstructor
public class AeropuertoController {

    private final AeropuertoRepository aeropuertoRepository;
    private final AeropuertoImportService importService;
    private final AeropuertoService aeropuertoService;

    @GetMapping
    public List<Aeropuerto> listarTodos() {
        return aeropuertoRepository.findAll();
    }

    @PostMapping("/importar/{continente}")
    public ResponseEntity<String> importar(@PathVariable String continente) {
        try {
            String nombreArchivo;
            switch (continente.toLowerCase()) {
                case "america":
                    nombreArchivo = "aeropuertos-america.txt";
                    break;
                case "asia":
                    nombreArchivo = "aeropuertos-asia.txt";
                    break;
                case "europa":
                    nombreArchivo = "aeropuertos-europa.txt";
                    break;
                default:
                    return ResponseEntity.badRequest()
                            .body("Continente no soportado: " + continente);
            }

            String nombreContinenteBd = capitalize(continente);
            importService.importarDesdeArchivo(nombreArchivo, nombreContinenteBd);

            return ResponseEntity.ok("Importación de aeropuertos de " + continente + " completada.");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body("Error al importar: " + e.getMessage());
        }
    }

    // Endpoint para marcar / desmarcar sede
    @PatchMapping("/{codigoIata}/sede")
    public ResponseEntity<?> actualizarSede(
            @PathVariable String codigoIata,
            @RequestParam boolean esSede) {

        try {
            Aeropuerto actualizado = aeropuertoService.actualizarSedePorCodigoIata(codigoIata, esSede);
            return ResponseEntity.ok(actualizado);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0,1).toUpperCase() + s.substring(1).toLowerCase();
    }
}
