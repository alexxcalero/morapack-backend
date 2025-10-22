package pe.edu.pucp.morapack.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import pe.edu.pucp.morapack.dtos.PlanDeVueloResponse;
import pe.edu.pucp.morapack.models.Aeropuerto;
import pe.edu.pucp.morapack.models.PlanDeVuelo;
import pe.edu.pucp.morapack.services.AeropuertoService;
import pe.edu.pucp.morapack.services.PlanDeVueloService;

import java.io.File;
import java.io.FileNotFoundException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Scanner;

@CrossOrigin(origins = "*")
@RestController
@RequiredArgsConstructor
@RequestMapping("api/planesDeVuelo")
public class PlanDeVueloController {
    private final PlanDeVueloService planDeVueloService;
    private final AeropuertoService aeropuertoService;

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

    @GetMapping("obtenerPorFechasConLatitudLongitud/{fechaI}/{fechaF}")
    ArrayList<PlanDeVueloResponse> obtenerTodosPorFechasConLatitudLongitud(@PathVariable String fechaI, @PathVariable String fechaF) {
        int anio = Integer.parseInt(fechaI.substring(0, 4));
        int mes = Integer.parseInt(fechaI.substring(4, 6));
        int dia = Integer.parseInt(fechaI.substring(6, 8));
        int hora = Integer.parseInt(fechaI.substring(9, 11));
        int minutos = Integer.parseInt(fechaI.substring(12, 14));
        String husoHorarioStr = fechaI.substring(15);
        ZonedDateTime fechaInicio = ZonedDateTime.of(anio, mes, dia, hora, minutos, 0, 0, ZoneId.of(husoHorarioStr));
        LocalDateTime fechaInicioLocal = fechaInicio.toLocalDateTime();

        anio = Integer.parseInt(fechaF.substring(0, 4));
        mes = Integer.parseInt(fechaF.substring(4, 6));
        dia = Integer.parseInt(fechaF.substring(6, 8));
        hora = Integer.parseInt(fechaF.substring(9, 11));
        minutos = Integer.parseInt(fechaF.substring(12, 14));
        husoHorarioStr = fechaF.substring(15);
        ZonedDateTime fechaFin = ZonedDateTime.of(anio, mes, dia, hora, minutos, 0, 0, ZoneId.of(husoHorarioStr));
        LocalDateTime fechaFinLocal = fechaFin.toLocalDateTime();

        return planDeVueloService.obtenerPlanesDeVueloPorFechaLatLong(fechaInicioLocal, husoHorarioStr, fechaFinLocal);
    }

    @GetMapping("obtenerPorFechas/{fechaI}/{fechaF}")
    ArrayList<PlanDeVuelo> obtenerTodosPorFechas(@PathVariable String fechaI, @PathVariable String fechaF) {
        int anio = Integer.parseInt(fechaI.substring(0, 4));
        int mes = Integer.parseInt(fechaI.substring(4, 6));
        int dia = Integer.parseInt(fechaI.substring(6, 8));
        int hora = Integer.parseInt(fechaI.substring(9, 11));
        int minutos = Integer.parseInt(fechaI.substring(12, 14));
        String husoHorarioStr = fechaI.substring(15);
        ZonedDateTime fechaInicio = ZonedDateTime.of(anio, mes, dia, hora, minutos, 0, 0, ZoneId.of(husoHorarioStr));
        LocalDateTime fechaInicioLocal = fechaInicio.toLocalDateTime();

        anio = Integer.parseInt(fechaF.substring(0, 4));
        mes = Integer.parseInt(fechaF.substring(4, 6));
        dia = Integer.parseInt(fechaF.substring(6, 8));
        hora = Integer.parseInt(fechaF.substring(9, 11));
        minutos = Integer.parseInt(fechaF.substring(12, 14));
        husoHorarioStr = fechaF.substring(15);
        ZonedDateTime fechaFin = ZonedDateTime.of(anio, mes, dia, hora, minutos, 0, 0, ZoneId.of(husoHorarioStr));
        LocalDateTime fechaFinLocal = fechaFin.toLocalDateTime();

        return planDeVueloService.obtenerPlanesDeVueloPorFecha(fechaInicioLocal, husoHorarioStr, fechaFinLocal);
    }

    @PostMapping("cargarArchivoPlanes/{fecha}")
    ArrayList<PlanDeVuelo> cargarPlanesVuelo(@PathVariable String fecha) {
        long startTime = System.currentTimeMillis();
        ArrayList<PlanDeVuelo> planes = new ArrayList<>();
        String anio = fecha.substring(0, 4);
        String mes = fecha.substring(4, 6);
        String dia = fecha.substring(6, 8);
        int aa = Integer.parseInt(anio);
        int mm = Integer.parseInt(mes);
        int dd = Integer.parseInt(dia);
        int i = 1;
        try {
            File planesFile = new File("src/main/resources/planes/vuelos.txt");
            Scanner scanner = new Scanner(planesFile);
            while(scanner.hasNextLine()) {  // Leer todas la lineas
                String row = scanner.nextLine();
                String data[] = row.split("-");

                // Un solo dato significaria que solo se leyo el salto de linea, el cual no queremos
                if(data.length > 1) {
                    Optional<Aeropuerto> aeropuertoOptionalOrig = aeropuertoService.obtenerAeropuertoPorCodigo(data[0]);
                    Optional<Aeropuerto> aeropuertoOptionalDest = aeropuertoService.obtenerAeropuertoPorCodigo(data[1]);

                    if(aeropuertoOptionalOrig.isPresent() && aeropuertoOptionalDest.isPresent()) {
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

                        //Segun la hora de inicio y final, podemos determinar si el vuelo acaba en el mismo o diferente dia
                        Integer cantDias = planDeVueloService.planAcabaAlSiguienteDia(data[2], data[3], husoOrigen, husoDestino, aa, mm, dd);
                        fechaFin = LocalDateTime.of(aa, mm, dd, hF.getHour(), hF.getMinute(), 0).plusDays(cantDias);

                        PlanDeVuelo plan = PlanDeVuelo.builder()
                                .ciudadOrigen(ciudadOrigen)
                                .ciudadDestino(ciudadDestino)
                                .horaOrigen(fechaInicio)
                                .horaDestino(fechaFin)
                                .husoHorarioOrigen(husoOrigen)
                                .husoHorarioDestino(husoDestino)
                                .capacidadMaxima(capacidad)
                                .capacidadOcupada(0)
                                .estado(1)
                                .build();

                        planes.add(plan);
                        System.out.println(i);
                        i++;
                    }
                }
            }
        } catch(FileNotFoundException e) {
            System.out.println("Archivo de pedidos no encontrado, error: " + e.getMessage());
        }

        planDeVueloService.insertarListaPlanesDeVuelo(planes);
        long endTime = System.currentTimeMillis();
        long durationInMillis = endTime - startTime;
        double durationInSeconds = durationInMillis / 1000.0;
        System.out.println("Tiempo de ejecucion: " + durationInSeconds + " segundos");
        return planes;
    }
}
