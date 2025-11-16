package pe.edu.pucp.morapack.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import pe.edu.pucp.morapack.models.Aeropuerto;
import pe.edu.pucp.morapack.models.Continente;
import pe.edu.pucp.morapack.models.Pais;
import pe.edu.pucp.morapack.services.AeropuertoService;
import pe.edu.pucp.morapack.services.ContinenteService;
import pe.edu.pucp.morapack.services.PaisService;
import pe.edu.pucp.morapack.services.servicesImp.AeropuertoServiceImp;
import pe.edu.pucp.morapack.services.servicesImp.ContinenteServiceImp;
import pe.edu.pucp.morapack.services.servicesImp.PaisServiceImp;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;

@CrossOrigin(origins = "*")
@RestController
@RequiredArgsConstructor
@RequestMapping("api/aeropuertos")
public class AeropuertoController {
    private final AeropuertoServiceImp aeropuertoService;
    private final PaisServiceImp paisService;
    private final ContinenteServiceImp continenteService;

    @PostMapping("insertar")
    Aeropuerto insertarAeropuerto(@RequestBody Aeropuerto aeropuerto) {
        return aeropuertoService.insertarAeropuerto(aeropuerto);
    }

    @PostMapping("insertarTodos")
    ArrayList<Aeropuerto> insertarTodosAeropuertos(@RequestBody ArrayList<Aeropuerto> aeropuertos) {
        return aeropuertoService.insertarListaAeropuertos(aeropuertos);
    }

    @GetMapping("obtenerTodos")
    ArrayList<Aeropuerto> obtenerTodosAeropuertos() {
        return aeropuertoService.obtenerTodosAeropuertos();
    }

    @GetMapping("obtenerPorId/{idAeropuerto}")
    Optional<Aeropuerto> obtenerAeropuertoPorId(@PathVariable Integer idAeropuerto) {
        return aeropuertoService.obtenerAeropuertoPorId(idAeropuerto);
    }

    @GetMapping("obtenerPorCodigo/{codigo}")
    Optional<Aeropuerto> obtenerAeropuertoPorCodigo(@PathVariable String codigo) {
        return aeropuertoService.obtenerAeropuertoPorCodigo(codigo);
    }

    @GetMapping("obtenerCapacidades")
    public Map<String, Object> obtenerCapacidadesAeropuertos() {
        Map<String, Object> response = new HashMap<>();
        ArrayList<Aeropuerto> aeropuertos = aeropuertoService.obtenerTodosAeropuertos();

        List<Map<String, Object>> aeropuertosFrontend = aeropuertos.stream()
                .map(a -> {
                    Map<String, Object> aeropuertoMap = new HashMap<>();
                    aeropuertoMap.put("id", a.getId());
                    aeropuertoMap.put("codigo", a.getCodigo());
                    aeropuertoMap.put("ciudad", a.getCiudad());
                    aeropuertoMap.put("pais", a.getPais());
                    aeropuertoMap.put("capacidadOcupada", a.getCapacidadOcupada() != null ? a.getCapacidadOcupada() : 0);
                    aeropuertoMap.put("capacidadMaxima", a.getCapacidadMaxima());
                    return aeropuertoMap;
                })
                .collect(java.util.stream.Collectors.toList());

        response.put("estado", "exito");
        response.put("cantidadAeropuertos", aeropuertosFrontend.size());
        response.put("aeropuertos", aeropuertosFrontend);

        return response;
    }

