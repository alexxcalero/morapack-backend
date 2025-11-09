package pe.edu.pucp.morapack.models;

import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;
import pe.edu.pucp.morapack.services.servicesImp.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Getter
@Setter
@Component
public class Planificador {
    private final Grasp grasp;
    private final PlanificacionWebSocketServiceImp webSocketService;
    private final EnvioServiceImp envioService;
    private final PlanDeVueloServiceImp planDeVueloService;
    private final AeropuertoServiceImp aeropuertoService;
    private ScheduledExecutorService scheduler;
    private boolean enEjecucion = false;

    // Configuración de planificación programada
    private static final int SA_MINUTOS = 2; // Salto del algoritmo - ejecutar cada 2 minutos
    private static final int K = 120; // Factor de consumo - planificar 240 minutos adelante
    private static final int TA_SEGUNDOS = 90; // Tiempo máximo de ejecución GRASP - 1.5 minutos

    // Estado del planificador
    private AtomicInteger cicloActual = new AtomicInteger(0);
    private LocalDateTime ultimaEjecucion;
    private LocalDateTime proximaEjecucion;
    private Solucion ultimaSolucion;
    private Map<String, Object> estadisticas = new HashMap<>();
    private List<Map<String, Object>> historicoCiclos = new ArrayList<>();

    private ArrayList<Envio> enviosOriginales;
    private LocalDateTime inicioHorizonteUltimoCiclo;
    private LocalDateTime finHorizonteUltimoCiclo;
    private List<PlanDeVuelo> vuelosUltimoCiclo = new ArrayList<>();

    // Controlar saltos para planificaciones semanales o del colapso
    private LocalDateTime ultimoHorizontePlanificado;
    private LocalDateTime tiempoInicioSimulacion;

    public Planificador(Grasp grasp, PlanificacionWebSocketServiceImp webSocketService,
                       EnvioServiceImp envioService, PlanDeVueloServiceImp planDeVueloService,
                       AeropuertoServiceImp aeropuertoService) {
        this.grasp = grasp;
        this.webSocketService = webSocketService;
        this.envioService = envioService;
        this.planDeVueloService = planDeVueloService;
        this.aeropuertoService = aeropuertoService;
    }

