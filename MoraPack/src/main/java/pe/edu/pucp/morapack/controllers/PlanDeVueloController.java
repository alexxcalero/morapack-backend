package pe.edu.pucp.morapack.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import pe.edu.pucp.morapack.dtos.PlanDeVueloResponse;
import pe.edu.pucp.morapack.models.Aeropuerto;
import pe.edu.pucp.morapack.models.PlanDeVuelo;
import pe.edu.pucp.morapack.services.AeropuertoService;
import pe.edu.pucp.morapack.services.PlanDeVueloService;
import pe.edu.pucp.morapack.services.servicesImp.AeropuertoServiceImp;
import pe.edu.pucp.morapack.services.servicesImp.PlanDeVueloServiceImp;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Scanner;

@CrossOrigin(origins = "*")
@RestController
@RequiredArgsConstructor
@RequestMapping("api/planesDeVuelo")
public class PlanDeVueloController {
    private final PlanDeVueloServiceImp planDeVueloService;
    private final AeropuertoServiceImp aeropuertoService;

    @PostMapping("insertar")
    PlanDeVuelo insertarPlanDeVuelo(PlanDeVuelo plan) {
        return planDeVueloService.insertarPlanDeVuelo(plan);
    }

    @PostMapping("insertarTodos")
    ArrayList<PlanDeVuelo> insertarTodosPlanesVuelos(ArrayList<PlanDeVuelo> planes) {
        return planDeVueloService.insertarListaPlanesDeVuelo(planes);
    }

    @GetMapping("obtenerTodos")
    ArrayList<PlanDeVueloResponse> obtenerTodosPlanesVuelos() {
        return planDeVueloService.obtenerPlanesDeVuelo();
    }

    @GetMapping("obtenerPorId")
    Optional<PlanDeVuelo> obtenerPorId(Integer idPlan) {
        return planDeVueloService.obtenerPlanDeVueloPorId(idPlan);
    }

    @PostMapping("lecturaArchivo/{fecha}")
    ArrayList<PlanDeVuelo> cargarDatos(@RequestParam("arch") MultipartFile arch, @PathVariable String fecha)
            throws IOException {
        long startTime = System.currentTimeMillis();
        ArrayList<PlanDeVuelo> planes = new ArrayList<>();
        String anio = fecha.substring(0, 4);
        String mes = fecha.substring(4, 6);
        String dia = fecha.substring(6, 8);
        int aa = Integer.parseInt(anio);
        int mm = Integer.parseInt(mes);
        int dd = Integer.parseInt(dia);
        int i = 1;
        String planesDatos = new String(arch.getBytes());
        String[] lineas = planesDatos.split("\n");

        for (String linea : lineas) {
            String data[] = linea.trim().split("-");

            // Un solo dato significaria que solo se leyo el salto de linea, el cual no
            // queremos
            if (data.length > 1) {
                Optional<Aeropuerto> aeropuertoOptionalOrig = aeropuertoService.obtenerAeropuertoPorCodigo(data[0]);
                Optional<Aeropuerto> aeropuertoOptionalDest = aeropuertoService.obtenerAeropuertoPorCodigo(data[1]);

                if (aeropuertoOptionalOrig.isPresent() && aeropuertoOptionalDest.isPresent()) {
                    Aeropuerto aeropuertoOrigen = aeropuertoOptionalOrig.get();
                    Aeropuerto aeropuertoDest = aeropuertoOptionalDest.get();

                    Integer ciudadOrigen = aeropuertoOrigen.getId();
                    Integer ciudadDestino = aeropuertoDest.getId();

                    String husoOrigen = aeropuertoOrigen.getHusoHorario();
                    String husoDestino = aeropuertoDest.getHusoHorario();

                    LocalTime hI = LocalTime.parse(data[2]);
                    LocalTime hF = LocalTime.parse(data[3]);
                    Integer capacidad = Integer.parseInt(data[4]);

                    LocalDateTime fechaInicio = LocalDateTime.of(aa, mm, dd, hI.getHour(), hI.getMinute(), 0);
                    LocalDateTime fechaFin;

                    // Segun la hora de inicio y final, podemos determinar si el vuelo acaba en el
                    // mismo o diferente dia
                    Integer cantDias = planDeVueloService.planAcabaAlSiguienteDia(data[2], data[3], husoOrigen,
                            husoDestino, aa, mm, dd);
                    fechaFin = LocalDateTime.of(aa, mm, dd, hF.getHour(), hF.getMinute(), 0).plusDays(cantDias);

                    PlanDeVuelo plan = PlanDeVuelo.builder()
                            .ciudadOrigen(ciudadOrigen)
                            .ciudadDestino(ciudadDestino)
                            .horaOrigen(fechaInicio)
                            .horaDestino(fechaFin)
                            .husoHorarioOrigen(husoOrigen)
                            .husoHorarioDestino(husoDestino)
                            .capacidadMaxima(capacidad)
                            // .capacidadOcupada(0)
                            .estado(1)
                            .build();

                    planes.add(plan);
                    System.out.println(i);
                    i++;
                }
            }
        }

        planDeVueloService.insertarListaPlanesDeVuelo(planes);
        long endTime = System.currentTimeMillis();
        long durationInMillis = endTime - startTime;
        double durationInSeconds = durationInMillis / 1000.0;
        System.out.println("Tiempo de ejecucion: " + durationInSeconds + " segundos");
        return planes;
    }

