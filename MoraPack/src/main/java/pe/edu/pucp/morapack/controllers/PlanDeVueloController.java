package pe.edu.pucp.morapack.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
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
                            .capacidadOcupada(0)
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

    @PostMapping("cargarMasivoArchivoPlanes/{fecha}")
    @Transactional
    public Map<String, Object> cargarPlanesMasivoVuelo(@RequestParam("arch") MultipartFile arch,
            @PathVariable String fecha) throws IOException {
        long startTime = System.currentTimeMillis();
        Map<String, Object> resultado = new HashMap<>();

        // ⚡ OPTIMIZACIÓN: Guardar en lotes para evitar OutOfMemoryError
        final int BATCH_SIZE = 5000;
        ArrayList<PlanDeVuelo> batchPlanes = new ArrayList<>(BATCH_SIZE);
        int totalPlanesGuardados = 0;

        // Parsear la fecha base
        String anio = fecha.substring(0, 4);
        String mes = fecha.substring(4, 6);
        String dia = fecha.substring(6, 8);
        int aa = Integer.parseInt(anio);
        int mm = Integer.parseInt(mes);
        int dd = Integer.parseInt(dia);

        LocalDate fechaBase = LocalDate.of(aa, mm, dd);

        // ⚡ OPTIMIZACIÓN: Cargar aeropuertos en caché UNA SOLA VEZ
        System.out.println("📂 Cargando aeropuertos en caché...");
        ArrayList<Aeropuerto> todosAeropuertos = aeropuertoService.obtenerTodosAeropuertos();
        Map<String, Aeropuerto> aeropuertosPorCodigo = new HashMap<>();
        for (Aeropuerto a : todosAeropuertos) {
            aeropuertosPorCodigo.put(a.getCodigo(), a);
        }
        System.out.println("✅ " + todosAeropuertos.size() + " aeropuertos en caché");

        String planesDatos = new String(arch.getBytes());
        String[] lineas = planesDatos.split("\n");
        int totalLineas = lineas.length;
        System.out.println(
                "📂 Procesando " + totalLineas + " rutas x 730 días (guardando en lotes de " + BATCH_SIZE + ")...");

        int lineaActual = 0;
        for (String linea : lineas) {
            lineaActual++;
            String data[] = linea.trim().split("-");

            if (data.length > 1) {
                // ⚡ OPTIMIZACIÓN: Usar caché en lugar de consultar BD
                Aeropuerto aeropuertoOrigen = aeropuertosPorCodigo.get(data[0]);
                Aeropuerto aeropuertoDest = aeropuertosPorCodigo.get(data[1]);

                if (aeropuertoOrigen != null && aeropuertoDest != null) {
                    Integer ciudadOrigen = aeropuertoOrigen.getId();
                    Integer ciudadDestino = aeropuertoDest.getId();
                    String husoOrigen = aeropuertoOrigen.getHusoHorario();
                    String husoDestino = aeropuertoDest.getHusoHorario();
                    LocalTime hI = LocalTime.parse(data[2]);
                    LocalTime hF = LocalTime.parse(data[3]);
                    Integer capacidad = Integer.parseInt(data[4]);

                    // ✅ GENERAR PARA 730 DÍAS (2 años aprox.)
                    for (int diaOffset = 0; diaOffset < 730; diaOffset++) {
                        LocalDate fechaVuelo = fechaBase.plusDays(diaOffset);

                        LocalDateTime fechaInicio = LocalDateTime.of(fechaVuelo, hI);
                        LocalDateTime fechaFin;

                        // Calcular si el vuelo acaba en el mismo o diferente día
                        Integer cantDias = planDeVueloService.planAcabaAlSiguienteDia(
                                data[2], data[3], husoOrigen, husoDestino,
                                fechaVuelo.getYear(), fechaVuelo.getMonthValue(), fechaVuelo.getDayOfMonth());

                        fechaFin = LocalDateTime.of(fechaVuelo, hF).plusDays(cantDias);

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

                        batchPlanes.add(plan);

                        // ⚡ GUARDAR EN LOTES para evitar OutOfMemoryError
                        if (batchPlanes.size() >= BATCH_SIZE) {
                            planDeVueloService.insertarListaPlanesDeVuelo(batchPlanes);
                            totalPlanesGuardados += batchPlanes.size();
                            batchPlanes.clear(); // Liberar memoria
                            System.out.println("💾 Guardados " + totalPlanesGuardados + " planes... (línea "
                                    + lineaActual + "/" + totalLineas + ")");
                        }
                    }
                }
            }
        }

        // Guardar el último lote (lo que quedó)
        if (!batchPlanes.isEmpty()) {
            planDeVueloService.insertarListaPlanesDeVuelo(batchPlanes);
            totalPlanesGuardados += batchPlanes.size();
            batchPlanes.clear();
            System.out.println("💾 Guardado último lote. Total: " + totalPlanesGuardados + " planes");
        }

        long endTime = System.currentTimeMillis();
        long durationInMillis = endTime - startTime;
        double durationInSeconds = durationInMillis / 1000.0;

        System.out.println("✅ Vuelos generados: " + totalPlanesGuardados);
        System.out.println("📅 Rango: " + fechaBase + " hasta " + fechaBase.plusDays(729));
        System.out.println("⏱️ Tiempo de ejecución: " + durationInSeconds + " segundos");

        // 🔹 Devolver solo un resumen
        resultado.put("estado", "éxito");
        resultado.put("mensaje", "Vuelos cargados correctamente desde archivo");
        resultado.put("planesGenerados", totalPlanesGuardados);
        resultado.put("fechaInicio", fechaBase.toString());
        resultado.put("fechaFin", fechaBase.plusDays(729).toString());
        resultado.put("tiempoEjecucionSegundos", durationInSeconds);

        return resultado;
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
            while (scanner.hasNextLine()) { // Leer todas la lineas
                String row = scanner.nextLine();
                String data[] = row.split("-");

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

                        Integer contOrig = aeropuertoOrigen.getPais() != null
                                ? aeropuertoOrigen.getPais().getIdContinente()
                                : null;
                        Integer contDest = aeropuertoDest.getPais() != null ? aeropuertoDest.getPais().getIdContinente()
                                : null;
                        Boolean mismoContinente = (contOrig != null && contDest != null) ? contOrig.equals(contDest)
                                : null;
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
                                .mismoContinente(mismoContinente)
                                .capacidadOcupada(0)
                                .estado(1)
                                .build();

                        planes.add(plan);
                        System.out.println(i);
                        i++;
                    }
                }
            }
        } catch (FileNotFoundException e) {
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
