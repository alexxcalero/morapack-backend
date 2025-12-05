package pe.edu.pucp.morapack.control;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.edu.pucp.morapack.model.Vuelo;
import pe.edu.pucp.morapack.repo.VueloRepository;
import pe.edu.pucp.morapack.service.VueloImportService;

import java.util.List;

@RestController
@RequestMapping("/api/vuelos")
@RequiredArgsConstructor
public class VueloController {

    private final VueloRepository vueloRepository;
    private final VueloImportService vueloImportService;

    @GetMapping
    public List<Vuelo> listarTodos() {
        return vueloRepository.findAll();
    }

    @PostMapping("/importar")
    public ResponseEntity<String> importarVuelos() {
        try {
            // Nombre del archivo en src/main/resources/data/
            String nombreArchivo = "vuelos.txt";
            vueloImportService.importarDesdeArchivo(nombreArchivo);
            return ResponseEntity.ok("Importación de vuelos completada desde " + nombreArchivo);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body("Error al importar vuelos: " + e.getMessage());
        }
    }
}