    @PostMapping("cargarArchivoPlanes/{fecha}")
    ArrayList<PlanDeVuelo> cargarPlanesVuelo(@PathVariable String fecha) {
        long startTime = System.currentTimeMillis();
        ArrayList<PlanDeVuelo> planes = new ArrayList<>();

        // Limpiar tabla de planes de vuelo
        System.out.println("Limpiando tabla de planes de vuelo...");
        planDeVueloService.eliminarTodosPlanesDeVuelo();

        String anio = fecha.substring(0, 4);
        String mes = fecha.substring(4, 6);
        String dia = fecha.substring(6, 8);
        int aa = Integer.parseInt(anio);
        int mm = Integer.parseInt(mes);
        int dd = Integer.parseInt(dia);

        System.out.println("Generando planes de vuelo para 7 días desde: " + aa + "-" + mm + "-" + dd);

        // Generar vuelos para 7 días
        for (int diaOffset = 0; diaOffset < 7; diaOffset++) {
            // Calcular la fecha para este día
            java.time.LocalDate fechaActual = java.time.LocalDate.of(aa, mm, dd).plusDays(diaOffset);
            int aaActual = fechaActual.getYear();
            int mmActual = fechaActual.getMonthValue();
            int ddActual = fechaActual.getDayOfMonth();

            System.out.println(
                    "Generando vuelos para día " + (diaOffset + 1) + ": " + aaActual + "-" + mmActual + "-" + ddActual);

            int i = 1;
            try {
                File planesFile = new File("src/main/resources/planes/vuelos.txt");
                Scanner scanner = new Scanner(planesFile);

                while (scanner.hasNextLine()) {
                    String row = scanner.nextLine();
                    String data[] = row.split("-");

                    if (data.length > 1) {
                        Optional<Aeropuerto> aeropuertoOptionalOrig = aeropuertoService
                                .obtenerAeropuertoPorCodigo(data[0]);
                        Optional<Aeropuerto> aeropuertoOptionalDest = aeropuertoService
                                .obtenerAeropuertoPorCodigo(data[1]);

                        if (aeropuertoOptionalOrig.isPresent() && aeropuertoOptionalDest.isPresent()) {
                            Aeropuerto aeropuertoOrigen = aeropuertoOptionalOrig.get();
                            Aeropuerto aeropuertoDest = aeropuertoOptionalDest.get();

                            Integer ciudadOrigen = aeropuertoOrigen.getId();
                            Integer ciudadDestino = aeropuertoDest.getId();

                            String husoOrigen = aeropuertoOrigen.getHusoHorario();
                            String husoDestino = aeropuertoDest.getHusoHorario();

                            LocalTime hI = LocalTime.parse(data[2]);
                            LocalTime hF = LocalTime.parse(data[3]);
                            Integer capacidad = Integer.parseInt(data[4]);

                            LocalDateTime fechaInicio = LocalDateTime.of(aaActual, mmActual, ddActual, hI.getHour(),
                                    hI.getMinute(), 0);
                            LocalDateTime fechaFin;

                            Integer contOrig = aeropuertoOrigen.getPais() != null
                                    ? aeropuertoOrigen.getPais().getIdContinente()
                                    : null;
                            Integer contDest = aeropuertoDest.getPais() != null
                                    ? aeropuertoDest.getPais().getIdContinente()
                                    : null;
                            Boolean mismoContinente = (contOrig != null && contDest != null) ? contOrig.equals(contDest)
                                    : null;

                            Integer cantDias = planDeVueloService.planAcabaAlSiguienteDia(data[2], data[3], husoOrigen,
                                    husoDestino, aaActual, mmActual, ddActual);
                            fechaFin = LocalDateTime.of(aaActual, mmActual, ddActual, hF.getHour(), hF.getMinute(), 0)
                                    .plusDays(cantDias);

                            PlanDeVuelo plan = PlanDeVuelo.builder()
                                    .ciudadOrigen(ciudadOrigen)
                                    .ciudadDestino(ciudadDestino)
                                    .horaOrigen(fechaInicio)
                                    .horaDestino(fechaFin)
                                    .husoHorarioOrigen(husoOrigen)
                                    .husoHorarioDestino(husoDestino)
                                    .capacidadMaxima(capacidad)
                                    .mismoContinente(mismoContinente)
                                    .estado(1)
                                    .build();

                            planes.add(plan);
                            i++;
                        }
                    }
                }
                scanner.close();
            } catch (FileNotFoundException e) {
                System.out.println("Archivo de planes no encontrado, error: " + e.getMessage());
            }
        }

        System.out.println("Total de vuelos generados: " + planes.size());
        planDeVueloService.insertarListaPlanesDeVuelo(planes);

        long endTime = System.currentTimeMillis();
        long durationInMillis = endTime - startTime;
        double durationInSeconds = durationInMillis / 1000.0;
        System.out.println("Tiempo de ejecución: " + durationInSeconds + " segundos");

        return planes;
    }
}