    public void iniciarPlanificacionProgramada() {
        if(enEjecucion) {
            System.out.println("⚠️ El planificador ya está en ejecución");
            return;
        }

        enEjecucion = true;
        cicloActual.set(0);
        scheduler = Executors.newScheduledThreadPool(1);

        // ✅ ENVIAR ESTADO INICIAL VÍA WEBSOCKET
        webSocketService.enviarEstadoPlanificador(true, cicloActual.get(), "inmediato");

        System.out.println("🚀 INICIANDO PLANIFICADOR PROGRAMADO");
        System.out.printf("⚙️ Configuración: Sa=%d min, K=%d, Ta=%d seg, Sc=%d min%n", SA_MINUTOS, K, TA_SEGUNDOS, SA_MINUTOS * K);

        this.enviosOriginales = envioService.obtenerEnvios(); //this.grasp.getEnvios();  // Guardo todos los envios

        // Obtener el primer pedido como referencia temporal
        this.tiempoInicioSimulacion = obtenerPrimerPedidoTiempo();
        this.ultimoHorizontePlanificado = this.tiempoInicioSimulacion;

        System.out.printf("⏰ Tiempo de inicio de simulación: %s%n", tiempoInicioSimulacion.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

        // Ejecutar el primer ciclo inmediatamente
        ejecutarCicloPlanificacion(tiempoInicioSimulacion);

        // Programar ejecuciones posteriores cada 5 minutos
        scheduler.scheduleAtFixedRate(() -> {
            LocalDateTime tiempoActual = obtenerTiempoActualSimulacion();
            ejecutarCicloPlanificacion(tiempoActual);
        }, SA_MINUTOS, SA_MINUTOS, TimeUnit.MINUTES);
    }

    public void detenerPlanificacion() {
        if(scheduler != null) {
            scheduler.shutdown();
            try {
                if(!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch(InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        enEjecucion = false;
        // ✅ ENVIAR ESTADO DE DETENCIÓN VÍA WEBSOCKET
        webSocketService.enviarEstadoPlanificador(false, cicloActual.get(), "detenido");
        System.out.println("🛑 Planificador detenido");
    }

    private LocalDateTime obtenerPrimerPedidoTiempo() {
        if(grasp.getEnvios() == null || grasp.getEnvios().isEmpty()) {
            return LocalDateTime.now();
        }

        return grasp.getEnvios().stream()
                .map(Envio::getZonedFechaIngreso)
                .map(ZonedDateTime::toLocalDateTime)
                .min(LocalDateTime::compareTo)
                .orElse(LocalDateTime.now());
    }

    private LocalDateTime obtenerTiempoActualSimulacion() {
        // En un sistema real, esto seria el tiempo actual
        // Para la simulacion, avanzamos el tiempo en cada ejecucion
        return obtenerUltimaEjecucionTiempo().plusMinutes(SA_MINUTOS);
    }

    private LocalDateTime obtenerUltimaEjecucionTiempo() {
        // Aqui llevarias registro del ultimo tiempo de ejecucion
        // Por simplicidad, usamos una variable estatica
        return ultimoTiempoEjecucion;
    }

    private static LocalDateTime ultimoTiempoEjecucion;

    private void ejecutarCicloPlanificacion(LocalDateTime tiempoEjecucion) {
        if(!enEjecucion) return;

        long inicioCiclo = System.currentTimeMillis();
        int ciclo = cicloActual.incrementAndGet();
        ultimaEjecucion = LocalDateTime.now();
        proximaEjecucion = ultimaEjecucion.plusMinutes(SA_MINUTOS);

        System.out.printf("%n=== CICLO %d INICIADO ===%n", ciclo);
        System.out.printf("🕒 Ejecución: %s%n", ultimaEjecucion.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        try {
            // 1. Calcular horizonte temporal (Sc)
            LocalDateTime inicioHorizonte = this.ultimoHorizontePlanificado;
            LocalDateTime finHorizonte = inicioHorizonte.plusMinutes(SA_MINUTOS * K);

            System.out.printf("📊 Horizonte de planificación: %s → %s%n",
                    inicioHorizonte.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                    finHorizonte.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

            // 2. Obtener pedidos dentro del horizonte actual
            List<Envio> pedidosParaPlanificar = obtenerPedidosEnVentana(inicioHorizonte, finHorizonte);

            System.out.printf("📦 Pedidos a planificar en el ciclo %d: %d%n", ciclo, pedidosParaPlanificar.size());

            if(pedidosParaPlanificar.isEmpty()) {
                System.out.println("✅ No hay pedidos pendientes en este horizonte");

                ultimoTiempoEjecucion = tiempoEjecucion;

                // ✅ IMPORTANTE: Actualizar el horizonte aunque no haya pedidos
                this.ultimoHorizontePlanificado = finHorizonte;

                return;
            }

            this.inicioHorizonteUltimoCiclo = inicioHorizonte;
            this.finHorizonteUltimoCiclo = finHorizonte;

            // ✅ Recargar estado actual de vuelos y aeropuertos para este ciclo
            recargarDatosBase(inicioHorizonte, finHorizonte);

            // 3. Ejecutar GRASP con timeout
            Solucion solucion = ejecutarGRASPConTimeout(pedidosParaPlanificar, tiempoEjecucion);
            this.ultimaSolucion = solucion;
            actualizarEstadisticas(solucion, ciclo, System.currentTimeMillis() - inicioCiclo);

            // ✅ PERSISTIR CAMBIOS EN LA BASE DE DATOS
            try {
                persistirCambios(solucion);
                System.out.println("💾 Cambios persistidos en la base de datos");
            } catch (Exception e) {
                System.err.printf("❌ Error al persistir cambios: %s%n", e.getMessage());
                e.printStackTrace();
            }

            // ✅ ACTUALIZAR el horizonte para el próximo ciclo
            this.ultimoHorizontePlanificado = finHorizonte;

            // ✅ ENVIAR ACTUALIZACIÓN VÍA WEBSOCKET
            webSocketService.enviarActualizacionCiclo(solucion, ciclo);

            // 4. Mostrar resultados
            mostrarResultadosCiclo(solucion, pedidosParaPlanificar, ciclo);

            System.out.printf("✅ CICLO %d COMPLETADO - %d/%d envíos asignados%n", ciclo, solucion.getEnviosCompletados(), solucion.getEnvios().size());

            ultimoTiempoEjecucion = tiempoEjecucion;
//        } catch(TimeoutException e) {
//            // ✅ ENVIAR ERROR VÍA WEBSOCKET
//            webSocketService.enviarError("Timeout después de " + TA_SEGUNDOS + " segundos", ciclo);
//            System.out.printf("⏰ CICLO %d - TIMEOUT%n", ciclo);
//            actualizarEstadisticasTimeout(ciclo);
        } catch(Exception e) {
            // ✅ ENVIAR ERROR VÍA WEBSOCKET
            webSocketService.enviarError("Error: " + e.getMessage(), ciclo);
            System.err.printf("❌ CICLO %d - ERROR: %s%n", ciclo, e.getMessage());
            actualizarEstadisticasError(ciclo, e.getMessage());
        }
    }

    private List<Envio> obtenerPedidosEnVentana(LocalDateTime inicio, LocalDateTime fin) {
        List<Envio> pedidosNuevos = new ArrayList<>();

        if(enviosOriginales == null) {
            return new ArrayList<>();
        }

        for(Envio envio : enviosOriginales) {
            LocalDateTime tiempoPedido = envio.getZonedFechaIngreso().toLocalDateTime();
            if(!tiempoPedido.isBefore(inicio) && tiempoPedido.isBefore(fin)) {
                // ✅ Crear COPIA del envío para este ciclo específico
                pedidosNuevos.add(crearCopiaEnvio(envio));
            }
        }

//        return enviosOriginales.stream()
//                .filter(envio -> {
//                    LocalDateTime tiempoPedido = envio.getZonedFechaIngreso().toLocalDateTime();
//                    return !tiempoPedido.isBefore(inicio) && tiempoPedido.isBefore(fin);
//                })
//                .collect(Collectors.toList());
        return pedidosNuevos;
    }

    private Envio crearCopiaEnvio(Envio original) {
        Envio copia = new Envio();
        copia.setId(original.getId());
        copia.setCliente(original.getCliente());
        copia.setAeropuertosOrigen(new ArrayList<>(original.getAeropuertosOrigen()));
        copia.setAeropuertoDestino(original.getAeropuertoDestino());
        copia.setZonedFechaIngreso(original.getZonedFechaIngreso());
        copia.setIdEnvioPorAeropuerto(original.getIdEnvioPorAeropuerto());
        copia.setNumProductos(original.getNumProductos());

        copia.setParteAsignadas(new ArrayList<>());  // ← Lista VACIA para este ciclo

        // Si necesitas las partes asignadas previas, cópialas manualmente
        if(original.getParteAsignadas() != null && !original.getParteAsignadas().isEmpty()) {
            for(ParteAsignada parteOriginal : original.getParteAsignadas()) {
                ParteAsignada parteCopia = crearCopiaParteAsignada(parteOriginal);
                parteCopia.setEnvio(copia);
                copia.getParteAsignadas().add(parteCopia);
            }
        }

        return copia;
    }

    private ParteAsignada crearCopiaParteAsignada(ParteAsignada original) {
        ParteAsignada copia = new ParteAsignada();
        copia.setId(original.getId());
        copia.setCantidad(original.getCantidad());
        copia.setLlegadaFinal(original.getLlegadaFinal());
        copia.setAeropuertoOrigen(original.getAeropuertoOrigen());

        // Copiar la ruta (vuelos instanciados)
        if(original.getRuta() != null) {
            List<PlanDeVuelo> rutaCopia = new ArrayList<>();
            for(PlanDeVuelo vuelo : original.getRuta()) {
                rutaCopia.add(crearCopiaVuelo(vuelo));
            }
            copia.setRuta(rutaCopia);
        }

        return copia;
    }

    private PlanDeVuelo crearCopiaVuelo(PlanDeVuelo original) {
        PlanDeVuelo copia = new PlanDeVuelo();
        copia.setId(original.getId());
        copia.setZonedHoraOrigen(original.getZonedHoraOrigen());
        copia.setZonedHoraDestino(original.getZonedHoraDestino());
        copia.setCapacidadOcupada(original.getCapacidadOcupada());
        return copia;
    }

    private Solucion ejecutarGRASPConTimeout(List<Envio> pedidos, LocalDateTime tiempoEjecucion) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Solucion> future = executor.submit(() -> {
            // Preparar GRASP para este ciclo específico
            grasp.setEnvios(new ArrayList<>(pedidos));
            grasp.setEnviosPorDiaPropio();

            // Ejecutar GRASP modificado para respetar el tiempo máximo
            return ejecutarGRASPLimitado(tiempoEjecucion);
        });

        try {
            return future.get(TA_SEGUNDOS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            System.out.println("⏰ TIMEOUT: GRASP excedió el tiempo máximo de " + TA_SEGUNDOS + " segundos");
            future.cancel(true);
            return crearSolucionVacia();
        } catch (Exception e) {
            System.err.println("❌ Error en ejecución de GRASP: " + e.getMessage());
            return crearSolucionVacia();
        } finally {
            executor.shutdownNow();
        }
    }

    private Solucion ejecutarGRASPLimitado(LocalDateTime tiempoEjecucion) {
        Solucion mejorSolucion = null;
        long inicioEjecucion = System.currentTimeMillis();

        // Verificar timeout periódicamente
        if((System.currentTimeMillis() - inicioEjecucion) > (TA_SEGUNDOS * 1000 * 0.8)) {
            System.out.println("⏰ GRASP: Cerca del timeout, terminando iteraciones");
            //break;
        }

        // Los PlanDeVuelo ya representan vuelos diarios, así que los usamos directamente
        ArrayList<PlanDeVuelo> planesDeVuelo = planDeVueloService.obtenerListaPlanesDeVuelo(); //grasp.getPlanesDeVuelo();
        if(planesDeVuelo == null || planesDeVuelo.isEmpty()) {
            return null;
        }

        ArrayList<Envio> enviosParaProgramar = grasp.getEnvios();

        // Inicializar los caches necesarios para trabajar con estos vuelos
        // Pasar los envíos para filtrar por ventana temporal
        grasp.inicializarCachesParaVuelos(planesDeVuelo, enviosParaProgramar);

        // Ejecutar GRASP para este día
        Solucion solucionDia = grasp.ejecutarGrasp(enviosParaProgramar, planesDeVuelo);

        if(mejorSolucion == null || grasp.esMejor(solucionDia, mejorSolucion)) {
            mejorSolucion = solucionDia;
        }

        /*
        for(LocalDateTime dia : grasp.getDias()) {
            // Verificar timeout periódicamente
            if((System.currentTimeMillis() - inicioEjecucion) > (TA_SEGUNDOS * 1000 * 0.8)) {
                System.out.println("⏰ GRASP: Cerca del timeout, terminando iteraciones");
                break;
            }

            List<Envio> enviosDelDia = grasp.getEnviosPorDia().get(dia);
            if(enviosDelDia == null || enviosDelDia.isEmpty()) {
                continue;
            }

            // Los PlanDeVuelo ya representan vuelos diarios, así que los usamos directamente
            ArrayList<PlanDeVuelo> planesDeVuelo = grasp.getPlanesDeVuelo();
            if(planesDeVuelo == null || planesDeVuelo.isEmpty()) {
                continue;
            }

            // Inicializar los caches necesarios para trabajar con estos vuelos
            // Pasar los envíos para filtrar por ventana temporal
            grasp.inicializarCachesParaVuelos(planesDeVuelo, enviosDelDia);

            // Ejecutar GRASP para este día
            Solucion solucionDia = grasp.ejecutarGrasp(enviosDelDia, planesDeVuelo);

            if(mejorSolucion == null || grasp.esMejor(solucionDia, mejorSolucion)) {
                mejorSolucion = solucionDia;
            }
        }
        */

        return mejorSolucion != null ? mejorSolucion : crearSolucionVacia();
    }

    private Solucion crearSolucionVacia() {
        return new Solucion(new ArrayList<>(), new ArrayList<>());
    }

    public String getProximoCiclo() {
        if (!enEjecucion || proximaEjecucion == null) {
            return "No programado";
        }
        return proximaEjecucion.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    public Map<String, Object> getEstadisticasActuales() {
        return new HashMap<>(estadisticas);
    }

    private void actualizarEstadisticas(Solucion solucion, int ciclo, long duracionMs) {
        Map<String, Object> statsCiclo = new HashMap<>();
        statsCiclo.put("ciclo", ciclo);
        statsCiclo.put("timestamp", LocalDateTime.now().toString());
        statsCiclo.put("duracionSegundos", duracionMs / 1000.0);
        statsCiclo.put("totalEnvios", solucion.getEnvios().size());
        statsCiclo.put("enviosCompletados", solucion.getEnviosCompletados());
        statsCiclo.put("tasaExito", solucion.getEnvios().size() > 0 ?
                (solucion.getEnviosCompletados() * 100.0 / solucion.getEnvios().size()) : 0);

        historicoCiclos.add(statsCiclo);

        // Mantener solo últimos 50 ciclos en histórico
        if (historicoCiclos.size() > 50) {
            historicoCiclos.remove(0);
        }

        // Actualizar estadísticas globales
        this.estadisticas.put("totalCiclosCompletados", ciclo);
        this.estadisticas.put("ultimoCicloExitoso", true);
        this.estadisticas.put("ultimaEjecucion", LocalDateTime.now().toString());
        this.estadisticas.put("promedioEjecucionSegundos", calcularPromedioEjecucion());
        this.estadisticas.put("totalEnviosProcesados", calcularTotalEnviosProcesados());
    }

    private void actualizarEstadisticasVacio(int ciclo) {
        Map<String, Object> statsCiclo = new HashMap<>();
        statsCiclo.put("ciclo", ciclo);
        statsCiclo.put("timestamp", LocalDateTime.now().toString());
        statsCiclo.put("duracionSegundos", 0.0);
        statsCiclo.put("totalEnvios", 0);
        statsCiclo.put("enviosCompletados", 0);
        statsCiclo.put("tasaExito", 0.0);
        statsCiclo.put("estado", "sin_pedidos");

        historicoCiclos.add(statsCiclo);
    }

    private void actualizarEstadisticasTimeout(int ciclo) {
        Map<String, Object> statsCiclo = new HashMap<>();
        statsCiclo.put("ciclo", ciclo);
        statsCiclo.put("timestamp", LocalDateTime.now().toString());
        statsCiclo.put("duracionSegundos", TA_SEGUNDOS);
        statsCiclo.put("totalEnvios", 0);
        statsCiclo.put("enviosCompletados", 0);
        statsCiclo.put("tasaExito", 0.0);
        statsCiclo.put("estado", "timeout");

        historicoCiclos.add(statsCiclo);
        this.estadisticas.put("ultimoCicloExitoso", false);
        this.estadisticas.put("timeouts",
                (int) this.estadisticas.getOrDefault("timeouts", 0) + 1);
    }

    private void actualizarEstadisticasError(int ciclo, String error) {
        Map<String, Object> statsCiclo = new HashMap<>();
        statsCiclo.put("ciclo", ciclo);
        statsCiclo.put("timestamp", LocalDateTime.now().toString());
        statsCiclo.put("duracionSegundos", 0.0);
        statsCiclo.put("totalEnvios", 0);
        statsCiclo.put("enviosCompletados", 0);
        statsCiclo.put("tasaExito", 0.0);
        statsCiclo.put("estado", "error");
        statsCiclo.put("mensajeError", error);

        historicoCiclos.add(statsCiclo);
        this.estadisticas.put("ultimoCicloExitoso", false);
        this.estadisticas.put("errores",
                (int) this.estadisticas.getOrDefault("errores", 0) + 1);
    }

    private double calcularPromedioEjecucion() {
        return historicoCiclos.stream()
                .filter(cycle -> cycle.containsKey("duracionSegundos"))
                .mapToDouble(cycle -> (Double) cycle.get("duracionSegundos"))
                .average()
                .orElse(0.0);
    }

    private int calcularTotalEnviosProcesados() {
        return historicoCiclos.stream()
                .filter(cycle -> cycle.containsKey("totalEnvios"))
                .mapToInt(cycle -> (Integer) cycle.get("totalEnvios"))
                .sum();
    }

    // Metodo adicional útil para el controller
    public List<Map<String, Object>> getHistoricoCiclos(int ultimosCiclos) {
        int fromIndex = Math.max(0, historicoCiclos.size() - ultimosCiclos);
        return new ArrayList<>(historicoCiclos.subList(fromIndex, historicoCiclos.size()));
    }

    private void mostrarResultadosCiclo(Solucion solucion, List<Envio> pedidosProcesados, Integer ciclo) {
        System.out.println("📊 RESULTADOS DEL CICLO:");
        System.out.printf("   • Pedidos procesados: %d%n", pedidosProcesados.size());
        System.out.printf("   • Pedidos completados: %d%n", solucion.getEnviosCompletados());

        if(!pedidosProcesados.isEmpty()) {
            double tasaExito = (solucion.getEnviosCompletados() * 100.0) / pedidosProcesados.size();
            System.out.printf("   • Tasa de éxito: %.1f%%%n", tasaExito);
        }

        System.out.printf("   • Tiempo medio de entrega: %s%n",
                formatDuracion(solucion.getLlegadaMediaPonderada()));


        System.out.printf("%n📋 DETALLE DE RUTAS ASIGNADAS - CICLO %d%n", ciclo);
        System.out.println("=".repeat(80));

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM dd HH:mm", Locale.forLanguageTag("es-ES"));

        for(Envio envio : solucion.getEnvios()) {
            if(envio.estaCompleto() || !envio.getParteAsignadas().isEmpty()) {
                System.out.printf("📦 PEDIDO %s → %s (%d unidades)%n",
                        envio.getId(),
                        envio.getAeropuertoDestino().getCodigo(),
                        envio.getNumProductos());

                System.out.printf("   📍 Orígenes posibles: %s%n",
                        envio.getAeropuertosOrigen().stream()
                                .map(Aeropuerto::getCodigo)
                                .collect(Collectors.joining(", ")));

                System.out.printf("   ⏰ Aparición: %s%n", formatFechaConOffset(envio.getZonedFechaIngreso(), envio.getFechaIngreso(), envio.getHusoHorarioDestino(), formatter));

                int parteNum = 1;
                for(ParteAsignada parte : envio.getParteAsignadas()) {
                    System.out.printf("   🚚 Parte %d (%d unidades desde %s):%n", parteNum, parte.getCantidad(), parte.getAeropuertoOrigen().getCodigo());

                    for(int i = 0; i < parte.getRuta().size(); i++) {
                        PlanDeVuelo vuelo = parte.getRuta().get(i);
                        System.out.printf("      ✈️  %s → %s | %s - %s | Cap: %d/%d%n",
                                obtenerAeropuertoPorId(vuelo.getCiudadOrigen()).getCodigo(),
                                obtenerAeropuertoPorId(vuelo.getCiudadDestino()).getCodigo(),
                                formatFechaConOffset(vuelo.getZonedHoraOrigen(), vuelo.getHoraOrigen(), vuelo.getHusoHorarioOrigen(), formatter),
                                formatFechaConOffset(vuelo.getZonedHoraDestino(), vuelo.getHoraDestino(), vuelo.getHusoHorarioDestino(), formatter),
                                vuelo.getCapacidadOcupada(),
                                vuelo.getCapacidadMaxima());
                    }

                    System.out.printf("      🏁 Llegada final: %s%n", formatFechaConOffset(parte.getLlegadaFinal(), null, null, formatter));
                    parteNum++;
                }
                System.out.println();
            }
        }
        System.out.println("=" .repeat(80));

    }

    private Aeropuerto obtenerAeropuertoPorId(Integer id) {
        for(Aeropuerto aeropuerto : this.grasp.getAeropuertos())
            if(aeropuerto.getId().equals(id))
                return aeropuerto;

        return null;
    }

    // Metodo para ver el estado actual del horizonte
    public Map<String, Object> getEstadoHorizonte() {
        Map<String, Object> estado = new HashMap<>();
        estado.put("tiempoInicioSimulacion", tiempoInicioSimulacion);
        estado.put("ultimoHorizontePlanificado", ultimoHorizontePlanificado);
        estado.put("proximoHorizonte", ultimoHorizontePlanificado.plusMinutes(SA_MINUTOS * K));
        //estado.put("pedidosYaPlanificados", pedidosYaPlanificados.size());
        return estado;
    }

    private String formatDuracion(Duration duration) {
        if(duration == null) return "N/A";
        long hours = duration.toHours();
        long minutes = duration.minusHours(hours).toMinutes();
        return String.format("%d h %d min", hours, minutes);
    }

    private String formatFechaConOffset(ZonedDateTime zoned, LocalDateTime local, String husoHorario, DateTimeFormatter baseFormatter) {
        if(zoned != null) {
            ZoneOffset offset = zoned.getOffset();
            String offsetStr = offset.getId().equals("Z") ? "+00:00" : offset.getId();
            return String.format("%s (UTC%s)", zoned.format(baseFormatter), offsetStr);
        }

        if(local != null && husoHorario != null) {
            int offsetHoras;
            try {
                offsetHoras = Integer.parseInt(husoHorario);
            } catch(NumberFormatException e) {
                offsetHoras = 0;
            }
            return String.format("%s (UTC%+03d:00)", local.format(baseFormatter), offsetHoras);
        }

        if(local != null) {
            return String.format("%s (UTC%+03d:00)", local.format(baseFormatter), 0);
        }

        return "N/A";
    }

    public List<PlanDeVuelo> getVuelosUltimoCiclo() {
        return vuelosUltimoCiclo != null ? vuelosUltimoCiclo : Collections.emptyList();
    }

    public List<Map<String, Object>> getAsignacionesUltimoCiclo() {
        if(ultimaSolucion == null || ultimaSolucion.getEnvios() == null) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> asignaciones = new ArrayList<>();

        for(Envio envio : ultimaSolucion.getEnvios()) {
            if(envio.getParteAsignadas() == null) continue;

            for(ParteAsignada parte : envio.getParteAsignadas()) {
                if(parte.getRuta() == null) continue;

                for(PlanDeVuelo vuelo : parte.getRuta()) {
                    if(vuelo.getId() == null) continue;

                    Map<String, Object> registro = new HashMap<>();
                    registro.put("vueloId", vuelo.getId());
                    registro.put("envioId", envio.getId());
                    registro.put("cantidad", parte.getCantidad());
                    registro.put("envioIdPorAeropuerto", envio.getIdEnvioPorAeropuerto());

                    if(envio.getCliente() != null) {
                        registro.put("cliente", envio.getCliente());
                    }

                    if(parte.getAeropuertoOrigen() != null) {
                        registro.put("aeropuertoOrigen", parte.getAeropuertoOrigen().getCodigo());
                    }

                    asignaciones.add(registro);
                }
            }
        }

        return asignaciones;
    }

    public LocalDateTime getInicioHorizonteUltimoCiclo() {
        return inicioHorizonteUltimoCiclo;
    }

    public LocalDateTime getFinHorizonteUltimoCiclo() {
        return finHorizonteUltimoCiclo;
    }

    /**
     * Recarga desde la base de datos el estado actual de planes de vuelo y aeropuertos.
     * Esto garantiza que cada ciclo utilice los mismos registros persistidos y respete la capacidad disponible.
     */
    private void recargarDatosBase(LocalDateTime inicioHorizonte, LocalDateTime finHorizonte) {
        ArrayList<PlanDeVuelo> planesActualizados = planDeVueloService.obtenerListaPlanesDeVuelo();
        ArrayList<Aeropuerto> aeropuertosActualizados = aeropuertoService.obtenerTodosAeropuertos();

        ArrayList<PlanDeVuelo> planesFiltrados = planesActualizados.stream()
                .filter(plan -> {
                    LocalDateTime salida = plan.getHoraOrigen();
                    LocalDateTime llegada = plan.getHoraDestino();
                    if(salida == null || llegada == null) return false;

                    boolean salidaEnRango = !salida.isBefore(inicioHorizonte) && !salida.isAfter(finHorizonte);
                    boolean llegadaEnRango = !llegada.isBefore(inicioHorizonte) && !llegada.isAfter(finHorizonte);
                    boolean cruzaHorizonte = salida.isBefore(inicioHorizonte) && llegada.isAfter(inicioHorizonte);
                    return salidaEnRango || llegadaEnRango || cruzaHorizonte;
                })
                .collect(Collectors.toCollection(ArrayList::new));

        this.vuelosUltimoCiclo = planesFiltrados;

        grasp.setPlanesDeVuelo(planesActualizados);
        grasp.setAeropuertos(aeropuertosActualizados);
        grasp.setHubsPropio();
    }

    /**
     * Persiste los cambios en la base de datos después de ejecutar GRASP
     * - Guarda las partes asignadas nuevas con sus rutas
     * - Actualiza la capacidad ocupada de los planes de vuelo
     * - Actualiza la capacidad ocupada de los aeropuertos
     */
    private void persistirCambios(Solucion solucion) {
        if (solucion == null || solucion.getEnvios() == null || solucion.getEnvios().isEmpty()) {
            return;
        }

        // Conjuntos para rastrear qué objetos fueron modificados
        Set<Integer> planesDeVueloModificados = new HashSet<>();
        Set<Integer> aeropuertosModificados = new HashSet<>();
        List<Envio> enviosParaActualizar = new ArrayList<>();

        // 1. Procesar cada envío y sus partes asignadas
        for(Envio envioCopia : solucion.getEnvios()) {
            if(envioCopia.getId() == null) {
                continue; // Saltar envíos sin ID (nuevos)
            }

            // Cargar el envío real de la BD
            Optional<Envio> envioOpt = envioService.obtenerEnvioPorId(envioCopia.getId());
            if(envioOpt.isEmpty()) {
                System.err.printf("⚠️ No se encontró el envío %d en la BD%n", envioCopia.getId());
                continue;
            }

            Envio envioReal = envioOpt.get();

            // Procesar cada parte asignada nueva
            if(envioCopia.getParteAsignadas() != null) {
                for(ParteAsignada parteCopia : envioCopia.getParteAsignadas()) {
                    // Verificar si esta parte ya existe en el envío real
                    boolean parteExiste = false;
                    if(envioReal.getParteAsignadas() != null) {
                        for(ParteAsignada parteExistente : envioReal.getParteAsignadas()) {
                            if(parteExistente.getId() != null && parteExistente.getId().equals(parteCopia.getId())) {
                                parteExiste = true;
                                break;
                            }
                        }
                    }

                    // Si la parte es nueva (sin ID) o no existe, crearla
                    if(parteCopia.getId() == null || !parteExiste) {
                        // Crear nueva parte asignada
                        ParteAsignada nuevaParte = new ParteAsignada();
                        nuevaParte.setEnvio(envioReal);
                        nuevaParte.setCantidad(parteCopia.getCantidad());
                        nuevaParte.setLlegadaFinal(parteCopia.getLlegadaFinal());
                        nuevaParte.setAeropuertoOrigen(parteCopia.getAeropuertoOrigen());

                        // Copiar la ruta transient - cargar los planes de vuelo reales de BD
                        if(parteCopia.getRuta() != null && !parteCopia.getRuta().isEmpty()) {
                            List<PlanDeVuelo> rutaReal = new ArrayList<>();
                            for(PlanDeVuelo vueloCopia : parteCopia.getRuta()) {
                                if(vueloCopia.getId() != null) {
                                    Optional<PlanDeVuelo> vueloOpt = planDeVueloService.obtenerPlanDeVueloPorId(vueloCopia.getId());
                                    if(vueloOpt.isPresent()) {
                                        rutaReal.add(vueloOpt.get());
                                        planesDeVueloModificados.add(vueloCopia.getId());
                                        if(vueloCopia.getCiudadDestino() != null) {
                                            aeropuertosModificados.add(vueloCopia.getCiudadDestino());
                                        }
                                    }
                                }
                            }
                            nuevaParte.setRuta(rutaReal);
                            // Sincronizar la ruta con BD antes de persistir
                            nuevaParte.sincronizarRutaConBD();
                        }

                        // Agregar a la lista de partes del envío
                        if(envioReal.getParteAsignadas() == null) {
                            envioReal.setParteAsignadas(new ArrayList<>());
                        }
                        envioReal.getParteAsignadas().add(nuevaParte);
                    }
                }
            }

            enviosParaActualizar.add(envioReal);
        }

        // 2. Actualizar planes de vuelo con su capacidad ocupada
        List<PlanDeVuelo> planesParaActualizar = new ArrayList<>();
        for(Integer planId : planesDeVueloModificados) {
            Optional<PlanDeVuelo> planOpt = planDeVueloService.obtenerPlanDeVueloPorId(planId);
            if(planOpt.isPresent()) {
                PlanDeVuelo planReal = planOpt.get();
                // Buscar el plan en el grasp para obtener la capacidad actualizada
        Integer capacidadAsignada = calcularCapacidadAsignada(planId, solucion.getEnvios());
        if(capacidadAsignada != null) {
            planReal.setCapacidadOcupada(capacidadAsignada);
            planesParaActualizar.add(planReal);
        }
            }
        }

        // 3. Actualizar aeropuertos con su capacidad ocupada
        List<Aeropuerto> aeropuertosParaActualizar = new ArrayList<>();
        for(Integer aeropuertoId : aeropuertosModificados) {
            Optional<Aeropuerto> aeropuertoOpt = aeropuertoService.obtenerAeropuertoPorId(aeropuertoId);
            if (aeropuertoOpt.isPresent()) {
                Aeropuerto aeropuertoReal = aeropuertoOpt.get();
                // Buscar el aeropuerto en el grasp para obtener la capacidad actualizada
                Aeropuerto aeropuertoGrasp = obtenerAeropuertoPorId(aeropuertoId);
                if (aeropuertoGrasp != null && aeropuertoGrasp.getCapacidadOcupada() != null) {
                    aeropuertoReal.setCapacidadOcupada(aeropuertoGrasp.getCapacidadOcupada());
                    aeropuertosParaActualizar.add(aeropuertoReal);
                }
            }
        }

        // 4. Persistir todos los cambios
        try {
            // Guardar envíos (esto guardará las partes asignadas por cascade)
            for (Envio envio : enviosParaActualizar) {
                envioService.insertarEnvio(envio);
            }

            // Guardar planes de vuelo
            if (!planesParaActualizar.isEmpty()) {
                planDeVueloService.insertarListaPlanesDeVuelo(new ArrayList<>(planesParaActualizar));
            }

            // Guardar aeropuertos
            if (!aeropuertosParaActualizar.isEmpty()) {
                aeropuertoService.insertarListaAeropuertos(new ArrayList<>(aeropuertosParaActualizar));
            }

        } catch (Exception e) {
            System.err.printf("❌ Error al guardar en BD: %s%n", e.getMessage());
            throw e;
        }
    }

    /**
     * Encuentra un plan de vuelo en el grasp por su ID
     */
    private Integer calcularCapacidadAsignada(Integer planId, List<Envio> envios) {
        if(envios == null) return null;

        int total = 0;
        for(Envio envio : envios) {
            if(envio.getParteAsignadas() == null) continue;

            for(ParteAsignada parte : envio.getParteAsignadas()) {
                if(parte.getRuta() == null) continue;

                for(PlanDeVuelo vuelo : parte.getRuta()) {
                    if(vuelo.getId() != null && vuelo.getId().equals(planId)) {
                        total += parte.getCantidad();
                        break;
                    }
                }
            }
        }
        return total;
    }
}