    @PostMapping("lecturaArchivo")
    ArrayList<Aeropuerto> cargarDatos(@RequestParam("arch") MultipartFile arch) throws IOException {
        ArrayList<Aeropuerto> aeropuertos = new ArrayList<>();

        Continente continente1 = Continente.builder()
                .id(1)
                .nombre("America")
                .build();

        Continente continente2 = Continente.builder()
                .id(2)
                .nombre("Europa")
                .build();

        Continente continente3 = Continente.builder()
                .id(3)
                .nombre("Asia")
                .build();

        continenteService.insertarContinente(continente1);
        continenteService.insertarContinente(continente2);
        continenteService.insertarContinente(continente3);

        String aeropuertosDatos = new String(arch.getBytes());
        String[] lineas = aeropuertosDatos.split("\n");

        for (String linea : lineas) {
            String data[] = linea.trim().split(",");

            Aeropuerto aeropuerto = new Aeropuerto();
            Pais pais = new Pais();

            Continente continente = continenteService.obtenerAeropuertoPorNombre(data[0]).get();

            Integer idContinente = continente.getId();
            Integer idAeropuerto = Integer.parseInt(data[1]);
            String codigoAeropuerto = data[2];
            String ciudad = data[3];
            String paisNombre = data[4];
            String abreviatura = data[5];
            String zonaHoraria = data[6];
            Integer capacidad = Integer.parseInt(data[7]);
            String latitud = data[8];
            String longitud = data[9];

            pais.setNombre(paisNombre);
            pais.setIdContinente(idContinente);
            paisService.insertarPais(pais);

            aeropuerto.setId(idAeropuerto);
            aeropuerto.setIdPais(pais.getId());
            aeropuerto.setLatitud(latitud);
            aeropuerto.setLongitud(longitud);
            aeropuerto.setEstado(1);
            aeropuerto.setCodigo(codigoAeropuerto);
            aeropuerto.setHusoHorario(zonaHoraria);
            aeropuerto.setCapacidadMaxima(capacidad);
            aeropuerto.setCapacidadOcupada(0);
            aeropuerto.setCiudad(ciudad);
            aeropuerto.setAbreviatura(abreviatura);

            aeropuertos.add(aeropuerto);

            // Aeropuerto insertado
            insertarAeropuerto(aeropuerto);
        }
        return aeropuertos;
    }

    @PostMapping("lecturaArchivoBack")
    ArrayList<Aeropuerto> cargarDatosBack() {
        ArrayList<Aeropuerto> aeropuertos = new ArrayList<>();

        Continente continente1 = Continente.builder()
                .id(1)
                .nombre("America")
                .build();

        Continente continente2 = Continente.builder()
                .id(2)
                .nombre("Europa")
                .build();

        Continente continente3 = Continente.builder()
                .id(3)
                .nombre("Asia")
                .build();

        continenteService.insertarContinente(continente1);
        continenteService.insertarContinente(continente2);
        continenteService.insertarContinente(continente3);

        try (Scanner scanner = new Scanner(new File("src/main/resources/aeropuertos/aeropuertos.csv"))) {

            while (scanner.hasNextLine()) { // Leer todas la lineas
                String row = scanner.nextLine();
                String data[] = row.split(",");

                Aeropuerto aeropuerto = new Aeropuerto();
                Pais pais = new Pais();

                Continente continente = continenteService.obtenerAeropuertoPorNombre(data[0]).get();

                Integer idContinente = continente.getId();
                Integer idAeropuerto = Integer.parseInt(data[1]);
                String codigoAeropuerto = data[2];
                String ciudad = data[3];
                String paisNombre = data[4];
                String abreviatura = data[5];
                String zonaHoraria = data[6];
                Integer capacidad = Integer.parseInt(data[7]);
                String latitud = data[8];
                String longitud = data[9];

                pais.setNombre(paisNombre);
                pais.setIdContinente(idContinente);
                pais.setContinente(continente);
                paisService.insertarPais(pais);

                aeropuerto.setId(idAeropuerto);
                aeropuerto.setIdPais(pais.getId());
                aeropuerto.setPais(pais);
                aeropuerto.setLatitud(latitud);
                aeropuerto.setLongitud(longitud);
                aeropuerto.setEstado(1);
                aeropuerto.setCodigo(codigoAeropuerto);
                aeropuerto.setHusoHorario(zonaHoraria);
                aeropuerto.setCapacidadMaxima(capacidad);
                aeropuerto.setCapacidadOcupada(0);
                aeropuerto.setCiudad(ciudad);
                aeropuerto.setAbreviatura(abreviatura);

                aeropuertos.add(aeropuerto);

                // Aeropuerto insertado
                insertarAeropuerto(aeropuerto);
            }
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }

        return aeropuertos;
    }
}
