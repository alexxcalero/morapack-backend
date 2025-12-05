package pe.edu.pucp.morapack.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.edu.pucp.morapack.dto.GraspCicloDto;
import pe.edu.pucp.morapack.model.*;
import pe.edu.pucp.morapack.repo.*;
import pe.edu.pucp.morapack.service.grasp.EstadoAlmacen;
import pe.edu.pucp.morapack.service.grasp.SolucionGrasp;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlanificadorGraspService {

    private final EnvioRepository envioRepository;
    private final VueloRepository vueloRepository;
    private final AeropuertoRepository aeropuertoRepository;
    private final EnvioVueloRepository envioVueloRepository;
    private final SimulacionRepository simulacionRepository;

    // Sabe dónde está cada envío en tSim
    private final EnvioLocalizacionService envioLocalizacionService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Ejecuta un ciclo GRASP.
     *
     * @param simulacion simulación semanal activa
     * @param tSim       tiempo simulado actual
     * @param k          constante de proporcionalidad del tiempo
     */
    @Transactional
    public LocalDateTime ejecutarCiclo(
            Simulacion simulacion,
            LocalDateTime tSim,
            int k) {

        if (tSim == null) {
            log.warn("[GRASP] tSim es null, no se ejecuta el planificador.");
            return null;
        }

        int factor = Math.max(1, k);
        Duration ventana = Duration.ofMinutes(2L * factor);
        LocalDateTime tFinVentana = tSim.plus(ventana);

        log.info("[GRASP] Ciclo GRASP. Ventana [{} , {}] (K={})",
                tSim, tFinVentana, factor);

        // 1) Cargar datos base
        List<Envio> enviosVentana = envioRepository.findReprogramablesEnVentana(tSim, tFinVentana);

        // Filtrar los que estén en aeropuerto y reprogramables en esta simulación
        List<Envio> envios = enviosVentana.stream()
                .filter(e -> envioLocalizacionService.puedeReprogramarse(e, simulacion, tSim))
                .toList();

        List<Vuelo> vuelos = vueloRepository.findAll();
        Map<Integer, EstadoAlmacen> almacenes = cargarEstadoAlmacenes();

        // ===================== CASOS SIN TRABAJO =====================

        if (envios.isEmpty()) {
            log.info("[GRASP] No hay envíos pendientes reprogramables en la ventana.");
            // Emitimos ciclo “vacío”, pero mandando listas base
            emitirCicloWebSocket(
                    simulacion,
                    tSim,
                    tFinVentana,
                    factor,
                    enviosVentana,
                    vuelos,
                    0,
                    null,
                    List.of(),
                    null
            );

            simulacion.setTiempoSimuladoInicio(tFinVentana);
            simulacionRepository.save(simulacion);
            return tFinVentana;
        }

        if (vuelos.isEmpty()) {
            log.info("[GRASP] No hay vuelos programados.");
            emitirCicloWebSocket(
                    simulacion,
                    tSim,
                    tFinVentana,
                    factor,
                    enviosVentana,
                    vuelos,
                    0,
                    null,
                    List.of(),
                    null
            );
            simulacion.setTiempoSimuladoInicio(tFinVentana);
            simulacionRepository.save(simulacion);
            return tFinVentana;
        }

        // 2) Instancias de vuelos
        List<VueloInstancia> instancias = instanciarVuelosEnVentana(vuelos, tSim, tFinVentana);

        if (instancias.isEmpty()) {
            log.info("[GRASP] No hay instancias de vuelos dentro de la ventana.");
            emitirCicloWebSocket(
                    simulacion,
                    tSim,
                    tFinVentana,
                    factor,
                    enviosVentana,
                    vuelos,
                    0,
                    null,
                    List.of(),
                    null
            );
            simulacion.setTiempoSimuladoInicio(tFinVentana);
            simulacionRepository.save(simulacion);
            return tFinVentana;
        }

        // 3) Ejecutar GRASP
        SolucionGrasp mejor = ejecutarGrasp(envios, instancias, almacenes, tSim, tFinVentana);

        if (mejor == null || mejor.getAsignaciones().isEmpty()) {
            log.info("[GRASP] Sin solución factible en esta ventana.");
            emitirCicloWebSocket(
                    simulacion,
                    tSim,
                    tFinVentana,
                    factor,
                    enviosVentana,
                    vuelos,
                    0,
                    mejor != null ? mejor.getMomentoColapso() : null,
                    List.of(),
                    null
            );
            simulacion.setTiempoSimuladoInicio(tFinVentana);
            simulacionRepository.save(simulacion);
            return tFinVentana;
        }

        // 4) Persistir la mejor solución
        persistirSolucion(simulacion, mejor);

        log.info("[GRASP] Ciclo aplicado: {} envíos asignados, colapso={}",
                mejor.getCantidadEnviosAsignados(), mejor.getMomentoColapso());

        // 5) Emitir por WebSocket (con listas completas)
        emitirCicloWebSocket(
                simulacion,
                tSim,
                tFinVentana,
                factor,
                enviosVentana,
                vuelos,
                mejor.getCantidadEnviosAsignados(),
                mejor.getMomentoColapso(),
                mejor.getAsignaciones(),
                mejor.getEstadoAlmacenesFinal()
        );

        // Cada ciclo avanza lo mismo que la ventana
        LocalDateTime nuevoTiempo = tFinVentana;

        // Guardar el puntero actual en la simulación
        simulacion.setTiempoSimuladoInicio(nuevoTiempo);
        simulacionRepository.save(simulacion);
        return nuevoTiempo;
    }

    // ========= ENVÍO POR WEBSOCKET ==========

    private void emitirCicloWebSocket(
            Simulacion simulacion,
            LocalDateTime tInicio,
            LocalDateTime tFin,
            int k,
            List<Envio> enviosVentana,
            List<Vuelo> vuelos,
            int totalAsignados,
            LocalDateTime momentoColapso,
            List<SolucionGrasp.AsignacionEnvioVuelo> asignaciones,
            Map<Integer, EstadoAlmacen> almacenesFinales
    ) {

        int totalCandidatos = (enviosVentana != null) ? enviosVentana.size() : 0;

        // 1) Aeropuertos
        List<Aeropuerto> aeropuertos = aeropuertoRepository.findAll();

        Map<Integer, EstadoAlmacen> mapaAlmacenes = (almacenesFinales != null && !almacenesFinales.isEmpty())
                ? almacenesFinales
                : cargarEstadoAlmacenes(); // fallback: BD

        var aeropuertosSnapshotDto = aeropuertos.stream()
                .map(a -> {
                    EstadoAlmacen ea = mapaAlmacenes.getOrDefault(
                            a.getId(),
                            new EstadoAlmacen(
                                    a.getId(),
                                    a.getCapacidadMaxima() != null ? a.getCapacidadMaxima() : 0,
                                    a.getOcupacionActual() != null ? a.getOcupacionActual() : 0));

                    double lat = a.getLatitud() != null ? a.getLatitud().doubleValue() : 0.0;
                    double lng = a.getLongitud() != null ? a.getLongitud().doubleValue() : 0.0;

                    return GraspCicloDto.AeropuertoSnapshotDto.builder()
                            .id(a.getId())
                            .codigoIata(a.getCodigoIata())
                            .nombre(a.getNombre())
                            .lat(lat)
                            .lng(lng)
                            .capacidadMaxima(ea.getCapacidadMaxima())
                            .ocupacionActual(ea.getOcupacionActual())
                            .build();
                })
                .toList();

        // 🔹 Lista “cruda” de aeropuertos
        var listaAeropuertosDto = aeropuertos.stream()
                .map(a -> GraspCicloDto.AeropuertoDto.builder()
                        .id(a.getId())
                        .codigoIata(a.getCodigoIata())
                        .nombre(a.getNombre())
                        .lat(a.getLatitud() != null ? a.getLatitud().doubleValue() : 0.0)
                        .lng(a.getLongitud() != null ? a.getLongitud().doubleValue() : 0.0)
                        .capacidadMaxima(a.getCapacidadMaxima())
                        .ocupacionActual(a.getOcupacionActual())
                        .build())
                .toList();

        // 2) Movimientos (segmentos origen→destino) a partir de las asignaciones
        var movimientosDto = (asignaciones == null ? List.<SolucionGrasp.AsignacionEnvioVuelo>of() : asignaciones)
                .stream()
                .map(a -> {
                    Vuelo v = a.getVuelo();
                    Aeropuerto origen = v.getAeropuertoOrigen();
                    Aeropuerto destino = v.getAeropuertoDestino();

                    double oLat = origen.getLatitud() != null ? origen.getLatitud().doubleValue() : 0.0;
                    double oLng = origen.getLongitud() != null ? origen.getLatitud().doubleValue() : 0.0;
                    double dLat = destino.getLatitud() != null ? destino.getLatitud().doubleValue() : 0.0;
                    double dLng = destino.getLongitud() != null ? destino.getLongitud().doubleValue() : 0.0;

                    return GraspCicloDto.MovimientoEnvioDto.builder()
                            .idEnvio(a.getEnvio().getId())
                            .codigoEnvio(a.getEnvio().getCodigoEnvio())
                            .idOrigen(origen.getId())
                            .codigoOrigen(origen.getCodigoIata())
                            .origenLat(oLat)
                            .origenLng(oLng)
                            .idDestino(destino.getId())
                            .codigoDestino(destino.getCodigoIata())
                            .destinoLat(dLat)
                            .destinoLng(dLng)
                            .build();
                })
                .toList();

        // 🔹 Lista de vuelos “cruda”
        var listaVuelosDto = (vuelos == null ? List.<Vuelo>of() : vuelos)
                .stream()
                .map(v -> GraspCicloDto.VueloDto.builder()
                        .id(v.getId())
                        .codigoVuelo(v.getCodigoVuelo())
                        .idOrigen(v.getAeropuertoOrigen().getId())
                        .idDestino(v.getAeropuertoDestino().getId())
                        .horaSalida(v.getHoraSalida())
                        .horaLlegadaEstimada(v.getHoraLlegadaEstimada())
                        .capacidadMaxima(v.getCapacidadMaxima())
                        .build())
                .toList();

        // 🔹 Lista de envíos de la ventana
        var listaEnviosDto = (enviosVentana == null ? List.<Envio>of() : enviosVentana)
                .stream()
                .map(e -> GraspCicloDto.EnvioDto.builder()
                        .id(e.getId())
                        .codigoEnvio(e.getCodigoEnvio())
                        .idOrigen(e.getAeropuertoOrigen().getId())
                        .idDestino(e.getAeropuertoDestino().getId())
                        .cantidad(e.getCantidad())
                        .fechaLimiteEntrega(e.getFechaLimiteEntrega())
                        .estado(e.getEstado() != null ? e.getEstado().name() : null)
                        .build())
                .toList();

        // 🔹 Lista tipo envio_vuelo (rutas) a partir de las asignaciones
        var listaEnviosVueloDto = (asignaciones == null ? List.<SolucionGrasp.AsignacionEnvioVuelo>of() : asignaciones)
                .stream()
                .map(a -> GraspCicloDto.EnvioVueloDto.builder()
                        .idEnvio(a.getEnvio().getId())
                        .idVuelo(a.getVuelo().getId())
                        .ordenTramo(1)   // por ahora un solo tramo
                        .cantidad(a.getCantidad())
                        .build())
                .toList();

        GraspCicloDto dto = GraspCicloDto.builder()
                .idSimulacion(simulacion.getId())
                .tipoSimulacion(simulacion.getTipoSimulacion().name())
                .tSim(tInicio)
                .tInicioVentana(tInicio)
                .tFinVentana(tFin)
                .kFactor(k)
                .totalEnviosCandidatos(totalCandidatos)
                .totalEnviosAsignados(totalAsignados)
                .momentoColapso(momentoColapso)
                .asignaciones(
                        asignaciones == null ? List.of()
                                : asignaciones.stream()
                                .map(a -> GraspCicloDto.AsignacionDto.builder()
                                        .idEnvio(a.getEnvio().getId())
                                        .codigoEnvio(a.getEnvio().getCodigoEnvio())
                                        .idVuelo(a.getVuelo().getId())
                                        .codigoVuelo(a.getVuelo().getCodigoVuelo())
                                        .cantidad(a.getCantidad())
                                        .build())
                                .toList())
                .aeropuertos(aeropuertosSnapshotDto)
                .movimientos(movimientosDto)

                // Nuevas listas detalladas
                .listaAeropuertos(listaAeropuertosDto)
                .listaVuelos(listaVuelosDto)
                .listaEnvios(listaEnviosDto)
                .listaEnviosVuelo(listaEnviosVueloDto)

                .build();

        messagingTemplate.convertAndSend("/topic/planificador/grasp-ciclo", dto);
    }

    /*
     * ==================================================
     * 1. Carga de almacenes (ocupación actual por aeropuerto)
     * ==================================================
     */

    private Map<Integer, EstadoAlmacen> cargarEstadoAlmacenes() {
        return aeropuertoRepository.findAll().stream()
                .collect(Collectors.toMap(
                        a -> a.getId(),
                        a -> new EstadoAlmacen(
                                a.getId(),
                                a.getCapacidadMaxima() != null ? a.getCapacidadMaxima() : 0,
                                a.getOcupacionActual() != null ? a.getOcupacionActual() : 0)));
    }

    /*
     * ==================================================
     * 2. Instanciación de vuelos (TIME → LocalDateTime)
     * ==================================================
     */

    private record VueloInstancia(
            Vuelo vueloBase,
            LocalDateTime salida,
            LocalDateTime llegada) {
    }

    private List<VueloInstancia> instanciarVuelosEnVentana(
            List<Vuelo> vuelos,
            LocalDateTime tInicio,
            LocalDateTime tFinVentana) {

        List<VueloInstancia> lista = new ArrayList<>();

        LocalDate fechaInicio = tInicio.toLocalDate();
        LocalDate fechaFin = tFinVentana.toLocalDate();

        for (Vuelo v : vuelos) {
            LocalTime horaSalida = v.getHoraSalida();
            LocalTime horaLlegada = v.getHoraLlegadaEstimada();
            if (horaSalida == null || horaLlegada == null)
                continue;

            for (LocalDate date = fechaInicio; !date.isAfter(fechaFin); date = date.plusDays(1)) {

                LocalDateTime salida = LocalDateTime.of(date, horaSalida);
                LocalDateTime llegada = LocalDateTime.of(date, horaLlegada);
                if (horaLlegada.isBefore(horaSalida)) {
                    llegada = llegada.plusDays(1);
                }

                if (!salida.isBefore(tInicio) && !salida.isAfter(tFinVentana)) {
                    lista.add(new VueloInstancia(v, salida, llegada));
                }
            }
        }

        return lista;
    }

    /*
     * ==================================================
     * 3. Núcleo GRASP adaptado al modelo
     * ==================================================
     */

    private SolucionGrasp ejecutarGrasp(
            List<Envio> envios,
            List<VueloInstancia> instancias,
            Map<Integer, EstadoAlmacen> almacenesBase,
            LocalDateTime tInicio,
            LocalDateTime tFinVentana) {

        final int MAX_ITER = 40;
        final double ALPHA = 0.3; // tamaño de la RCL

        SolucionGrasp mejor = null;

        for (int it = 0; it < MAX_ITER; it++) {
            Map<Integer, EstadoAlmacen> almacenesIter = clonarAlmacenes(almacenesBase);
            List<VueloInstancia> instanciasIter = clonarInstancias(instancias);

            SolucionGrasp candidata = construirSolucionGreedyRandom(
                    envios, instanciasIter, almacenesIter, tInicio, tFinVentana, ALPHA);

            candidata = busquedaLocalSimple(
                    candidata, instanciasIter, almacenesIter, tInicio, tFinVentana);

            if (mejor == null || esMejor(candidata, mejor)) {
                mejor = candidata;
            }
        }

        return mejor;
    }

    private Map<Integer, EstadoAlmacen> clonarAlmacenes(Map<Integer, EstadoAlmacen> base) {
        Map<Integer, EstadoAlmacen> copia = new HashMap<>();
        for (EstadoAlmacen ea : base.values()) {
            copia.put(
                    ea.getIdAeropuerto(),
                    new EstadoAlmacen(
                            ea.getIdAeropuerto(),
                            ea.getCapacidadMaxima(),
                            ea.getOcupacionActual()));
        }
        return copia;
    }

    private List<VueloInstancia> clonarInstancias(List<VueloInstancia> base) {
        return new ArrayList<>(base); // record inmutable
    }

    private SolucionGrasp construirSolucionGreedyRandom(
            List<Envio> envios,
            List<VueloInstancia> instancias,
            Map<Integer, EstadoAlmacen> almacenes,
            LocalDateTime tInicio,
            LocalDateTime tFinVentana,
            double alpha) {

        List<Envio> lista = new ArrayList<>(envios);
        lista.sort(Comparator.comparing(Envio::getFechaLimiteEntrega));

        List<SolucionGrasp.AsignacionEnvioVuelo> asignaciones = new ArrayList<>();
        LocalDateTime colapso = null;

        Random rnd = new Random();

        for (Envio e : lista) {
            int q = e.getCantidad();
            if (q <= 0) continue;

            if (e.getFechaLimiteEntrega() != null &&
                    e.getFechaLimiteEntrega().isBefore(tInicio)) {

                if (colapso == null || e.getFechaLimiteEntrega().isBefore(colapso)) {
                    colapso = e.getFechaLimiteEntrega();
                }
                continue;
            }

            List<VueloInstancia> candidatos = instancias.stream()
                    .filter(vInst -> vueloPuedeLlevarEnvio(vInst, e, tInicio, tFinVentana, almacenes))
                    .collect(Collectors.toList());

            if (candidatos.isEmpty()) {
                if (e.getFechaLimiteEntrega() != null &&
                        (colapso == null || e.getFechaLimiteEntrega().isBefore(colapso))) {

                    colapso = e.getFechaLimiteEntrega();
                }
                continue;
            }

            candidatos.sort(Comparator.comparing(vInst -> calcularHolgura(e, vInst.llegada())));

            int rclSize = Math.max(1, (int) Math.round(candidatos.size() * alpha));
            List<VueloInstancia> rcl = candidatos.subList(0, rclSize);

            VueloInstancia elegido = rcl.get(rnd.nextInt(rcl.size()));

            actualizarCapacidades(e, elegido, almacenes);

            asignaciones.add(
                    new SolucionGrasp.AsignacionEnvioVuelo(
                            e, elegido.vueloBase(), e.getCantidad()));
        }

        return SolucionGrasp.builder()
                .asignaciones(asignaciones)
                .cantidadEnviosAsignados(asignaciones.size())
                .momentoColapso(colapso)
                .estadoAlmacenesFinal(clonarAlmacenes(almacenes))
                .build();
    }

    private boolean vueloPuedeLlevarEnvio(
            VueloInstancia vInst,
            Envio envio,
            LocalDateTime tInicio,
            LocalDateTime tFinVentana,
            Map<Integer, EstadoAlmacen> almacenes) {

        LocalDateTime salida = vInst.salida();
        LocalDateTime llegada = vInst.llegada();
        Vuelo vuelo = vInst.vueloBase();

        if (salida.isBefore(tInicio) || salida.isAfter(tFinVentana)) {
            return false;
        }

        if (envio.getFechaLimiteEntrega() != null &&
                llegada.isAfter(envio.getFechaLimiteEntrega())) {
            return false;
        }

        int capacidadMax = vuelo.getCapacidadMaxima();
        int capacidadUtilizada = 0;
        if (capacidadMax - capacidadUtilizada < envio.getCantidad()) {
            return false;
        }

        Integer idOrigen = vuelo.getAeropuertoOrigen().getId();
        Integer idDestino = vuelo.getAeropuertoDestino().getId();

        EstadoAlmacen origen = almacenes.get(idOrigen);
        EstadoAlmacen destino = almacenes.get(idDestino);
        if (origen == null || destino == null)
            return false;

        return destino.capacidadLibre() >= envio.getCantidad();
    }

    private long calcularHolgura(Envio envio, LocalDateTime llegadaVuelo) {
        if (envio.getFechaLimiteEntrega() == null || llegadaVuelo == null) {
            return Long.MAX_VALUE;
        }
        return Duration.between(llegadaVuelo, envio.getFechaLimiteEntrega()).toMinutes();
    }

    private void actualizarCapacidades(
            Envio envio,
            VueloInstancia vInst,
            Map<Integer, EstadoAlmacen> almacenes) {

        Vuelo vuelo = vInst.vueloBase();
        int q = envio.getCantidad();

        Integer idOrigen = vuelo.getAeropuertoOrigen().getId();
        Integer idDestino = vuelo.getAeropuertoDestino().getId();

        EstadoAlmacen origen = almacenes.get(idOrigen);
        EstadoAlmacen destino = almacenes.get(idDestino);

        if (origen != null) origen.despachar(q);
        if (destino != null) destino.recibir(q);
    }

    private SolucionGrasp busquedaLocalSimple(
            SolucionGrasp solucion,
            List<VueloInstancia> instancias,
            Map<Integer, EstadoAlmacen> almacenes,
            LocalDateTime tInicio,
            LocalDateTime tFinVentana) {

        // por ahora no hace nada
        return solucion;
    }

    private boolean esMejor(SolucionGrasp a, SolucionGrasp b) {
        if (a == null) return false;
        if (b == null) return true;

        if (a.getCantidadEnviosAsignados() != b.getCantidadEnviosAsignados()) {
            return a.getCantidadEnviosAsignados() > b.getCantidadEnviosAsignados();
        }

        LocalDateTime colA = a.getMomentoColapso();
        LocalDateTime colB = b.getMomentoColapso();

        if (colA == null && colB != null) return true;
        if (colA != null && colB == null) return false;
        if (colA == null) return false;

        return colA.isAfter(colB);
    }

    /*
     * ==================================================
     * 4. Persistencia de la solución
     * ==================================================
     */

    private void persistirSolucion(
            Simulacion simulacion,
            SolucionGrasp sol) {

        List<EnvioVuelo> rutas = sol.getAsignaciones().stream()
                .map(a -> {
                    EnvioVuelo ev = new EnvioVuelo();
                    ev.setEnvio(a.getEnvio());
                    ev.setVuelo(a.getVuelo());
                    ev.setOrdenTramo(1);
                    return ev;
                })
                .toList();

        envioVueloRepository.saveAll(rutas);

        for (SolucionGrasp.AsignacionEnvioVuelo a : sol.getAsignaciones()) {
            a.getEnvio().setEstado(EstadoEnvio.PLANIFICADO);
        }

        envioRepository.saveAll(
                sol.getAsignaciones().stream()
                        .map(SolucionGrasp.AsignacionEnvioVuelo::getEnvio)
                        .distinct()
                        .toList());
    }
}
