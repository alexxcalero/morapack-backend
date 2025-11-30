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
    private ScheduledFuture<?> tareaProgramada;
    private ScheduledFuture<?> tareaLiberacionProductos;
    private boolean enEjecucion = false;
    private LocalDateTime tiempoSimuladoActual; // Tiempo simulado actual para verificaciones de liberación

    // Configuración de planificación programada
    private static final int SA_MINUTOS = 2; // Salto del algoritmo - ejecutar cada 2 minutos
    private static final int K_NORMAL = 120; // Factor de consumo - planificar 240 minutos adelante (modo normal y
                                             // semanal)
    private static final int K_COLAPSO = 240; // Factor de consumo - planificar 480 minutos adelante (modo colapso)
    private static final int TA_SEGUNDOS = 100; // Tiempo máximo de ejecución GRASP - 1.5 minutos

    // Método para obtener el valor de K según el modo de simulación
    private int obtenerK() {
        return modoSimulacion == ModoSimulacion.COLAPSO ? K_COLAPSO : K_NORMAL;
    }

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

    // ⚡ SISTEMA DE EVENTOS TEMPORALES: Separar planificación (GRASP) de ejecución temporal
    // Scheduler dedicado para eventos temporales (ejecución individual en tiempo exacto)
    private ScheduledExecutorService schedulerEventos;
    // Lista de ScheduledFuture para poder cancelar eventos si es necesario
    private List<ScheduledFuture<?>> eventosProgramados = new ArrayList<>();

    /**
     * Clase interna para representar eventos temporales (llegada/salida de vuelos)
     */
    private static class EventoTemporal {
        private final ZonedDateTime tiempoEvento;
        private final TipoEvento tipo;
        private final PlanDeVuelo vuelo;
        private final ParteAsignada parte;
        private final Integer cantidad;
        private final Integer aeropuertoId;
        private final boolean esPrimerVuelo;  // Si es el primer vuelo de la ruta
        private final boolean esUltimoVuelo;  // Si es el último vuelo de la ruta
        private final Envio envio;  // Referencia al envío para cambiar estados

        enum TipoEvento {
            LLEGADA_VUELO,  // Vuelo llega a destino -> asignar capacidad en aeropuerto destino
            SALIDA_VUELO    // Vuelo sale de origen -> desasignar capacidad en aeropuerto origen (solo si no es primer vuelo)
        }

        public EventoTemporal(ZonedDateTime tiempoEvento, TipoEvento tipo, PlanDeVuelo vuelo,
                             ParteAsignada parte, Integer cantidad, Integer aeropuertoId,
                             boolean esPrimerVuelo, boolean esUltimoVuelo, Envio envio) {
            this.tiempoEvento = tiempoEvento;
            this.tipo = tipo;
            this.vuelo = vuelo;
            this.parte = parte;
            this.cantidad = cantidad;
            this.aeropuertoId = aeropuertoId;
            this.esPrimerVuelo = esPrimerVuelo;
            this.esUltimoVuelo = esUltimoVuelo;
            this.envio = envio;
        }

        public ZonedDateTime getTiempoEvento() { return tiempoEvento; }
        public TipoEvento getTipo() { return tipo; }
        public PlanDeVuelo getVuelo() { return vuelo; }
        public ParteAsignada getParte() { return parte; }
        public Integer getCantidad() { return cantidad; }
        public Integer getAeropuertoId() { return aeropuertoId; }
        public boolean isPrimerVuelo() { return esPrimerVuelo; }
        public boolean isUltimoVuelo() { return esUltimoVuelo; }
        public Envio getEnvio() { return envio; }
    }

    // Modos de simulación
    public enum ModoSimulacion {
        NORMAL, // Modo original - planifica todos los pedidos
        SEMANAL, // Simulación semanal con fecha inicio y fin
        COLAPSO // Simulación de colapso - solo fecha inicio
    }

    private ModoSimulacion modoSimulacion = ModoSimulacion.NORMAL;
    private LocalDateTime fechaInicioSimulacion;
    private LocalDateTime fechaFinSimulacion;

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
        iniciarPlanificacionProgramada(ModoSimulacion.NORMAL, null, null);
    }

    public void iniciarPlanificacionProgramada(ModoSimulacion modo, LocalDateTime fechaInicio, LocalDateTime fechaFin) {
        if (enEjecucion) {
            System.out.println("⚠️ El planificador ya está en ejecución");
            return;
        }

        enEjecucion = true;
        cicloActual.set(0);
        scheduler = Executors.newScheduledThreadPool(1);
        if (scheduler == null) {
            System.err.println("❌ Error crítico: No se pudo crear el ScheduledExecutorService");
            enEjecucion = false;
            return;
        }
        System.out.println("✅ Scheduler inicializado correctamente");
        this.modoSimulacion = modo;
        this.fechaInicioSimulacion = fechaInicio;
        this.fechaFinSimulacion = fechaFin;

        // ✅ ENVIAR ESTADO INICIAL VÍA WEBSOCKET
        webSocketService.enviarEstadoPlanificador(true, cicloActual.get(), "inmediato");

        System.out.println("🚀 INICIANDO PLANIFICADOR PROGRAMADO");
        int kActual = obtenerK();
        System.out.printf("⚙️ Configuración: Sa=%d min, K=%d, Ta=%d seg, Sc=%d min%n", SA_MINUTOS, kActual, TA_SEGUNDOS,
                SA_MINUTOS * kActual);
        System.out.printf("📋 Modo de simulación: %s%n", modo);

        // ⚡ OPTIMIZACIÓN CRÍTICA: No cargar todos los envíos en memoria
        // En su lugar, cargaremos solo los envíos del horizonte actual en cada ciclo
        // Esto evita cargar decenas de miles de envíos en memoria
        this.enviosOriginales = null; // No mantener todos los envíos en memoria
        System.out.println("📊 [iniciarPlanificacionProgramada] Envíos se cargarán por ciclo desde BD");

        // Determinar tiempo de inicio según el modo
        try {
            if (modo == ModoSimulacion.SEMANAL || modo == ModoSimulacion.COLAPSO) {
                if (fechaInicio == null) {
                    System.err.println("❌ Error: fechaInicio es null en modo " + modo);
                    enEjecucion = false;
                    if (scheduler != null) {
                        scheduler.shutdown();
                        scheduler = null;
                    }
                    return;
                }
                this.tiempoInicioSimulacion = fechaInicio;
                this.ultimoHorizontePlanificado = fechaInicio;
                this.tiempoSimuladoActual = fechaInicio; // Inicializar tiempo simulado para verificaciones
                System.out.printf("⏰ Tiempo de inicio de simulación: %s%n",
                        fechaInicio.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
                if (modo == ModoSimulacion.SEMANAL) {
                    if (fechaFin == null) {
                        System.err.println("❌ Error: fechaFin es null en modo SEMANAL");
                        enEjecucion = false;
                        if (scheduler != null) {
                            scheduler.shutdown();
                            scheduler = null;
                        }
                        return;
                    }
                    System.out.printf("⏰ Tiempo de fin de simulación: %s%n",
                            fechaFin.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
                }
            } else {
                // Modo normal - obtener el primer pedido como referencia temporal
                this.tiempoInicioSimulacion = obtenerPrimerPedidoTiempo();
                this.ultimoHorizontePlanificado = this.tiempoInicioSimulacion;
                this.tiempoSimuladoActual = this.tiempoInicioSimulacion; // Inicializar tiempo simulado para
                                                                         // verificaciones
                System.out.printf("⏰ Tiempo de inicio de simulación: %s%n",
                        tiempoInicioSimulacion.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
            }
        } catch (Exception e) {
            System.err.printf("❌ Error al determinar tiempo de inicio: %s%n", e.getMessage());
            e.printStackTrace();
            enEjecucion = false;
            if (scheduler != null) {
                scheduler.shutdown();
                scheduler = null;
            }
            return;
        }

        // Validar que el scheduler se haya inicializado correctamente
        if (scheduler == null) {
            System.err.println("❌ Error: scheduler no se inicializó correctamente");
            enEjecucion = false;
            return;
        }

        // ⚡ Inicializar scheduler dedicado para eventos temporales
        if (schedulerEventos == null) {
            schedulerEventos = Executors.newScheduledThreadPool(10); // Pool de 10 hilos para eventos
            System.out.println("⏰ Scheduler de eventos temporales inicializado");
        }

        // Ejecutar el primer ciclo inmediatamente
        try {
            ejecutarCicloPlanificacion(tiempoInicioSimulacion);
        } catch (Exception e) {
            System.err.printf("❌ Error al ejecutar el primer ciclo: %s%n", e.getMessage());
            e.printStackTrace();
            // Continuar con la programación aunque haya error en el primer ciclo
        }

        // Programar ejecuciones posteriores cada SA_MINUTOS minutos
        if (scheduler != null) {
            tareaProgramada = scheduler.scheduleAtFixedRate(() -> {
                LocalDateTime tiempoActual = obtenerTiempoActualSimulacion();
                ejecutarCicloPlanificacion(tiempoActual);
            }, SA_MINUTOS, SA_MINUTOS, TimeUnit.MINUTES);

            // ✅ Programar tarea separada para verificar liberación de productos cada hora
            // simulada
            // Esta tarea se ejecuta más frecuentemente para verificar liberaciones
            // exactamente cuando pasen 2 horas
            // Mapeo: 1 hora simulada = 30 segundos reales (ajustable según necesidad)
            tareaLiberacionProductos = scheduler.scheduleAtFixedRate(() -> {
                if (!enEjecucion)
                    return;

                // Avanzar el tiempo simulado en 1 hora para verificaciones
                LocalDateTime nuevoTiempoSimulado = tiempoSimuladoActual.plusHours(1);

                // No avanzar más allá del horizonte actual de planificación
                // Esto asegura que no verificamos tiempos futuros que aún no han sido
                // planificados
                if (nuevoTiempoSimulado.isAfter(ultimoHorizontePlanificado)) {
                    // Si el tiempo simulado alcanzó el horizonte, no avanzar más
                    // La verificación se hará cuando el horizonte avance en el siguiente ciclo
                    return;
                }

                tiempoSimuladoActual = nuevoTiempoSimulado;

                // Verificar y liberar productos que llegaron hace más de 2 horas
                liberarProductosEntregados(tiempoSimuladoActual);
            }, 30, 30, TimeUnit.SECONDS); // Ejecutar cada 30 segundos reales = 1 hora simulada
        } else {
            System.err.println("❌ Error: scheduler es null, no se puede programar la tarea");
            enEjecucion = false;
        }
    }

    public void detenerPlanificacion() {
        // Cancelar la tarea programada primero para evitar RejectedExecutionException
        if (tareaProgramada != null && !tareaProgramada.isCancelled()) {
            tareaProgramada.cancel(false);
            tareaProgramada = null;
        }

        // Cancelar la tarea de liberación de productos
        if (tareaLiberacionProductos != null && !tareaLiberacionProductos.isCancelled()) {
            tareaLiberacionProductos.cancel(false);
            tareaLiberacionProductos = null;
        }

        // ⚡ Cancelar todos los eventos temporales programados
        if (eventosProgramados != null) {
            int eventosCancelados = 0;
            for (ScheduledFuture<?> evento : eventosProgramados) {
                if (evento != null && !evento.isCancelled() && !evento.isDone()) {
                    evento.cancel(false);
                    eventosCancelados++;
                }
            }
            eventosProgramados.clear();
            if (eventosCancelados > 0) {
                System.out.printf("🛑 Cancelados %d eventos temporales pendientes%n", eventosCancelados);
            }
        }

        // Cerrar scheduler de eventos temporales
        if (schedulerEventos != null) {
            schedulerEventos.shutdown();
            try {
                if (!schedulerEventos.awaitTermination(10, TimeUnit.SECONDS)) {
                    schedulerEventos.shutdownNow();
                }
            } catch (InterruptedException e) {
                schedulerEventos.shutdownNow();
                Thread.currentThread().interrupt();
            }
            schedulerEventos = null;
        }

        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }
        enEjecucion = false;
        // ✅ ENVIAR ESTADO DE DETENCIÓN VÍA WEBSOCKET
        webSocketService.enviarEstadoPlanificador(false, cicloActual.get(), "detenido");
        System.out.println("🛑 Planificador detenido");
    }

    public boolean estaEnEjecucion() {
        return enEjecucion;
    }

    private LocalDateTime obtenerPrimerPedidoTiempo() {
        if (grasp.getEnvios() == null || grasp.getEnvios().isEmpty()) {
            return LocalDateTime.now();
        }

        // Convertir a UTC para comparar correctamente considerando husos horarios
        ZonedDateTime primerPedidoZoned = grasp.getEnvios().stream()
                .map(Envio::getZonedFechaIngreso)
                .map(zdt -> zdt.withZoneSameInstant(ZoneOffset.UTC))
                .min(ZonedDateTime::compareTo)
                .orElse(ZonedDateTime.now(ZoneOffset.UTC));

        // Retornar como LocalDateTime en UTC para mantener consistencia
        return primerPedidoZoned.toLocalDateTime();
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
        if (!enEjecucion)
            return;

        long inicioCiclo = System.currentTimeMillis();
        int ciclo = cicloActual.incrementAndGet();
        ultimaEjecucion = LocalDateTime.now();
        proximaEjecucion = ultimaEjecucion.plusMinutes(SA_MINUTOS);

        System.out.printf("%n=== CICLO %d INICIADO ===%n", ciclo);
        System.out.printf("🕒 Ejecución: %s%n",
                ultimaEjecucion.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        try {
            // 1. Verificar si se alcanzó la fecha fin (solo para modo SEMANAL)
            if (modoSimulacion == ModoSimulacion.SEMANAL && fechaFinSimulacion != null) {
                if (this.ultimoHorizontePlanificado.isAfter(fechaFinSimulacion) ||
                        this.ultimoHorizontePlanificado.isEqual(fechaFinSimulacion)) {
                    System.out.println("🏁 Simulación semanal completada - se alcanzó la fecha fin");
                    detenerPlanificacion();
                    return;
                }
            }

            // 2. Calcular horizonte temporal (Sc)
            LocalDateTime inicioHorizonte = this.ultimoHorizontePlanificado;
            int kActual = obtenerK();
            LocalDateTime finHorizonte = inicioHorizonte.plusMinutes(SA_MINUTOS * kActual);

            // En modo SEMANAL, limitar el horizonte a la fecha fin
            if (modoSimulacion == ModoSimulacion.SEMANAL && fechaFinSimulacion != null) {
                if (finHorizonte.isAfter(fechaFinSimulacion)) {
                    finHorizonte = fechaFinSimulacion;
                }
            }

            System.out.printf("📊 Horizonte de planificación: %s → %s%n",
                    inicioHorizonte.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                    finHorizonte.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

            // 3. Obtener pedidos dentro del horizonte actual
            List<Envio> pedidosParaPlanificar = obtenerPedidosEnVentana(inicioHorizonte, finHorizonte);

            System.out.printf("📦 Pedidos a planificar en el ciclo %d: %d%n", ciclo, pedidosParaPlanificar.size());

            this.inicioHorizonteUltimoCiclo = inicioHorizonte;
            this.finHorizonteUltimoCiclo = finHorizonte;

            // ✅ Sincronizar tiempo simulado para verificaciones con el inicio del horizonte
            // Esto asegura que el tiempo simulado no se quede atrás del horizonte
            if (tiempoSimuladoActual == null || inicioHorizonte.isAfter(tiempoSimuladoActual)) {
                tiempoSimuladoActual = inicioHorizonte;
            }

            // ⚡ Los eventos temporales se ejecutan individualmente cuando les toca
            // (programados con ScheduledExecutorService en crearEventosTemporales)

            // ✅ Liberar productos que llegaron al destino final hace más de 2 horas
            // IMPORTANTE: Esta validación debe ejecutarse SIEMPRE, incluso si no hay
            // pedidos para planificar
            // Usar el tiempo simulado actual (que avanza más granularmente) en lugar del
            // inicio del horizonte
            // Esto permite verificar liberaciones exactamente cuando pasen 2 horas, no solo
            // al inicio de cada ciclo
            if (tiempoSimuladoActual != null) {
                liberarProductosEntregados(tiempoSimuladoActual);
            }

            if (pedidosParaPlanificar.isEmpty()) {
                System.out.println("✅ No hay pedidos pendientes en este horizonte");

                // ⚡ Los eventos temporales se ejecutan individualmente cuando les toca
                // (programados con ScheduledExecutorService en crearEventosTemporales)

                ultimoTiempoEjecucion = tiempoEjecucion;

                // ✅ IMPORTANTE: Actualizar el horizonte aunque no haya pedidos
                this.ultimoHorizontePlanificado = finHorizonte;

                // En modo SEMANAL, verificar si se alcanzó la fecha fin
                if (modoSimulacion == ModoSimulacion.SEMANAL && fechaFinSimulacion != null) {
                    if (finHorizonte.isAfter(fechaFinSimulacion) || finHorizonte.isEqual(fechaFinSimulacion)) {
                        System.out.println("🏁 Simulación semanal completada - se alcanzó la fecha fin");
                        detenerPlanificacion();
                    }
                }

                return;
            }

            // ✅ Recargar estado actual de vuelos y aeropuertos para este ciclo
            recargarDatosBase(inicioHorizonte, finHorizonte);

            // 4. Ejecutar GRASP con timeout
            Solucion solucion = ejecutarGRASPConTimeout(pedidosParaPlanificar, tiempoEjecucion);
            this.ultimaSolucion = solucion;
            actualizarEstadisticas(solucion, ciclo, System.currentTimeMillis() - inicioCiclo);

            // 5. Verificar si hay pedidos sin ruta (no completados)
            List<Envio> pedidosSinRuta = new ArrayList<>();
            for (Envio envio : solucion.getEnvios()) {
                if (!envio.estaCompleto()) {
                    pedidosSinRuta.add(envio);
                }
            }

            if (!pedidosSinRuta.isEmpty()) {
                System.out.printf("⚠️ ALERTA: %d pedido(s) no pudieron ser asignados completamente:%n",
                        pedidosSinRuta.size());
                for (Envio envio : pedidosSinRuta) {
                    System.out.printf("  - Pedido ID: %d, Cliente: %s, Cantidad restante: %d%n",
                            envio.getId(), envio.getCliente(), envio.cantidadRestante());
                }

                // Detener planificación si hay pedidos sin ruta (aplica para ambos modos)
                System.out.println("🛑 Deteniendo planificación: no se encontró ruta para uno o más pedidos");
                detenerPlanificacion();

                // ✅ PERSISTIR CAMBIOS EN LA BASE DE DATOS (aunque haya pedidos sin ruta)
                try {
                    persistirCambios(solucion);
                    System.out.println("💾 Cambios persistidos en la base de datos");
                } catch (Exception e) {
                    System.err.printf("❌ Error al persistir cambios: %s%n", e.getMessage());
                    e.printStackTrace();
                }

                // ✅ ENVIAR ACTUALIZACIÓN VÍA WEBSOCKET
                webSocketService.enviarActualizacionCiclo(solucion, ciclo);

                // Mostrar resultados
                mostrarResultadosCiclo(solucion, pedidosParaPlanificar, ciclo);

                return;
            }

            // ⚡ CREAR EVENTOS TEMPORALES: Convertir las rutas planificadas en eventos
            // que se procesarán cuando el tiempo avance
            crearEventosTemporales(solucion, inicioHorizonte);

            // ✅ PERSISTIR CAMBIOS EN LA BASE DE DATOS
            try {
                persistirCambios(solucion);
                System.out.println("💾 Cambios persistidos en la base de datos");
            } catch (Exception e) {
                System.err.printf("❌ Error al persistir cambios: %s%n", e.getMessage());
                e.printStackTrace();
            }

            // ⚡ Los eventos temporales se ejecutan individualmente cuando les toca
            // (programados con ScheduledExecutorService en crearEventosTemporales)

            // ✅ ACTUALIZAR el horizonte para el próximo ciclo
            this.ultimoHorizontePlanificado = finHorizonte;

            // ✅ ENVIAR ACTUALIZACIÓN VÍA WEBSOCKET
            webSocketService.enviarActualizacionCiclo(solucion, ciclo);

            // 6. Mostrar resultados
            mostrarResultadosCiclo(solucion, pedidosParaPlanificar, ciclo);

            System.out.printf("✅ CICLO %d COMPLETADO - %d/%d envíos asignados%n", ciclo,
                    solucion.getEnviosCompletados(), solucion.getEnvios().size());

            ultimoTiempoEjecucion = tiempoEjecucion;
            // } catch(TimeoutException e) {
            // // ✅ ENVIAR ERROR VÍA WEBSOCKET
            // webSocketService.enviarError("Timeout después de " + TA_SEGUNDOS + "
            // segundos", ciclo);
            // System.out.printf("⏰ CICLO %d - TIMEOUT%n", ciclo);
            // actualizarEstadisticasTimeout(ciclo);
        } catch (Exception e) {
            // ✅ ENVIAR ERROR VÍA WEBSOCKET
            webSocketService.enviarError("Error: " + e.getMessage(), ciclo);
            System.err.printf("❌ CICLO %d - ERROR: %s%n", ciclo, e.getMessage());
            actualizarEstadisticasError(ciclo, e.getMessage());
        }
    }

    private List<Envio> obtenerPedidosEnVentana(LocalDateTime inicio, LocalDateTime fin) {
        List<Envio> pedidosNuevos = new ArrayList<>();

        // ⚡ OPTIMIZACIÓN CRÍTICA: Cargar envíos desde BD solo del rango actual
        // Esto evita mantener todos los envíos en memoria
        List<Envio> enviosEnRango;
        if (enviosOriginales != null && !enviosOriginales.isEmpty()) {
            // Si hay envíos en memoria (modo legacy), usarlos
            enviosEnRango = enviosOriginales;
        } else {
            // Cargar solo envíos del rango actual desde BD
            System.out.printf("📦 [obtenerPedidosEnVentana] Cargando envíos desde BD: %s hasta %s%n",
                    inicio.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                    fin.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

            try {
                enviosEnRango = envioService.obtenerEnviosEnRango(inicio, "0", fin, "0");
                System.out.printf("✅ [obtenerPedidosEnVentana] Envíos cargados: %d%n", enviosEnRango.size());

                // ⚡ Configurar hubs para los envíos cargados
                ArrayList<Aeropuerto> hubs = grasp.getHubs();
                if (hubs != null && !hubs.isEmpty()) {
                    ArrayList<Aeropuerto> uniqHubs = new ArrayList<>(new LinkedHashSet<>(hubs));
                    for (Envio e : enviosEnRango) {
                        e.setAeropuertosOrigen(new ArrayList<>(uniqHubs));
                    }
                    System.out.printf("⚙️ [obtenerPedidosEnVentana] Hubs configurados para %d envíos%n", enviosEnRango.size());
                }
            } catch (Exception e) {
                System.err.printf("❌ Error al cargar envíos desde BD: %s%n", e.getMessage());
                return new ArrayList<>();
            }
        }

        // Convertir los límites del horizonte a ZonedDateTime en UTC para comparar
        // correctamente
        ZonedDateTime inicioUTC = inicio.atZone(ZoneOffset.UTC);
        ZonedDateTime finUTC = fin.atZone(ZoneOffset.UTC);

        ZonedDateTime fechaInicioSimulacionUTC = null;
        ZonedDateTime fechaFinSimulacionUTC = null;
        if (fechaInicioSimulacion != null) {
            fechaInicioSimulacionUTC = fechaInicioSimulacion.atZone(ZoneOffset.UTC);
        }
        if (fechaFinSimulacion != null) {
            fechaFinSimulacionUTC = fechaFinSimulacion.atZone(ZoneOffset.UTC);
        }

        for (Envio envio : enviosEnRango) {
            // Convertir la fecha de ingreso del pedido a UTC para comparar correctamente
            ZonedDateTime tiempoPedidoUTC = envio.getZonedFechaIngreso()
                    .withZoneSameInstant(ZoneOffset.UTC);

            // Filtrar según el modo de simulación
            boolean incluirPedido = false;

            if (modoSimulacion == ModoSimulacion.SEMANAL) {
                // En modo semanal, solo incluir pedidos dentro del rango fechaInicioSimulacion
                // - fechaFinSimulacion
                if (fechaInicioSimulacionUTC != null && fechaFinSimulacionUTC != null) {
                    if (!tiempoPedidoUTC.isBefore(fechaInicioSimulacionUTC) &&
                            !tiempoPedidoUTC.isAfter(fechaFinSimulacionUTC)) {
                        // Y además deben estar en el horizonte actual
                        if (!tiempoPedidoUTC.isBefore(inicioUTC) && tiempoPedidoUTC.isBefore(finUTC)) {
                            incluirPedido = true;
                        }
                    }
                }
            } else if (modoSimulacion == ModoSimulacion.COLAPSO) {
                // En modo colapso, solo incluir pedidos desde fechaInicioSimulacion en adelante
                if (fechaInicioSimulacionUTC != null) {
                    if (!tiempoPedidoUTC.isBefore(fechaInicioSimulacionUTC)) {
                        // Y además deben estar en el horizonte actual
                        if (!tiempoPedidoUTC.isBefore(inicioUTC) && tiempoPedidoUTC.isBefore(finUTC)) {
                            incluirPedido = true;
                        }
                    }
                }
            } else {
                // Modo normal - comportamiento original
                if (!tiempoPedidoUTC.isBefore(inicioUTC) && tiempoPedidoUTC.isBefore(finUTC)) {
                    incluirPedido = true;
                }
            }

            if (incluirPedido) {
                // ✅ Crear COPIA del envío para este ciclo específico
                pedidosNuevos.add(crearCopiaEnvio(envio));
            }
        }

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

        copia.setParteAsignadas(new ArrayList<>()); // ← Lista VACIA para este ciclo

        // Si necesitas las partes asignadas previas, cópialas manualmente
        // ⚡ Proteger acceso a parteAsignadas para evitar LazyInitializationException
        try {
            List<ParteAsignada> partesOriginales = original.getParteAsignadas();
            if (partesOriginales != null && !partesOriginales.isEmpty()) {
                for (ParteAsignada parteOriginal : partesOriginales) {
                    ParteAsignada parteCopia = crearCopiaParteAsignada(parteOriginal);
                    parteCopia.setEnvio(copia);
                    copia.getParteAsignadas().add(parteCopia);
                }
            }
        } catch (org.hibernate.LazyInitializationException e) {
            // Si hay error de lazy loading, simplemente no copiar las partes previas
            // (la copia ya tiene una lista vacía, que es lo que queremos para el nuevo ciclo)
        }

        return copia;
    }

    private ParteAsignada crearCopiaParteAsignada(ParteAsignada original) {
        ParteAsignada copia = new ParteAsignada();
        copia.setId(original.getId());
        copia.setCantidad(original.getCantidad());
        copia.setLlegadaFinal(original.getLlegadaFinal());
        copia.setAeropuertoOrigen(original.getAeropuertoOrigen());
        copia.setEntregado(original.getEntregado());

        // Copiar la ruta (vuelos instanciados)
        if (original.getRuta() != null) {
            List<PlanDeVuelo> rutaCopia = new ArrayList<>();
            for (PlanDeVuelo vuelo : original.getRuta()) {
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
        if ((System.currentTimeMillis() - inicioEjecucion) > (TA_SEGUNDOS * 1000 * 0.8)) {
            System.out.println("⏰ GRASP: Cerca del timeout, terminando iteraciones");
            // break;
        }

        // ⚡ OPTIMIZACIÓN: Reutilizar vuelos ya cargados y filtrados en inicialización
        ArrayList<PlanDeVuelo> planesDeVuelo = grasp.getPlanesDeVuelo();
        if (planesDeVuelo == null || planesDeVuelo.isEmpty()) {
            return null;
        }

        ArrayList<Envio> enviosParaProgramar = grasp.getEnvios();

        // Inicializar los caches necesarios para trabajar con estos vuelos
        // Pasar los envíos para filtrar por ventana temporal
        grasp.inicializarCachesParaVuelos(planesDeVuelo, enviosParaProgramar);

        // Ejecutar GRASP para este día
        Solucion solucionDia = grasp.ejecutarGrasp(enviosParaProgramar, planesDeVuelo);

        if (mejorSolucion == null || grasp.esMejor(solucionDia, mejorSolucion)) {
            mejorSolucion = solucionDia;
        }

        /*
         * for(LocalDateTime dia : grasp.getDias()) {
         * // Verificar timeout periódicamente
         * if((System.currentTimeMillis() - inicioEjecucion) > (TA_SEGUNDOS * 1000 *
         * 0.8)) {
         * System.out.println("⏰ GRASP: Cerca del timeout, terminando iteraciones");
         * break;
         * }
         *
         * List<Envio> enviosDelDia = grasp.getEnviosPorDia().get(dia);
         * if(enviosDelDia == null || enviosDelDia.isEmpty()) {
         * continue;
         * }
         *
         * // Los PlanDeVuelo ya representan vuelos diarios, así que los usamos
         * directamente
         * ArrayList<PlanDeVuelo> planesDeVuelo = grasp.getPlanesDeVuelo();
         * if(planesDeVuelo == null || planesDeVuelo.isEmpty()) {
         * continue;
         * }
         *
         * // Inicializar los caches necesarios para trabajar con estos vuelos
         * // Pasar los envíos para filtrar por ventana temporal
         * grasp.inicializarCachesParaVuelos(planesDeVuelo, enviosDelDia);
         *
         * // Ejecutar GRASP para este día
         * Solucion solucionDia = grasp.ejecutarGrasp(enviosDelDia, planesDeVuelo);
         *
         * if(mejorSolucion == null || grasp.esMejor(solucionDia, mejorSolucion)) {
         * mejorSolucion = solucionDia;
         * }
         * }
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
        statsCiclo.put("tasaExito",
                solucion.getEnvios().size() > 0
                        ? (solucion.getEnviosCompletados() * 100.0 / solucion.getEnvios().size())
                        : 0);

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

        // ⚡ Estadísticas por estado de envío (desde BD)
        this.estadisticas.put("totalEnviosPlanificados", calcularTotalEnviosPorEstado(Envio.EstadoEnvio.PLANIFICADO));
        this.estadisticas.put("totalEnviosEnRuta", calcularTotalEnviosPorEstado(Envio.EstadoEnvio.EN_RUTA));
        this.estadisticas.put("totalEnviosFinalizados", calcularTotalEnviosPorEstado(Envio.EstadoEnvio.FINALIZADO));
        this.estadisticas.put("totalEnviosEntregados", calcularTotalEnviosPorEstado(Envio.EstadoEnvio.ENTREGADO));

        // Mantener compatibilidad con campos antiguos
        this.estadisticas.put("totalEnviosProcesados", calcularTotalEnviosProcesados());
        this.estadisticas.put("totalPedidosPlanificados", calcularTotalPedidosPlanificados());
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

    /**
     * Calcula el total de pedidos planificados basándose en los envíos que tienen
     * al menos una parte asignada (es decir, que tienen una ruta de vuelos
     * asignada)
     */
    private int calcularTotalPedidosPlanificados() {
        try {
            // ⚡ Usar servicio optimizado que ya trae solo envíos con partes asignadas
            // y carga la colección lazy de forma segura desde la capa de persistencia.
            List<Envio> enviosConPartes = envioService.obtenerEnviosConPartesAsignadas();

            // Cada envío devuelto tiene al menos una parte asignada, así que el total
            // de pedidos planificados es simplemente el tamaño de la lista.
            return enviosConPartes != null ? enviosConPartes.size() : 0;
        } catch (Exception e) {
            System.err.printf("❌ Error al calcular pedidos planificados: %s%n", e.getMessage());
            return 0;
        }
    }

    /**
     * ⚡ OPTIMIZADO: Calcula el total de envíos por estado desde la base de datos.
     * Usa COUNT directamente en la BD en lugar de cargar todos los envíos en memoria.
     * Esto previene OutOfMemoryError cuando hay muchos envíos.
     */
    private int calcularTotalEnviosPorEstado(Envio.EstadoEnvio estado) {
        try {
            return (int) envioService.contarEnviosPorEstado(estado);
        } catch (Exception e) {
            System.err.printf("❌ Error al calcular envíos por estado %s: %s%n", estado, e.getMessage());
            e.printStackTrace();
            return 0;
        }
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

        if (!pedidosProcesados.isEmpty()) {
            double tasaExito = (solucion.getEnviosCompletados() * 100.0) / pedidosProcesados.size();
            System.out.printf("   • Tasa de éxito: %.1f%%%n", tasaExito);
        }

        // ⚡ Mostrar estadísticas por estado
        try {
            int planificados = calcularTotalEnviosPorEstado(Envio.EstadoEnvio.PLANIFICADO);
            int enRuta = calcularTotalEnviosPorEstado(Envio.EstadoEnvio.EN_RUTA);
            int finalizados = calcularTotalEnviosPorEstado(Envio.EstadoEnvio.FINALIZADO);
            int entregados = calcularTotalEnviosPorEstado(Envio.EstadoEnvio.ENTREGADO);

            System.out.println("   📦 ESTADÍSTICAS POR ESTADO:");
            System.out.printf("      • Planificados: %d%n", planificados);
            System.out.printf("      • En Ruta: %d%n", enRuta);
            System.out.printf("      • Finalizados: %d%n", finalizados);
            System.out.printf("      • Entregados: %d%n", entregados);
        } catch (Exception e) {
            System.err.printf("   ⚠️ Error al calcular estadísticas por estado: %s%n", e.getMessage());
        }

        System.out.printf("   • Tiempo medio de entrega: %s%n",
                formatDuracion(solucion.getLlegadaMediaPonderada()));

        System.out.printf("%n📋 DETALLE DE RUTAS ASIGNADAS - CICLO %d%n", ciclo);
        System.out.println("=".repeat(80));

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM dd HH:mm", Locale.forLanguageTag("es-ES"));

        for (Envio envio : solucion.getEnvios()) {
            // ⚡ Verificar partes asignadas de forma segura para evitar LazyInitializationException
            List<ParteAsignada> partesAsignadas = null;
            try {
                partesAsignadas = envio.getParteAsignadas();
            } catch (Exception e) {
                // Si hay error de lazy loading, intentar cargar desde BD si tiene ID
                if (envio.getId() != null) {
                    try {
                        Optional<Envio> envioOpt = envioService.obtenerEnvioPorIdConPartesInicializadas(envio.getId());
                        if (envioOpt.isPresent()) {
                            partesAsignadas = envioOpt.get().getParteAsignadas();
                        }
                    } catch (Exception ex) {
                        // Si falla, simplemente no mostrar las partes
                        partesAsignadas = new ArrayList<>();
                    }
                } else {
                    partesAsignadas = new ArrayList<>();
                }
            }

            boolean tienePartes = partesAsignadas != null && !partesAsignadas.isEmpty();
            if (envio.estaCompleto() || tienePartes) {
                System.out.printf("📦 PEDIDO %s → %s (%d unidades)%n",
                        envio.getId(),
                        envio.getAeropuertoDestino() != null ? envio.getAeropuertoDestino().getCodigo() : "N/A",
                        envio.getNumProductos());

                System.out.printf("   📍 Orígenes posibles: %s%n",
                        envio.getAeropuertosOrigen() != null ? envio.getAeropuertosOrigen().stream()
                                .map(Aeropuerto::getCodigo)
                                .collect(Collectors.joining(", ")) : "N/A");

                System.out.printf("   ⏰ Aparición: %s%n", formatFechaConOffset(envio.getZonedFechaIngreso(),
                        envio.getFechaIngreso(), envio.getHusoHorarioDestino(), formatter));

                int parteNum = 1;
                if (partesAsignadas != null) {
                    for (ParteAsignada parte : partesAsignadas) {
                        System.out.printf("   🚚 Parte %d (%d unidades desde %s):%n", parteNum, parte.getCantidad(),
                                parte.getAeropuertoOrigen().getCodigo());

                        for (int i = 0; i < parte.getRuta().size(); i++) {
                            PlanDeVuelo vuelo = parte.getRuta().get(i);
                            System.out.printf("      ✈️  %s → %s | %s - %s | Cap: %d/%d%n",
                                    obtenerAeropuertoPorId(vuelo.getCiudadOrigen()).getCodigo(),
                                    obtenerAeropuertoPorId(vuelo.getCiudadDestino()).getCodigo(),
                                    formatFechaConOffset(vuelo.getZonedHoraOrigen(), vuelo.getHoraOrigen(),
                                            vuelo.getHusoHorarioOrigen(), formatter),
                                    formatFechaConOffset(vuelo.getZonedHoraDestino(), vuelo.getHoraDestino(),
                                            vuelo.getHusoHorarioDestino(), formatter),
                                    vuelo.getCapacidadOcupada(),
                                    vuelo.getCapacidadMaxima());
                        }

                        System.out.printf("      🏁 Llegada final: %s%n",
                                formatFechaConOffset(parte.getLlegadaFinal(), null, null, formatter));
                        parteNum++;
                    }
                }
                System.out.println();
            }
        }
        System.out.println("=".repeat(80));

    }

    private Aeropuerto obtenerAeropuertoPorId(Integer id) {
        for (Aeropuerto aeropuerto : this.grasp.getAeropuertos())
            if (aeropuerto.getId().equals(id))
                return aeropuerto;

        return null;
    }

    // Metodo para ver el estado actual del horizonte
    public Map<String, Object> getEstadoHorizonte() {
        Map<String, Object> estado = new HashMap<>();
        estado.put("tiempoInicioSimulacion", tiempoInicioSimulacion);
        estado.put("ultimoHorizontePlanificado", ultimoHorizontePlanificado);
        int kActual = obtenerK();
        estado.put("proximoHorizonte", ultimoHorizontePlanificado.plusMinutes(SA_MINUTOS * kActual));
        // estado.put("pedidosYaPlanificados", pedidosYaPlanificados.size());
        return estado;
    }

    private String formatDuracion(Duration duration) {
        if (duration == null)
            return "N/A";
        long hours = duration.toHours();
        long minutes = duration.minusHours(hours).toMinutes();
        return String.format("%d h %d min", hours, minutes);
    }

    private String formatFechaConOffset(ZonedDateTime zoned, LocalDateTime local, String husoHorario,
            DateTimeFormatter baseFormatter) {
        if (zoned != null) {
            ZoneOffset offset = zoned.getOffset();
            String offsetStr = offset.getId().equals("Z") ? "+00:00" : offset.getId();
            return String.format("%s (UTC%s)", zoned.format(baseFormatter), offsetStr);
        }

        if (local != null && husoHorario != null) {
            int offsetHoras;
            try {
                offsetHoras = Integer.parseInt(husoHorario);
            } catch (NumberFormatException e) {
                offsetHoras = 0;
            }
            return String.format("%s (UTC%+03d:00)", local.format(baseFormatter), offsetHoras);
        }

        if (local != null) {
            return String.format("%s (UTC%+03d:00)", local.format(baseFormatter), 0);
        }

        return "N/A";
    }

    public List<PlanDeVuelo> getVuelosUltimoCiclo() {
        return vuelosUltimoCiclo != null ? vuelosUltimoCiclo : Collections.emptyList();
    }

    public List<Map<String, Object>> getAsignacionesUltimoCiclo() {
        if (ultimaSolucion == null || ultimaSolucion.getEnvios() == null) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> asignaciones = new ArrayList<>();

        for (Envio envio : ultimaSolucion.getEnvios()) {
            if (envio.getParteAsignadas() == null)
                continue;

            for (ParteAsignada parte : envio.getParteAsignadas()) {
                if (parte.getRuta() == null)
                    continue;

                for (PlanDeVuelo vuelo : parte.getRuta()) {
                    if (vuelo.getId() == null)
                        continue;

                    Map<String, Object> registro = new HashMap<>();
                    registro.put("vueloId", vuelo.getId());
                    registro.put("envioId", envio.getId());
                    registro.put("cantidad", parte.getCantidad());
                    registro.put("envioIdPorAeropuerto", envio.getIdEnvioPorAeropuerto());

                    if (envio.getCliente() != null) {
                        registro.put("cliente", envio.getCliente());
                    }

                    if (parte.getAeropuertoOrigen() != null) {
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
     * Libera la capacidad ocupada en aeropuertos destino final para productos
     * que llegaron hace más de 2 horas (simulando que el cliente recogió el
     * producto).
     * Solo aplica para productos que llegaron al aeropuerto destino final del
     * envío.
     */
    private void liberarProductosEntregados(LocalDateTime tiempoSimulado) {
        try {
            System.out.printf("🔍 [LiberarProductos] Iniciando verificación a las %s (tiempo simulado)%n",
                    tiempoSimulado.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

            // ⚡ OPTIMIZACIÓN: Obtener envíos CON partes asignadas desde BD para evitar
            // LazyInitializationException
            // Calculamos el rango de fechas basado en el tiempo simulado actual
            LocalDateTime fechaInicio = this.tiempoInicioSimulacion != null ? this.tiempoInicioSimulacion
                    : tiempoSimulado.minusDays(1);
            LocalDateTime fechaFin = tiempoSimulado.plusDays(1);

            // Usar el método con JOIN FETCH para cargar las partes asignadas
            List<Envio> enviosConPartes;
            try {
                enviosConPartes = new ArrayList<>(envioService.obtenerEnviosEnRangoConPartes(
                        fechaInicio, "0", fechaFin, "0"));
            } catch (Exception e) {
                System.err.println("⚠️ Error al obtener envíos con partes, usando lista en memoria: " + e.getMessage());
                // Fallback: usar envíos en memoria (pueden causar lazy exception)
                enviosConPartes = this.enviosOriginales != null ? new ArrayList<>(this.enviosOriginales)
                        : new ArrayList<>();
            }

            // Filtrar solo envíos con partes asignadas no vacías
            List<Envio> envios = enviosConPartes.stream()
                    .filter(e -> {
                        try {
                            return e.getParteAsignadas() != null && !e.getParteAsignadas().isEmpty()
                                    && e.getAeropuertoDestino() != null;
                        } catch (Exception ex) {
                            return false; // Si hay error de lazy loading, ignorar este envío
                        }
                    })
                    .collect(java.util.stream.Collectors.toList());

            System.out.printf("🔍 [LiberarProductos] Envíos con partes asignadas: %d (de %d consultados)%n",
                    envios.size(),
                    enviosConPartes.size());

            Map<Integer, Aeropuerto> aeropuertosActualizados = new HashMap<>();
            List<ParteAsignada> partesParaActualizar = new ArrayList<>();
            int productosLiberados = 0;
            int partesEntregadas = 0;
            int enviosConPartesCount = 0;
            int partesSinLlegadaFinal = 0;
            int partesNoLlegaronDestino = 0;
            int partesMenosDe2Horas = 0;

            for (Envio envio : envios) {
                // Ya filtrados: tienen partes asignadas y aeropuerto destino
                enviosConPartesCount++;
                Integer aeropuertoDestinoId = envio.getAeropuertoDestino().getId();

                for (ParteAsignada parte : envio.getParteAsignadas()) {
                    // Evitar procesar partes que ya fueron liberadas del aeropuerto
                    // (ya marcadas como entregadas al cliente en un ciclo anterior)
                    if (parte.getEntregado() != null && parte.getEntregado()) {
                        continue;
                    }

                    // Verificar que la parte tenga llegada final
                    if (parte.getLlegadaFinal() == null) {
                        partesSinLlegadaFinal++;
                        continue;
                    }

                    // Cargar la ruta desde BD si no está cargada
                    if (parte.getRuta() == null || parte.getRuta().isEmpty()) {
                        parte.cargarRutaDesdeBD();
                    }

                    // Verificar que la ruta termine en el aeropuerto destino final del envío
                    boolean llegoADestinoFinal = false;
                    if (parte.getRuta() != null && !parte.getRuta().isEmpty()) {
                        PlanDeVuelo ultimoVuelo = parte.getRuta().get(parte.getRuta().size() - 1);
                        if (ultimoVuelo.getCiudadDestino() != null &&
                                ultimoVuelo.getCiudadDestino().equals(aeropuertoDestinoId)) {
                            llegoADestinoFinal = true;
                        }
                    }

                    // Solo procesar si llegó al destino final
                    if (!llegoADestinoFinal) {
                        partesNoLlegaronDestino++;
                        continue;
                    }

                    // Convertir tiempoSimulado a ZonedDateTime para comparar con llegadaFinal
                    // Usar el mismo timezone que la llegada final
                    ZonedDateTime tiempoSimuladoZoned;
                    try {
                        // Obtener la zona horaria de la llegada final
                        java.time.ZoneId zonaLlegada = parte.getLlegadaFinal().getZone();
                        // Convertir tiempoSimulado a la misma zona horaria
                        tiempoSimuladoZoned = tiempoSimulado.atZone(zonaLlegada);
                    } catch (Exception e) {
                        // Si hay problema con el timezone, usar UTC y luego convertir
                        tiempoSimuladoZoned = tiempoSimulado.atZone(java.time.ZoneOffset.UTC)
                                .withZoneSameInstant(parte.getLlegadaFinal().getZone());
                    }

                    // Verificar si han pasado más de 2 horas desde la llegada final
                    // Usar toInstant() para comparar correctamente independientemente de la zona
                    // horaria
                    // tiempoSimulado debe ser POSTERIOR a llegadaFinal para que las horas sean
                    // positivas
                    long horasTranscurridas = java.time.Duration.between(
                            parte.getLlegadaFinal().toInstant(), tiempoSimuladoZoned.toInstant()).toHours();

//                    System.out.printf(
//                            "🔍 [LiberarProductos] Parte ID %d - Envío ID %d - Llegada: %s - Horas transcurridas: %d%n",
//                            parte.getId(), envio.getId(),
//                            parte.getLlegadaFinal().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
//                            horasTranscurridas);

                    if (horasTranscurridas >= 2) {
                        // Liberar capacidad del aeropuerto destino
                        // Obtener el aeropuerto desde el mapa si ya fue actualizado, o desde la BD
                        Aeropuerto aeropuertoParaActualizar;
                        if (aeropuertosActualizados.containsKey(aeropuertoDestinoId)) {
                            // Usar la instancia del mapa si ya existe, para mantener los cambios acumulados
                            aeropuertoParaActualizar = aeropuertosActualizados.get(aeropuertoDestinoId);
                        } else {
                            // Obtener desde la BD
                            Optional<Aeropuerto> aeropuertoOpt = aeropuertoService
                                    .obtenerAeropuertoPorId(aeropuertoDestinoId);
                            if (!aeropuertoOpt.isPresent()) {
                                System.err.printf("⚠️  Aeropuerto ID %d no encontrado para liberar productos%n",
                                        aeropuertoDestinoId);
                                continue;
                            }
                            aeropuertoParaActualizar = aeropuertoOpt.get();
                            aeropuertosActualizados.put(aeropuertoDestinoId, aeropuertoParaActualizar);
                        }

                        // Guardar capacidad antes de desasignar para logging
                        Integer capacidadAntes = aeropuertoParaActualizar.getCapacidadOcupada();

                        // Desasignar capacidad
                        aeropuertoParaActualizar.desasignarCapacidad(parte.getCantidad());

                        System.out.printf(
                                "✅ [LiberarProductos] Desasignando %d productos del aeropuerto %s (ID: %d) - Capacidad: %d -> %d%n",
                                parte.getCantidad(), aeropuertoParaActualizar.getCodigo(),
                                aeropuertoDestinoId, capacidadAntes, aeropuertoParaActualizar.getCapacidadOcupada());

                        productosLiberados += parte.getCantidad();
                        parte.setEntregado(true);
                        partesParaActualizar.add(parte);
                        partesEntregadas++;

                        System.out.printf("📦 Productos entregados: Parte ID %d del Envío ID %d - " +
                                "%d productos liberados del aeropuerto %s después de %d horas%n",
                                parte.getId(), envio.getId(), parte.getCantidad(),
                                aeropuertoParaActualizar.getCodigo(), horasTranscurridas);
                    } else {
                        partesMenosDe2Horas++;
                    }
                }
            }

            System.out.printf("🔍 [LiberarProductos] Resumen: %d envíos con partes, %d partes sin llegadaFinal, " +
                    "%d partes no llegaron a destino, %d partes con menos de 2 horas%n",
                    enviosConPartesCount, partesSinLlegadaFinal, partesNoLlegaronDestino, partesMenosDe2Horas);

            // Persistir los cambios
            if (!partesParaActualizar.isEmpty()) {
                // Agrupar partes por envío para actualizar eficientemente
                Map<Integer, Envio> enviosParaActualizar = new HashMap<>();
                Map<Integer, ParteAsignada> partesPorId = new HashMap<>();

                for (ParteAsignada parte : partesParaActualizar) {
                    partesPorId.put(parte.getId(), parte);
                    Integer envioId = parte.getEnvio() != null ? parte.getEnvio().getId() : null;
                    if (envioId == null)
                        continue;

                    if (!enviosParaActualizar.containsKey(envioId)) {
                        // ⚡ Usar método que carga partes asignadas dentro de transacción
                        // para evitar LazyInitializationException
                        Optional<Envio> envioOpt = envioService.obtenerEnvioPorIdConPartesInicializadas(envioId);
                        if (envioOpt.isPresent()) {
                            Envio envio = envioOpt.get();

                            // Actualizar las partes del envío con el estado entregado
                            if (envio.getParteAsignadas() != null) {
                                for (ParteAsignada parteEnvio : envio.getParteAsignadas()) {
                                    if (partesPorId.containsKey(parteEnvio.getId())) {
                                        parteEnvio.setEntregado(true);
                                    }
                                }
                            }

                            // ⚡ CAMBIAR ESTADO: Verificar si todas las partes están entregadas (después de marcar las nuevas)
                            if (envio.getParteAsignadas() != null && !envio.getParteAsignadas().isEmpty()) {
                                boolean todasEntregadas = envio.getParteAsignadas().stream()
                                        .allMatch(p -> p.getEntregado() != null && p.getEntregado());

                                if (todasEntregadas && envio.getEstado() != Envio.EstadoEnvio.ENTREGADO) {
                                    envio.setEstado(Envio.EstadoEnvio.ENTREGADO);
                                    // 💾 PERSISTIR inmediatamente el cambio de estado
                                    try {
                                        envioService.insertarEnvio(envio);
//                                        System.out.printf("  ✅ [Estado] Envío %d cambió a ENTREGADO (todas las partes entregadas) [💾 Persistido]%n", envio.getId());
                                    } catch (Exception e) {
                                        System.err.printf("❌ Error al persistir cambio de estado ENTREGADO: %s%n", e.getMessage());
                                    }
                                }
                            }

                            enviosParaActualizar.put(envioId, envio);
                        }
                    }
                }

                // Actualizar envíos (las partes se actualizarán por cascade)
                for (Envio envio : enviosParaActualizar.values()) {
                    envioService.insertarEnvio(envio);
                }

                // Actualizar aeropuertos con capacidad liberada
                if (!aeropuertosActualizados.isEmpty()) {
                    aeropuertoService.insertarListaAeropuertos(
                            new ArrayList<>(aeropuertosActualizados.values()));
                }

                System.out.printf("✅ Liberación de productos: %d partes entregadas, " +
                        "%d productos liberados de aeropuertos%n", partesEntregadas, productosLiberados);
            } else {
                System.out.printf("ℹ️  [LiberarProductos] No se encontraron partes para liberar en este ciclo%n");
            }

        } catch (Exception e) {
            System.err.printf("❌ Error al liberar productos entregados: %s%n", e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Recarga desde la base de datos el estado actual de planes de vuelo y
     * aeropuertos.
     * Esto garantiza que cada ciclo utilice los mismos registros persistidos y
     * respete la capacidad disponible.
     */
    private void recargarDatosBase(LocalDateTime inicioHorizonte, LocalDateTime finHorizonte) {
        // ⚡ OPTIMIZACIÓN CRÍTICA: Cargar solo vuelos relevantes para este ciclo
        // Rango: desde inicioHorizonte hasta inicioHorizonte + 3 días (plazo máximo de entrega)
        // Esto evita cargar 2+ millones de vuelos en memoria
        LocalDateTime finConsultaVuelos = inicioHorizonte.plusDays(3);

        System.out.printf("📊 [recargarDatosBase] Cargando vuelos desde %s hasta %s (3 días desde inicio)%n",
                inicioHorizonte.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                finConsultaVuelos.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

        ArrayList<PlanDeVuelo> planesActualizados = planDeVueloService.obtenerVuelosEnRango(
                inicioHorizonte, "0", finConsultaVuelos, "0");

        System.out.printf("✅ [recargarDatosBase] Vuelos cargados: %d (en lugar de 2+ millones)%n", planesActualizados.size());

        ArrayList<Aeropuerto> aeropuertosActualizados = aeropuertoService.obtenerTodosAeropuertos();

        // Filtrar vuelos que están dentro del horizonte actual para el reporte
        ArrayList<PlanDeVuelo> planesFiltrados = planesActualizados.stream()
                .filter(plan -> {
                    LocalDateTime salida = plan.getHoraOrigen();
                    LocalDateTime llegada = plan.getHoraDestino();
                    if (salida == null || llegada == null)
                        return false;

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
        for (Envio envioCopia : solucion.getEnvios()) {
            if (envioCopia.getId() == null) {
                continue; // Saltar envíos sin ID (nuevos)
            }

            // Cargar el envío real de la BD y forzar la carga de partes asignadas
            // dentro de una transacción para evitar LazyInitializationException
            Optional<Envio> envioOpt = envioService.obtenerEnvioPorIdConPartesInicializadas(envioCopia.getId());
            if (envioOpt.isEmpty()) {
                System.err.printf("⚠️ No se encontró el envío %d en la BD%n", envioCopia.getId());
                continue;
            }

            Envio envioReal = envioOpt.get();

            // Procesar cada parte asignada nueva
            if (envioCopia.getParteAsignadas() != null) {
                for (ParteAsignada parteCopia : envioCopia.getParteAsignadas()) {
                    // Verificar si esta parte ya existe en el envío real
                    boolean parteExiste = false;
                    if (envioReal.getParteAsignadas() != null) {
                        for (ParteAsignada parteExistente : envioReal.getParteAsignadas()) {
                            if (parteExistente.getId() != null && parteExistente.getId().equals(parteCopia.getId())) {
                                parteExiste = true;
                                break;
                            }
                        }
                    }

                    // Si la parte es nueva (sin ID) o no existe, crearla
                    if (parteCopia.getId() == null || !parteExiste) {
                        // Crear nueva parte asignada
                        ParteAsignada nuevaParte = new ParteAsignada();
                        nuevaParte.setEnvio(envioReal);
                        nuevaParte.setCantidad(parteCopia.getCantidad());
                        nuevaParte.setLlegadaFinal(parteCopia.getLlegadaFinal());
                        nuevaParte.setAeropuertoOrigen(parteCopia.getAeropuertoOrigen());

                        // Copiar la ruta transient - cargar los planes de vuelo reales de BD
                        if (parteCopia.getRuta() != null && !parteCopia.getRuta().isEmpty()) {
                            List<PlanDeVuelo> rutaReal = new ArrayList<>();
                            for (int i = 0; i < parteCopia.getRuta().size(); i++) {
                                PlanDeVuelo vueloCopia = parteCopia.getRuta().get(i);
                                if (vueloCopia.getId() != null) {
                                    Optional<PlanDeVuelo> vueloOpt = planDeVueloService
                                            .obtenerPlanDeVueloPorId(vueloCopia.getId());
                                    if (vueloOpt.isPresent()) {
                                        rutaReal.add(vueloOpt.get());
                                        planesDeVueloModificados.add(vueloCopia.getId());

                                        // Agregar aeropuerto destino (siempre se asigna capacidad cuando llega)
                                        if (vueloCopia.getCiudadDestino() != null) {
                                            aeropuertosModificados.add(vueloCopia.getCiudadDestino());
                                        }

                                        // Agregar aeropuerto origen (si NO es el primer vuelo, se desasigna capacidad
                                        // cuando despega)
                                        if (i > 0 && vueloCopia.getCiudadOrigen() != null) {
                                            aeropuertosModificados.add(vueloCopia.getCiudadOrigen());
                                        }
                                    }
                                }
                            }
                            nuevaParte.setRuta(rutaReal);
                            // Sincronizar la ruta con BD antes de persistir
                            nuevaParte.sincronizarRutaConBD();
                        }

                        // Agregar a la lista de partes del envío
                        if (envioReal.getParteAsignadas() == null) {
                            envioReal.setParteAsignadas(new ArrayList<>());
                        }
                        envioReal.getParteAsignadas().add(nuevaParte);
                    }
                }
            }

            // ⚡ CAMBIAR ESTADO: Si el envío tiene partes asignadas (ruta), establecer PLANIFICADO
            if (envioReal.getParteAsignadas() != null && !envioReal.getParteAsignadas().isEmpty()) {
                if (envioReal.getEstado() == null || envioReal.getEstado() != Envio.EstadoEnvio.PLANIFICADO) {
                    envioReal.setEstado(Envio.EstadoEnvio.PLANIFICADO);
                    // 💾 PERSISTIR inmediatamente el cambio de estado
                    try {
                        envioService.insertarEnvio(envioReal);
                        //System.out.printf("  ✅ [Estado] Envío %d cambió a PLANIFICADO (ruta asignada) [💾 Persistido]%n", envioReal.getId());
                    } catch (Exception e) {
                        System.err.printf("❌ Error al persistir cambio de estado PLANIFICADO: %s%n", e.getMessage());
                    }
                }
            }

            enviosParaActualizar.add(envioReal);
        }

        // 2. Actualizar planes de vuelo con su capacidad ocupada
        List<PlanDeVuelo> planesParaActualizar = new ArrayList<>();
        for (Integer planId : planesDeVueloModificados) {
            Optional<PlanDeVuelo> planOpt = planDeVueloService.obtenerPlanDeVueloPorId(planId);
            if (planOpt.isPresent()) {
                PlanDeVuelo planReal = planOpt.get();
                // Buscar el plan en el grasp para obtener la capacidad actualizada
                Integer capacidadAsignada = calcularCapacidadAsignada(planId, solucion.getEnvios());
                if (capacidadAsignada != null) {
                    planReal.setCapacidadOcupada(capacidadAsignada);
                    planesParaActualizar.add(planReal);
                }
            }
        }

        // 3. Actualizar aeropuertos con su capacidad ocupada
        List<Aeropuerto> aeropuertosParaActualizar = new ArrayList<>();
        for (Integer aeropuertoId : aeropuertosModificados) {
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
     * ⚡ Crea y programa eventos temporales individualmente.
     * Cada evento se ejecutará exactamente cuando le toca usando ScheduledExecutorService.
     *
     * NOTA: Los eventos se programan basándose en el tiempo simulado.
     * El delay se calcula desde el tiempo simulado actual hasta el tiempo del evento.
     * Como estamos en una simulación acelerada, usamos un factor de conversión:
     * - 1 minuto simulado = 1 segundo real (configurable)
     */
    private void crearEventosTemporales(Solucion solucion, LocalDateTime tiempoReferencia) {
        if (solucion == null || solucion.getEnvios() == null) {
            return;
        }

        if (schedulerEventos == null) {
            System.err.println("⚠️ Scheduler de eventos no inicializado, no se pueden programar eventos");
            return;
        }

        // Factor de conversión: 1 segundo real = 2 minutos simulados
        // Esto significa que la simulación corre 120x más rápido que el tiempo real
        final double FACTOR_CONVERSION = 2.0; // minutos simulados por segundo real

        LocalDateTime tiempoSimuladoActual = this.tiempoSimuladoActual != null
                ? this.tiempoSimuladoActual
                : tiempoReferencia;

        int contadorEventos = 0;
        for (Envio envio : solucion.getEnvios()) {
            if (envio.getParteAsignadas() == null) {
                continue;
            }

            for (ParteAsignada parte : envio.getParteAsignadas()) {
                if (parte.getRuta() == null || parte.getRuta().isEmpty()) {
                    continue;
                }

                Integer cantidad = parte.getCantidad();
                if (cantidad == null || cantidad <= 0) {
                    continue;
                }

                // Crear y programar eventos para cada vuelo en la ruta
                int totalVuelos = parte.getRuta().size();
                for (int i = 0; i < totalVuelos; i++) {
                    PlanDeVuelo vuelo = parte.getRuta().get(i);
                    boolean esPrimerVuelo = (i == 0);
                    boolean esUltimoVuelo = (i == totalVuelos - 1);

                    // Evento: Llegada del vuelo al destino
                    ZonedDateTime llegada = vuelo.getZonedHoraDestino();
                    if (llegada != null) {
                        LocalDateTime llegadaLocal = llegada.toLocalDateTime();

                        // Calcular delay en segundos reales
                        long minutosSimulados = Duration.between(tiempoSimuladoActual, llegadaLocal).toMinutes();
                        if (minutosSimulados >= 0) { // Solo programar eventos futuros
                            long delaySegundos = (long) (minutosSimulados / FACTOR_CONVERSION);

                            EventoTemporal eventoLlegada = new EventoTemporal(
                                    llegada,
                                    EventoTemporal.TipoEvento.LLEGADA_VUELO,
                                    vuelo,
                                    parte,
                                    cantidad,
                                    vuelo.getCiudadDestino(),
                                    esPrimerVuelo,
                                    esUltimoVuelo,
                                    envio
                            );

                            // Programar el evento para ejecutarse después del delay calculado
                            ScheduledFuture<?> futuro = schedulerEventos.schedule(
                                    () -> procesarEvento(eventoLlegada),
                                    delaySegundos,
                                    TimeUnit.SECONDS
                            );

                            eventosProgramados.add(futuro);
                            contadorEventos++;

//                            System.out.printf("  📅 Evento programado: Vuelo %d llegará a %s en %d min sim (%d seg real) - %s%n", vuelo.getId(), llegadaLocal.format(DateTimeFormatter.ofPattern("HH:mm")), minutosSimulados, delaySegundos, llegadaLocal.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
                        }
                    }

                    // Evento: Salida del vuelo desde el origen
                    // Para el primer vuelo: cambia estado a EN_RUTA
                    // Para vuelos intermedios: solo desasigna capacidad
                    ZonedDateTime salida = vuelo.getZonedHoraOrigen();
                    if (salida != null) {
                        LocalDateTime salidaLocal = salida.toLocalDateTime();

                        // Calcular delay en segundos reales
                        long minutosSimulados = Duration.between(tiempoSimuladoActual, salidaLocal).toMinutes();
                        if (minutosSimulados >= 0) { // Solo programar eventos futuros
                            long delaySegundos = (long) (minutosSimulados / FACTOR_CONVERSION);

                            EventoTemporal eventoSalida = new EventoTemporal(
                                    salida,
                                    EventoTemporal.TipoEvento.SALIDA_VUELO,
                                    vuelo,
                                    parte,
                                    cantidad,
                                    vuelo.getCiudadOrigen(),
                                    esPrimerVuelo,
                                    esUltimoVuelo,
                                    envio
                            );

                            // Programar el evento para ejecutarse después del delay calculado
                            ScheduledFuture<?> futuro = schedulerEventos.schedule(
                                    () -> procesarEvento(eventoSalida),
                                    delaySegundos,
                                    TimeUnit.SECONDS
                            );

                            eventosProgramados.add(futuro);
                            contadorEventos++;

//                            System.out.printf("  📅 Evento programado: Vuelo %d saldrá de %s en %d min sim (%d seg real) - %s%n", vuelo.getId(), salidaLocal.format(DateTimeFormatter.ofPattern("HH:mm")), minutosSimulados, delaySegundos, salidaLocal.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
                        }
                    }
                }
            }
        }

        System.out.printf("📅 [crearEventosTemporales] Programados %d eventos temporales desde %s%n",
                contadorEventos, tiempoReferencia.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
    }

    /**
     * ⚡ MÉTODO OBSOLETO: Ya no se usa procesamiento en lotes.
     * Los eventos ahora se ejecutan individualmente cuando les toca usando ScheduledExecutorService.
     * Este método se mantiene por compatibilidad pero no se llama.
     *
     * @deprecated Los eventos se programan individualmente en crearEventosTemporales
     */
    @Deprecated
    private void procesarEventosTemporales(LocalDateTime tiempoActual) {
        // Ya no se procesan eventos en lotes, se ejecutan individualmente cuando les toca
        // Este método se mantiene por compatibilidad pero no debería llamarse
    }

    /**
     * Procesa un evento temporal individual (llegada o salida de vuelo)
     * ⚡ PERSISTE los cambios en la base de datos inmediatamente
     */
    private void procesarEvento(EventoTemporal evento) {
        try {
            Optional<Aeropuerto> aeropuertoOpt = aeropuertoService.obtenerAeropuertoPorId(evento.getAeropuertoId());
            if (!aeropuertoOpt.isPresent()) {
                System.err.printf("⚠️ Aeropuerto ID %d no encontrado para evento%n", evento.getAeropuertoId());
                return;
            }

            Aeropuerto aeropuerto = aeropuertoOpt.get();

            if (evento.getTipo() == EventoTemporal.TipoEvento.LLEGADA_VUELO) {
                // Vuelo llega: asignar capacidad en el aeropuerto destino
                aeropuerto.asignarCapacidad(evento.getCantidad());

                // ⚡ También actualizar capacidad del vuelo cuando llega
                PlanDeVuelo vuelo = evento.getVuelo();
                PlanDeVuelo vueloReal = null;
                if (vuelo != null && vuelo.getId() != null) {
                    Optional<PlanDeVuelo> vueloOpt = planDeVueloService.obtenerPlanDeVueloPorId(vuelo.getId());
                    if (vueloOpt.isPresent()) {
                        vueloReal = vueloOpt.get();
                        // Solo asignar la diferencia si no está ya completamente asignado
                        int capacidadActual = vueloReal.getCapacidadOcupada() != null ? vueloReal.getCapacidadOcupada() : 0;
                        int cantidadFaltante = evento.getCantidad() - capacidadActual;
                        if (cantidadFaltante > 0) {
                            vueloReal.asignar(cantidadFaltante);
                        }
                    }
                }

                // ⚡ CAMBIAR ESTADO: Si es el último vuelo, el envío llegó a su destino final -> FINALIZADO
                Envio envio = evento.getEnvio();
                if (evento.isUltimoVuelo() && envio != null && envio.getId() != null) {
                    try {
                        Optional<Envio> envioOpt = envioService.obtenerEnvioPorIdConPartesInicializadas(envio.getId());
                        if (envioOpt.isPresent()) {
                            Envio envioReal = envioOpt.get();
                            if (envioReal.getEstado() != Envio.EstadoEnvio.FINALIZADO) {
                                envioReal.setEstado(Envio.EstadoEnvio.FINALIZADO);
                                envioService.insertarEnvio(envioReal);
                                //System.out.printf("  ✅ [Estado] Envío %d cambió a FINALIZADO (llegó a destino final)%n", envio.getId());
                            }
                        }
                    } catch (Exception e) {
                        System.err.printf("❌ Error al cambiar estado del envío: %s%n", e.getMessage());
                    }
                }

                // 💾 PERSISTIR cambios en la base de datos
                try {
                    aeropuertoService.insertarAeropuerto(aeropuerto);
                    if (vueloReal != null) {
                        planDeVueloService.insertarPlanDeVuelo(vueloReal);
                    }
                } catch (Exception e) {
                    System.err.printf("❌ Error al persistir cambios del evento: %s%n", e.getMessage());
                    e.printStackTrace();
                }

//                System.out.printf("  ✈️ [Evento] Vuelo %d llegó a %s - Asignados %d productos (Aeropuerto Cap: %d/%d) [💾 Persistido]%n", vuelo != null && vuelo.getId() != null ? vuelo.getId() : "N/A", aeropuerto.getCodigo(), evento.getCantidad(), aeropuerto.getCapacidadOcupada(), aeropuerto.getCapacidadMaxima());
            } else if (evento.getTipo() == EventoTemporal.TipoEvento.SALIDA_VUELO) {
                // Vuelo sale: desasignar capacidad en el aeropuerto origen
                aeropuerto.desasignarCapacidad(evento.getCantidad());

                // ⚡ CAMBIAR ESTADO: Si es el primer vuelo, el envío inicia su ruta -> EN_RUTA
                Envio envio = evento.getEnvio();
                if (evento.isPrimerVuelo() && envio != null && envio.getId() != null) {
                    try {
                        Optional<Envio> envioOpt = envioService.obtenerEnvioPorIdConPartesInicializadas(envio.getId());
                        if (envioOpt.isPresent()) {
                            Envio envioReal = envioOpt.get();
                            if (envioReal.getEstado() != Envio.EstadoEnvio.EN_RUTA) {
                                envioReal.setEstado(Envio.EstadoEnvio.EN_RUTA);
                                envioService.insertarEnvio(envioReal);
                                //System.out.printf("  ✅ [Estado] Envío %d cambió a EN_RUTA (primer vuelo inició)%n", envio.getId());
                            }
                        }
                    } catch (Exception e) {
                        System.err.printf("❌ Error al cambiar estado del envío: %s%n", e.getMessage());
                    }
                }

                // 💾 PERSISTIR cambios en la base de datos
                try {
                    aeropuertoService.insertarAeropuerto(aeropuerto);
                } catch (Exception e) {
                    System.err.printf("❌ Error al persistir cambios del evento: %s%n", e.getMessage());
                    e.printStackTrace();
                }

//                System.out.printf("  ✈️ [Evento] Vuelo %d salió de %s - Desasignados %d productos (Aeropuerto Cap: %d/%d) [💾 Persistido]%n", evento.getVuelo() != null && evento.getVuelo().getId() != null ? evento.getVuelo().getId() : "N/A", aeropuerto.getCodigo(), evento.getCantidad(), aeropuerto.getCapacidadOcupada(), aeropuerto.getCapacidadMaxima());
            }
        } catch (Exception e) {
            System.err.printf("❌ Error al procesar evento: %s%n", e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Encuentra un plan de vuelo en el grasp por su ID
     */
    private Integer calcularCapacidadAsignada(Integer planId, List<Envio> envios) {
        if (envios == null)
            return null;

        int total = 0;
        for (Envio envio : envios) {
            if (envio.getParteAsignadas() == null)
                continue;

            for (ParteAsignada parte : envio.getParteAsignadas()) {
                if (parte.getRuta() == null)
                    continue;

                for (PlanDeVuelo vuelo : parte.getRuta()) {
                    if (vuelo.getId() != null && vuelo.getId().equals(planId)) {
                        total += parte.getCantidad();
                        break;
                    }
                }
            }
        }
        return total;
    }

    /**
     * Obtiene un resumen completo de la última simulación realizada
     * basado en los datos persistidos en la base de datos.
     * Este método funciona incluso después de que el planificador se haya detenido.
     */
    public Map<String, Object> obtenerResumenUltimaSimulacion() {
        Map<String, Object> resumen = new HashMap<>();

        // ⚡ OPTIMIZACIÓN: Usar envíos ya filtrados en memoria
        List<Envio> envios = this.enviosOriginales != null ? this.enviosOriginales : envioService.obtenerEnvios();

        // ⚡ Calcular estadísticas de pedidos por estado
        int totalPedidos = envios.size();
        int pedidosCompletados = 0;
        int pedidosParciales = 0;
        int pedidosSinAsignar = 0;
        int totalProductos = 0;
        int productosAsignados = 0;
        int productosSinAsignar = 0;

        // ⚡ Contadores por estado
        int enviosPlanificados = 0;
        int enviosEnRuta = 0;
        int enviosFinalizados = 0;
        int enviosEntregados = 0;
        int enviosSinEstado = 0;

        List<Map<String, Object>> pedidosSinCompletar = new ArrayList<>();

        for (Envio envio : envios) {
            if (envio.getNumProductos() != null) {
                totalProductos += envio.getNumProductos();
            }

            int cantidadAsignada = envio.cantidadAsignada();
            productosAsignados += cantidadAsignada;

            // ⚡ Contar por estado
            if (envio.getEstado() != null) {
                switch (envio.getEstado()) {
                    case PLANIFICADO:
                        enviosPlanificados++;
                        break;
                    case EN_RUTA:
                        enviosEnRuta++;
                        break;
                    case FINALIZADO:
                        enviosFinalizados++;
                        break;
                    case ENTREGADO:
                        enviosEntregados++;
                        break;
                }
            } else {
                enviosSinEstado++;
            }

            if (envio.estaCompleto()) {
                pedidosCompletados++;
            } else if (cantidadAsignada > 0) {
                pedidosParciales++;
                Map<String, Object> pedidoInfo = new HashMap<>();
                pedidoInfo.put("id", envio.getId());
                pedidoInfo.put("cliente", envio.getCliente());
                pedidoInfo.put("cantidadTotal", envio.getNumProductos());
                pedidoInfo.put("cantidadAsignada", cantidadAsignada);
                pedidoInfo.put("cantidadRestante", envio.cantidadRestante());
                pedidosSinCompletar.add(pedidoInfo);
            } else {
                pedidosSinAsignar++;
                Map<String, Object> pedidoInfo = new HashMap<>();
                pedidoInfo.put("id", envio.getId());
                pedidoInfo.put("cliente", envio.getCliente());
                pedidoInfo.put("cantidadTotal", envio.getNumProductos());
                pedidoInfo.put("cantidadAsignada", 0);
                pedidoInfo.put("cantidadRestante", envio.getNumProductos());
                pedidosSinCompletar.add(pedidoInfo);
            }
        }

        productosSinAsignar = totalProductos - productosAsignados;

        // ⚡ OPTIMIZACIÓN: Usar vuelos ya filtrados en memoria para estadísticas
        List<PlanDeVuelo> vuelos = grasp.getPlanesDeVuelo() != null ? grasp.getPlanesDeVuelo()
                : planDeVueloService.obtenerListaPlanesDeVuelo();
        Map<Integer, Integer> productosPorVuelo = new HashMap<>();
        int totalProductosEnVuelos = 0;
        int vuelosUtilizados = 0;

        for (Envio envio : envios) {
            if (envio.getParteAsignadas() != null) {
                for (ParteAsignada parte : envio.getParteAsignadas()) {
                    if (parte.getVuelosRuta() != null) {
                        for (ParteAsignadaPlanDeVuelo vueloRuta : parte.getVuelosRuta()) {
                            if (vueloRuta.getPlanDeVuelo() != null && vueloRuta.getPlanDeVuelo().getId() != null) {
                                int vueloId = vueloRuta.getPlanDeVuelo().getId();
                                int cantidad = parte.getCantidad();
                                productosPorVuelo.put(vueloId,
                                        productosPorVuelo.getOrDefault(vueloId, 0) + cantidad);
                                totalProductosEnVuelos += cantidad;
                            }
                        }
                    }
                }
            }
        }

        vuelosUtilizados = productosPorVuelo.size();

        // Preparar resumen de vuelos con productos
        List<Map<String, Object>> resumenVuelos = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : productosPorVuelo.entrySet()) {
            int vueloId = entry.getKey();
            int cantidad = entry.getValue();

            Optional<PlanDeVuelo> vueloOpt = vuelos.stream()
                    .filter(v -> v.getId() != null && v.getId().equals(vueloId))
                    .findFirst();

            if (vueloOpt.isPresent()) {
                PlanDeVuelo vuelo = vueloOpt.get();
                Map<String, Object> vueloInfo = new HashMap<>();
                vueloInfo.put("vueloId", vueloId);
                vueloInfo.put("productosAsignados", cantidad);
                vueloInfo.put("capacidadOcupada",
                        vuelo.getCapacidadOcupada() != null ? vuelo.getCapacidadOcupada() : 0);
                vueloInfo.put("capacidadMaxima", vuelo.getCapacidadMaxima() != null ? vuelo.getCapacidadMaxima() : 0);

                if (vuelo.getCiudadOrigen() != null) {
                    aeropuertoService.obtenerAeropuertoPorId(vuelo.getCiudadOrigen())
                            .ifPresent(a -> vueloInfo.put("origen", a.getCodigo()));
                }
                if (vuelo.getCiudadDestino() != null) {
                    aeropuertoService.obtenerAeropuertoPorId(vuelo.getCiudadDestino())
                            .ifPresent(a -> vueloInfo.put("destino", a.getCodigo()));
                }

                resumenVuelos.add(vueloInfo);
            }
        }

        // Calcular productos en cada aeropuerto al final de la simulación
        List<Aeropuerto> aeropuertos = aeropuertoService.obtenerTodosAeropuertos();
        List<Map<String, Object>> productosPorAeropuerto = new ArrayList<>();

        for (Aeropuerto aeropuerto : aeropuertos) {
            if (aeropuerto.getCapacidadOcupada() != null && aeropuerto.getCapacidadOcupada() > 0) {
                Map<String, Object> aeropuertoInfo = new HashMap<>();
                aeropuertoInfo.put("id", aeropuerto.getId());
                aeropuertoInfo.put("codigo", aeropuerto.getCodigo());
                aeropuertoInfo.put("ciudad", aeropuerto.getCiudad());
                aeropuertoInfo.put("productosAlmacenados", aeropuerto.getCapacidadOcupada());
                aeropuertoInfo.put("capacidadMaxima",
                        aeropuerto.getCapacidadMaxima() != null ? aeropuerto.getCapacidadMaxima() : 0);
                aeropuertoInfo.put("capacidadDisponible",
                        (aeropuerto.getCapacidadMaxima() != null ? aeropuerto.getCapacidadMaxima() : 0) -
                                aeropuerto.getCapacidadOcupada());
                productosPorAeropuerto.add(aeropuertoInfo);
            }
        }

        // Información general de la simulación
        Map<String, Object> infoGeneral = new HashMap<>();
        infoGeneral.put("cicloActual", cicloActual.get());
        infoGeneral.put("modoSimulacion", modoSimulacion != null ? modoSimulacion.name() : "NORMAL");
        infoGeneral.put("fechaInicioSimulacion",
                tiempoInicioSimulacion != null ? tiempoInicioSimulacion.toString() : "N/A");
        infoGeneral.put("ultimoHorizontePlanificado",
                ultimoHorizontePlanificado != null ? ultimoHorizontePlanificado.toString() : "N/A");
        infoGeneral.put("enEjecucion", enEjecucion);
        resumen.put("informacionGeneral", infoGeneral);

        // Estadísticas de pedidos
        Map<String, Object> statsPedidos = new HashMap<>();
        statsPedidos.put("totalPedidos", totalPedidos);
        statsPedidos.put("pedidosCompletados", pedidosCompletados);
        statsPedidos.put("pedidosParciales", pedidosParciales);
        statsPedidos.put("pedidosSinAsignar", pedidosSinAsignar);
        statsPedidos.put("tasaExito", totalPedidos > 0 ? (pedidosCompletados * 100.0 / totalPedidos) : 0.0);
        resumen.put("estadisticasPedidos", statsPedidos);

        // ⚡ Estadísticas por estado de envío
        Map<String, Object> statsPorEstado = new HashMap<>();
        statsPorEstado.put("enviosPlanificados", enviosPlanificados);
        statsPorEstado.put("enviosEnRuta", enviosEnRuta);
        statsPorEstado.put("enviosFinalizados", enviosFinalizados);
        statsPorEstado.put("enviosEntregados", enviosEntregados);
        statsPorEstado.put("enviosSinEstado", enviosSinEstado);
        resumen.put("estadisticasPorEstado", statsPorEstado);

        // Estadísticas de productos
        Map<String, Object> statsProductos = new HashMap<>();
        statsProductos.put("totalProductos", totalProductos);
        statsProductos.put("productosAsignados", productosAsignados);
        statsProductos.put("productosSinAsignar", productosSinAsignar);
        statsProductos.put("tasaAsignacion", totalProductos > 0 ? (productosAsignados * 100.0 / totalProductos) : 0.0);
        resumen.put("estadisticasProductos", statsProductos);

        // Estadísticas de vuelos
        Map<String, Object> statsVuelos = new HashMap<>();
        statsVuelos.put("totalVuelosDisponibles", vuelos.size());
        statsVuelos.put("vuelosUtilizados", vuelosUtilizados);
        statsVuelos.put("totalProductosEnVuelos", totalProductosEnVuelos);
        resumen.put("estadisticasVuelos", statsVuelos);

        // Detalles adicionales
        resumen.put("pedidosSinCompletar", pedidosSinCompletar);
        resumen.put("productosPorVuelo", resumenVuelos);
        resumen.put("productosPorAeropuerto", productosPorAeropuerto);
        resumen.put("timestamp", LocalDateTime.now().toString());

        return resumen;
    }
}
