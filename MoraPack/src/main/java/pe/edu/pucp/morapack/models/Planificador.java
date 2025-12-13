package pe.edu.pucp.morapack.models;

import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;

import pe.edu.pucp.morapack.services.AeropuertoService;
import pe.edu.pucp.morapack.services.servicesImp.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import pe.edu.pucp.morapack.dtos.VueloPlanificadorDto;
import pe.edu.pucp.morapack.dtos.AeropuertoEstadoDto;

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
    private boolean enEjecucion = false;
    private volatile boolean cicloEnEjecucion = false; // ⚡ Flag para evitar que otras tareas compitan con GRASP
    private LocalDateTime tiempoSimuladoActual; // Tiempo simulado actual para verificaciones de liberación

    // Configuración de planificación programada
    private static final int SA_MINUTOS = 2; // Salto del algoritmo - ejecutar cada 2 minutos
    private static final int K_NORMAL = 120; // Factor de consumo - planificar 240 minutos adelante (modo normal y
    // semanal)
    private static final int K_COLAPSO = 240; // Factor de consumo - planificar 480 minutos adelante (modo colapso)
    private static final int TA_SEGUNDOS = 100; // ⚡ OPTIMIZADO: Tiempo máximo GRASP - ~1 minuto (antes 100s)

    // Método para obtener el valor de K según el modo de simulación
    private int obtenerK() {
        if (modoSimulacion == ModoSimulacion.OPERACIONES_DIARIAS) {
            return 1; // K=1 para operaciones diarias en tiempo real
        }
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

    // Control para modo COLAPSO: rastrear cuando se completa la semana y pedidos sin planificar
    private boolean semanaCompletaColapso = false;
    private boolean pedidoSinPlanificarEncontrado = false; // Para COLAPSO: indica si se encontró un pedido sin planificar
    private int ciclosDespuesSemanaCompleta = 0;
    private static final int CICLOS_DESPUES_SEMANA_COMPLETA = 5; // Para COLAPSO 2025

    // ⚡ CACHÉ DE VUELOS: Evita recargar 8000+ vuelos cada 2 minutos
    private ArrayList<PlanDeVuelo> vuelosCacheados;
    private LocalDateTime cacheVuelosInicio;
    private LocalDateTime cacheVuelosFin;

    // ⚡ SISTEMA DE EVENTOS TEMPORALES: Separar planificación (GRASP) de ejecución
    // temporal
    // Scheduler dedicado para eventos temporales (ejecución individual en tiempo
    // exacto)
    private ScheduledExecutorService schedulerEventos;
    // ✅ Lista thread-safe de ScheduledFuture para poder cancelar eventos si es
    // necesario
    // Collections.synchronizedList previene ConcurrentModificationException cuando
    // se modifica
    // desde múltiples threads (crearEventosTemporales y limpiarEventosEjecutados)
    private final List<ScheduledFuture<?>> eventosProgramados = Collections.synchronizedList(new ArrayList<>());

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
        private final boolean esPrimerVuelo; // Si es el primer vuelo de la ruta
        private final boolean esUltimoVuelo; // Si es el último vuelo de la ruta
        private final Envio envio; // Referencia al envío para cambiar estados

        enum TipoEvento {
            LLEGADA_VUELO, // Vuelo llega a destino -> asignar capacidad en aeropuerto destino
            SALIDA_VUELO, // Vuelo sale de origen -> desasignar capacidad en aeropuerto origen (solo si no
            // es primer vuelo)
            LIBERAR_PRODUCTOS // Liberar productos del aeropuerto destino final después de 1 hora
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

        public ZonedDateTime getTiempoEvento() {
            return tiempoEvento;
        }

        public TipoEvento getTipo() {
            return tipo;
        }

        public PlanDeVuelo getVuelo() {
            return vuelo;
        }

        public ParteAsignada getParte() {
            return parte;
        }

        public Integer getCantidad() {
            return cantidad;
        }

        public Integer getAeropuertoId() {
            return aeropuertoId;
        }

        public boolean isPrimerVuelo() {
            return esPrimerVuelo;
        }

        public boolean isUltimoVuelo() {
            return esUltimoVuelo;
        }

        public Envio getEnvio() {
            return envio;
        }
    }

    // Modos de simulación
    public enum ModoSimulacion {
        NORMAL, // Modo original - planifica todos los pedidos
        SEMANAL, // Simulación semanal con fecha inicio y fin
        COLAPSO, // Simulación de colapso - solo fecha inicio
        OPERACIONES_DIARIAS // Operaciones diarias en tiempo real - K=1, sin fecha fin
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
            if (modo == ModoSimulacion.SEMANAL || modo == ModoSimulacion.COLAPSO
                    || modo == ModoSimulacion.OPERACIONES_DIARIAS) {
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
                } else if (modo == ModoSimulacion.OPERACIONES_DIARIAS) {
                    // Modo OPERACIONES_DIARIAS no tiene fecha fin
                    System.out.println("⏰ Modo OPERACIONES_DIARIAS: Sin fecha fin (tiempo real)");
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
        // ✅ OPTIMIZADO: Ajustar threads según núcleos disponibles para reducir context
        // switching
        if (schedulerEventos == null) {
            int numCores = Runtime.getRuntime().availableProcessors();
            int numThreadsEventos = numCores <= 2 ? 2 : Math.min(4, numCores);
            schedulerEventos = Executors.newScheduledThreadPool(numThreadsEventos);
            System.out.printf("⏰ Scheduler de eventos temporales inicializado con %d threads (para %d núcleos)%n",
                    numThreadsEventos, numCores);
        }

        // ✅ INICIALIZAR ultimoTiempoEjecucion antes del primer ciclo
        if (ultimoTiempoEjecucion == null) {
            ultimoTiempoEjecucion = tiempoInicioSimulacion;
        }

        // Ejecutar el primer ciclo inmediatamente
        try {
            ejecutarCicloPlanificacion(tiempoInicioSimulacion);
            // ✅ Asegurar que ultimoTiempoEjecucion esté inicializado después del primer
            // ciclo
            if (ultimoTiempoEjecucion == null) {
                ultimoTiempoEjecucion = tiempoInicioSimulacion;
            }
        } catch (Exception e) {
            System.err.printf("❌ Error al ejecutar el primer ciclo: %s%n", e.getMessage());
            e.printStackTrace();
            // ✅ INICIALIZAR ultimoTiempoEjecucion incluso si hay error
            if (ultimoTiempoEjecucion == null) {
                ultimoTiempoEjecucion = tiempoInicioSimulacion;
            }
            // Continuar con la programación aunque haya error en el primer ciclo
        }

        // Programar ejecuciones posteriores cada SA_MINUTOS minutos
        if (scheduler != null) {
            tareaProgramada = scheduler.scheduleAtFixedRate(() -> {
                try {
                    // ✅ Verificar que ultimoTiempoEjecucion no sea null
                    if (ultimoTiempoEjecucion == null) {
                        System.err.println(
                                "⚠️ ADVERTENCIA: ultimoTiempoEjecucion es null, usando tiempoInicioSimulacion");
                        ultimoTiempoEjecucion = tiempoInicioSimulacion != null ? tiempoInicioSimulacion
                                : LocalDateTime.now();
                    }
                    LocalDateTime tiempoActual = obtenerTiempoActualSimulacion();
                    ejecutarCicloPlanificacion(tiempoActual);
                } catch (Exception e) {
                    System.err.printf("❌ Error crítico en tarea programada (ciclo %d): %s%n",
                            cicloActual.get() + 1, e.getMessage());
                    e.printStackTrace();
                    // ✅ NO detener automáticamente, solo registrar el error
                    // El planificador continuará intentando en el siguiente ciclo
                }
            }, SA_MINUTOS, SA_MINUTOS, TimeUnit.MINUTES);

            // ⚡ ELIMINADO: Tarea periódica de liberación de productos
            // Ahora se usan eventos programados específicos (LIBERAR_PRODUCTOS) que se
            // ejecutan
            // exactamente 1 hora después de cada llegada al destino final
            // Esto es más preciso y evita problemas de tiempo simulado avanzando demasiado
            // rápido
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

        // ⚡ Cancelar todos los eventos temporales programados
        if (eventosProgramados != null) {
            // ✅ Sincronizar el acceso para evitar ConcurrentModificationException
            synchronized (eventosProgramados) {
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

        // ⚡ Limpiar caché de vuelos al detener
        vuelosCacheados = null;
        cacheVuelosInicio = null;
        cacheVuelosFin = null;

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

    /**
     * Obtiene el año de la fecha de inicio de la simulación
     * @return El año de fechaInicioSimulacion, o 0 si es null
     */
    private int obtenerAnioFechaInicio() {
        if (fechaInicioSimulacion == null) {
            return 0;
        }
        return fechaInicioSimulacion.getYear();
    }

    private void ejecutarCicloPlanificacion(LocalDateTime tiempoEjecucion) {
        if (!enEjecucion)
            return;

        // ⚡ Marcar que el ciclo está en ejecución para evitar que liberarProductos
        // compita
        cicloEnEjecucion = true;

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

            // 1.1. Verificar semana completa en modo COLAPSO (solo para 2025)
            if (modoSimulacion == ModoSimulacion.COLAPSO && obtenerAnioFechaInicio() == 2025) {
                if (!semanaCompletaColapso && tiempoInicioSimulacion != null) {
                    // Verificar si han pasado 7 días desde el inicio
                    LocalDateTime fechaSemanaCompleta = tiempoInicioSimulacion.plusDays(7);
                    if (this.ultimoHorizontePlanificado.isAfter(fechaSemanaCompleta) ||
                            this.ultimoHorizontePlanificado.isEqual(fechaSemanaCompleta)) {
                        semanaCompletaColapso = true;
                        // Si ya se encontró un pedido sin planificar, empezar a contar ciclos
                        if (pedidoSinPlanificarEncontrado) {
                            ciclosDespuesSemanaCompleta = 0;
                            //System.out.println("📅 [COLAPSO 2025] Semana completa alcanzada - comenzando conteo de 5 ciclos (pedido sin planificar encontrado)");
                        }
                    }
                }

                // Si la semana ya está completa Y hay un pedido sin planificar, incrementar contador
                if (semanaCompletaColapso && pedidoSinPlanificarEncontrado) {
                    ciclosDespuesSemanaCompleta++;
                    //System.out.printf("📅 [COLAPSO 2025] Ciclo %d después de semana completa con pedido sin planificar (límite: %d)%n", ciclosDespuesSemanaCompleta, CICLOS_DESPUES_SEMANA_COMPLETA);

                    if (ciclosDespuesSemanaCompleta >= CICLOS_DESPUES_SEMANA_COMPLETA) {
                        //System.out.println("🛑 [COLAPSO 2025] Deteniendo planificación: 5 ciclos después de semana completa con pedido sin planificar");
                        detenerPlanificacion();
                        return;
                    }
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

            if (pedidosParaPlanificar.isEmpty()) {
                System.out.println("✅ No hay pedidos pendientes en este horizonte");

                // ✅ LIMPIAR EVENTOS EJECUTADOS cuando no hay pedidos (momento ideal para
                // limpieza)
                limpiarEventosEjecutados();

                // ⚡ Marcar ciclo como terminado
                cicloEnEjecucion = false;

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
            System.out.println("🚀 [ANTES] Ejecutando GRASP...");
            long inicioGrasp = System.currentTimeMillis();
            Solucion solucion = ejecutarGRASPConTimeout(pedidosParaPlanificar, tiempoEjecucion);
            long tiempoGrasp = System.currentTimeMillis() - inicioGrasp;
            System.out.printf("✅ [DESPUÉS] GRASP terminado en %d ms. Solución con %d envíos%n",
                    tiempoGrasp, solucion != null && solucion.getEnvios() != null ? solucion.getEnvios().size() : 0);
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

                // Lógica de detención según año y modo
                int anioInicio = obtenerAnioFechaInicio();

                if (anioInicio == 2026) {
                    if (ciclo >= 30) {
                        System.out.println("🛑 Deteniendo planificación: hay envíos sin planificar");
                        detenerPlanificacion();
                        return;
                    } else {
                        //System.out.println("ℹ️ [2026] Continuando planificación: hay envíos sin planificar pero ciclo < 30");
                        System.out.println("continuar");
                    }
                }

                if (anioInicio == 2025) {
                    if (modoSimulacion == ModoSimulacion.SEMANAL) {
                        System.out.println("continuar");
                        //System.out.println("ℹ️ [SEMANAL 2025] Continuando planificación a pesar de envíos sin planificar");
                    } else if (modoSimulacion == ModoSimulacion.COLAPSO) {
                        // COLAPSO 2025: Marcar que se encontró un pedido sin planificar
                        // Se detendrá 5 ciclos después de completar la semana
                        pedidoSinPlanificarEncontrado = true;
                        //System.out.println("⚠️ [COLAPSO 2025] Pedido sin planificar encontrado - se detendrá 5 ciclos después de completar semana");

                        // Si ya se completó la semana, empezar a contar ciclos ahora
                        if (semanaCompletaColapso) {
                            ciclosDespuesSemanaCompleta = 0;
                            //System.out.println("📅 [COLAPSO 2025] Semana ya completada - comenzando conteo de 5 ciclos");
                        }
                    }
                }
            }

            // ⚡ CREAR EVENTOS TEMPORALES: Convertir las rutas planificadas en eventos
            // que se procesarán cuando el tiempo avance
            crearEventosTemporales(solucion, inicioHorizonte);

            // ✅ LIMPIAR EVENTOS EJECUTADOS para liberar memoria
            limpiarEventosEjecutados();

            // ✅ PERSISTIR CAMBIOS EN LA BASE DE DATOS
            try {
                System.out.println("💾 [ANTES] Iniciando persistirCambios...");
                long inicioPersistir = System.currentTimeMillis();
                persistirCambios(solucion);
                long tiempoPersistir = System.currentTimeMillis() - inicioPersistir;
                System.out.printf("💾 [DESPUÉS] persistirCambios terminado en %d ms%n", tiempoPersistir);
                System.out.println("💾 Cambios persistidos en la base de datos");
            } catch (Exception e) {
                System.err.printf("❌ Error al persistir cambios: %s%n", e.getMessage());
                e.printStackTrace();
            }

            // ✅ ACTUALIZAR el horizonte para el próximo ciclo
            this.ultimoHorizontePlanificado = finHorizonte;

            // ✅ ENVIAR ACTUALIZACIÓN VÍA WEBSOCKET
            if (modoSimulacion == ModoSimulacion.OPERACIONES_DIARIAS) {
                // Nueva ruta: estado detallado para el mapa en tiempo real
                notificarEstadoCicloDia(solucion);
            } else {
                // Comportamiento anterior para NORMAL / SEMANAL / COLAPSO
                webSocketService.enviarActualizacionCiclo(solucion, ciclo);
            }

            notificarEstadoCicloDia(ultimaSolucion);

            // 6. Mostrar resultados
            mostrarResultadosCiclo(solucion, pedidosParaPlanificar, ciclo);

            System.out.printf("✅ CICLO %d COMPLETADO - %d/%d envíos asignados%n", ciclo,
                    solucion.getEnviosCompletados(), solucion.getEnvios().size());

            ultimoTiempoEjecucion = tiempoEjecucion;

            // ⚡ Marcar ciclo como terminado
            cicloEnEjecucion = false;
        } catch (Exception e) {
            // ✅ ENVIAR ERROR VÍA WEBSOCKET
            webSocketService.enviarError("Error: " + e.getMessage(), ciclo);
            System.err.printf("❌ CICLO %d - ERROR: %s%n", ciclo, e.getMessage());
            actualizarEstadisticasError(ciclo, e.getMessage());
            // ⚡ Marcar ciclo como terminado incluso en error
            cicloEnEjecucion = false;
        }
    }

    public List<VueloPlanificadorDto> construirVuelosDto(Solucion solucionActual) {
        List<VueloPlanificadorDto> lista = new ArrayList<>();

        if (solucionActual.getEnvios() == null)
            return lista;

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        for (Envio envio : solucionActual.getEnvios()) {
            if (envio.getParteAsignadas() == null)
                continue;

            for (ParteAsignada parte : envio.getParteAsignadas()) {
                if (parte.getRuta() == null)
                    continue;

                for (PlanDeVuelo vuelo : parte.getRuta()) {
                    VueloPlanificadorDto dto = new VueloPlanificadorDto();
                    dto.setId(vuelo.getId() != null ? vuelo.getId().longValue() : null);

                    // Horas en el mismo formato que ya usas en tus endpoints
                    String salidaStr = formatFechaConOffset(
                            vuelo.getZonedHoraOrigen(),
                            vuelo.getHoraOrigen(),
                            vuelo.getHusoHorarioOrigen(),
                            formatter);
                    String llegadaStr = formatFechaConOffset(
                            vuelo.getZonedHoraDestino(),
                            vuelo.getHoraDestino(),
                            vuelo.getHusoHorarioDestino(),
                            formatter);
                    dto.setHoraSalida(salidaStr);
                    dto.setHoraLlegada(llegadaStr);

                    // Origen
                    VueloPlanificadorDto.AeropuertoDto origenDto = new VueloPlanificadorDto.AeropuertoDto();
                    Aeropuerto origen = vuelo.getCiudadOrigen() != null
                            ? obtenerAeropuertoPorId(vuelo.getCiudadOrigen())
                            : null;
                    if (origen != null) {
                        origenDto.setId(origen.getId().longValue());
                        origenDto.setCiudad(origen.getCiudad());
                        origenDto.setCodigo(origen.getCodigo());
                    }
                    dto.setOrigen(origenDto);

                    // Destino
                    VueloPlanificadorDto.AeropuertoDto destinoDto = new VueloPlanificadorDto.AeropuertoDto();
                    Aeropuerto destino = vuelo.getCiudadDestino() != null
                            ? obtenerAeropuertoPorId(vuelo.getCiudadDestino())
                            : null;
                    if (destino != null) {
                        destinoDto.setId(destino.getId().longValue());
                        destinoDto.setCiudad(destino.getCiudad());
                        destinoDto.setCodigo(destino.getCodigo());
                    }
                    dto.setDestino(destinoDto);

                    // Envío asociado a este vuelo
                    List<VueloPlanificadorDto.EnvioAsignadoDto> enviosAsignados = new ArrayList<>();
                    VueloPlanificadorDto.EnvioAsignadoDto envioDto = new VueloPlanificadorDto.EnvioAsignadoDto();
                    if (envio.getId() != null) {
                        envioDto.setEnvioId(envio.getId().longValue());
                    }
                    envioDto.setCantidad(parte.getCantidad());
                    enviosAsignados.add(envioDto);

                    dto.setEnviosAsignados(enviosAsignados);

                    lista.add(dto);
                }
            }
        }

        return lista;
    }

    public List<AeropuertoEstadoDto> construirAeropuertosDto() {
        List<AeropuertoEstadoDto> lista = new ArrayList<>();
        if (grasp.getAeropuertos() == null)
            return lista;

        for (Aeropuerto a : grasp.getAeropuertos()) {
            AeropuertoEstadoDto dto = new AeropuertoEstadoDto();
            dto.setIdAeropuerto(
                    a.getId() != null ? a.getId().longValue() : null);
            dto.setId(
                    a.getId() != null ? a.getId().longValue() : null);

            dto.setCapacidadOcupada(
                    a.getCapacidadOcupada() != null ? a.getCapacidadOcupada() : 0);
            dto.setCapacidadMaxima(
                    a.getCapacidadMaxima() != null ? a.getCapacidadMaxima() : 0);
            lista.add(dto);
        }
        return lista;
    }

    private long getSimMillis() {
        if (tiempoSimuladoActual != null) {
            // Asumimos que el tiempo simulado está en UTC-5 (Perú) y lo convertimos a epoch
            // ms
            ZonedDateTime zdt = tiempoSimuladoActual.atZone(ZoneOffset.ofHours(-5));
            return zdt.toInstant().toEpochMilli();
        }
        // Fallback: tiempo real del servidor
        return System.currentTimeMillis();
    }

    // 🛰️ Notificación especial para el modo OPERACIONES_DIARIAS (mapa en tiempo
    // real)
    private void notificarEstadoCicloDia(Solucion solucionActual) {
        if (solucionActual == null)
            return;

        // 1. Vuelos con info que el front necesita (origen, destino, horas, envíos)
        List<VueloPlanificadorDto> vuelosDto = construirVuelosDto(solucionActual);

        // 2. Estado dinámico de aeropuertos (ocupación / capacidad)
        List<AeropuertoEstadoDto> aeropuertosDto = construirAeropuertosDto();

        // 3. Tiempo de simulación en milisegundos (usar tiempoSimuladoActual como
        // referencia)
        long simMs = getSimMillis();

        // 4. Enviar al topic STOMP específico de la simulación diaria
        webSocketService.enviarEstadoVuelosYAeropuertosDia(vuelosDto, aeropuertosDto, simMs);
    }

    private List<Envio> obtenerPedidosEnVentana(LocalDateTime inicio, LocalDateTime fin) {
        List<Envio> pedidosNuevos = new ArrayList<>();

        // ⚡ MODO OPERACIONES_DIARIAS: Cargar envíos con estado NULL y filtrar por fecha
        // considerando husos horarios
        if (modoSimulacion == ModoSimulacion.OPERACIONES_DIARIAS) {
            System.out.printf(
                    "📦 [obtenerPedidosEnVentana] Modo OPERACIONES_DIARIAS: Cargando envíos con estado NULL hasta %s (UTC)%n",
                    inicio.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

            try {
                // Cargar todos los envíos con estado NULL
                List<Envio> enviosPendientes = envioService.obtenerEnviosPendientes();
                System.out.printf("✅ [obtenerPedidosEnVentana] Envíos pendientes (estado NULL) cargados: %d%n",
                        enviosPendientes.size());

                // Convertir el inicio del horizonte a UTC para comparar
                ZonedDateTime inicioUTC = inicio.atZone(ZoneOffset.UTC);

                // Filtrar envíos cuya fechaIngreso (en su huso horario) <= inicio (en UTC)
                // Convertir cada fechaIngreso a UTC usando su huso horario
                for (Envio envio : enviosPendientes) {
                    // ⚡ Inicializar zonedFechaIngreso si es null (defensa adicional)
                    if (envio.getZonedFechaIngreso() == null && envio.getFechaIngreso() != null
                            && envio.getHusoHorarioDestino() != null) {
                        try {
                            Integer offsetDestino = Integer.parseInt(envio.getHusoHorarioDestino());
                            ZoneOffset zoneDestino = ZoneOffset.ofHours(offsetDestino);
                            envio.setZonedFechaIngreso(envio.getFechaIngreso().atZone(zoneDestino));
                        } catch (Exception e) {
                            System.err.printf("⚠️ Error al inicializar zonedFechaIngreso para envío %d: %s%n",
                                    envio.getId(), e.getMessage());
                            continue; // Saltar este envío si no se puede inicializar
                        }
                    }

                    // Si aún es null después del intento de inicialización, saltar este envío
                    if (envio.getZonedFechaIngreso() == null) {
                        System.err.printf("⚠️ Envío %d no tiene zonedFechaIngreso inicializado, saltando...%n",
                                envio.getId());
                        continue;
                    }

                    // Convertir la fecha de ingreso del envío a UTC para comparar correctamente
                    ZonedDateTime tiempoPedidoUTC = envio.getZonedFechaIngreso()
                            .withZoneSameInstant(ZoneOffset.UTC);

                    // Incluir solo envíos cuya fechaIngreso (en UTC) <= inicio (en UTC)
                    if (!tiempoPedidoUTC.isAfter(inicioUTC)) {
                        pedidosNuevos.add(crearCopiaEnvio(envio));
                    }
                }

                System.out.printf(
                        "✅ [obtenerPedidosEnVentana] Envíos filtrados (fechaIngreso <= inicio horizonte): %d%n",
                        pedidosNuevos.size());

                // ⚡ Configurar hubs para los envíos filtrados
                ArrayList<Aeropuerto> hubs = grasp.getHubs();
                if (hubs != null && !hubs.isEmpty()) {
                    ArrayList<Aeropuerto> uniqHubs = new ArrayList<>(new LinkedHashSet<>(hubs));
                    for (Envio e : pedidosNuevos) {
                        e.setAeropuertosOrigen(new ArrayList<>(uniqHubs));
                    }
                    System.out.printf("⚙️ [obtenerPedidosEnVentana] Hubs configurados para %d envíos%n",
                            pedidosNuevos.size());
                }

                return pedidosNuevos;
            } catch (Exception e) {
                System.err.printf("❌ Error al cargar envíos pendientes desde BD: %s%n", e.getMessage());
                e.printStackTrace();
                return new ArrayList<>();
            }
        }

        // ⚡ OPTIMIZACIÓN CRÍTICA: Cargar envíos desde BD solo del rango actual (modos
        // NORMAL, SEMANAL, COLAPSO)
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
                    System.out.printf("⚙️ [obtenerPedidosEnVentana] Hubs configurados para %d envíos%n",
                            enviosEnRango.size());
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
        copia.setFechaIngreso(original.getFechaIngreso());
        copia.setHusoHorarioDestino(original.getHusoHorarioDestino());
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
            // (la copia ya tiene una lista vacía, que es lo que queremos para el nuevo
            // ciclo)
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
        // Usar AtomicReference para compartir la mejor solución entre threads
        AtomicReference<Solucion> mejorSolucionHastaAhora = new AtomicReference<>(null);

        Future<Solucion> future = executor.submit(() -> {
            // Preparar GRASP para este ciclo específico
            grasp.setEnvios(new ArrayList<>(pedidos));
            grasp.setEnviosPorDiaPropio();

            // Ejecutar GRASP modificado para respetar el tiempo máximo y compartir mejor solución
            return ejecutarGRASPLimitado(tiempoEjecucion, mejorSolucionHastaAhora);
        });

        try {
            return future.get(TA_SEGUNDOS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            System.out.println("⏰ TIMEOUT: GRASP excedió el tiempo máximo de " + TA_SEGUNDOS + " segundos");
            future.cancel(true);

            // Obtener la mejor solución encontrada hasta el momento
            Solucion mejorSolucion = mejorSolucionHastaAhora.get();
            if (mejorSolucion != null) {
                System.out.println("✅ Devolviendo mejor solución encontrada hasta el momento del timeout");
                return mejorSolucion;
            } else {
                System.out.println("⚠️ No se encontró ninguna solución antes del timeout");
                return crearSolucionVacia();
            }
        } catch (Exception e) {
            System.err.println("❌ Error en ejecución de GRASP: " + e.getMessage());
            // Intentar devolver la mejor solución encontrada incluso si hay error
            Solucion mejorSolucion = mejorSolucionHastaAhora.get();
            return mejorSolucion != null ? mejorSolucion : crearSolucionVacia();
        } finally {
            executor.shutdownNow();
        }
    }

    private Solucion ejecutarGRASPLimitado(LocalDateTime tiempoEjecucion, AtomicReference<Solucion> mejorSolucionRef) {
        Solucion mejorSolucion = null;
        long inicioEjecucion = System.currentTimeMillis();

        // ⚡ OPTIMIZACIÓN: Reutilizar vuelos ya cargados y filtrados en inicialización
        ArrayList<PlanDeVuelo> planesDeVuelo = grasp.getPlanesDeVuelo();
        if (planesDeVuelo == null || planesDeVuelo.isEmpty()) {
            return null;
        }

        ArrayList<Envio> enviosParaProgramar = grasp.getEnvios();

        // Inicializar los caches necesarios para trabajar con estos vuelos
        // Pasar los envíos para filtrar por ventana temporal
        grasp.inicializarCachesParaVuelos(planesDeVuelo, enviosParaProgramar);

        // Ejecutar GRASP con verificación de timeout periódica
        Solucion solucionDia = grasp.ejecutarGraspConTimeout(enviosParaProgramar, planesDeVuelo,
                TA_SEGUNDOS * 1000, inicioEjecucion, mejorSolucionRef);

        if (mejorSolucion == null || (solucionDia != null && grasp.esMejor(solucionDia, mejorSolucion))) {
            mejorSolucion = solucionDia;
        }

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

        // Mantener compatibilidad con campos antiguos (sin queries)
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

    /**
     * Calcula el total de pedidos planificados basándose en los envíos que tienen
     * al menos una parte asignada (es decir, que tienen una ruta de vuelos
     * asignada)
     */
    private int calcularTotalPedidosPlanificados() {
        try {
            List<Envio> enviosConPartes = envioService.obtenerEnviosConPartesAsignadas();
            return enviosConPartes != null ? enviosConPartes.size() : 0;
        } catch (Exception e) {
            System.err.printf("❌ Error al calcular pedidos planificados: %s%n", e.getMessage());
            return 0;
        }
    }

    /**
     * ⚡ OPTIMIZADO: Calcula el total de envíos por estado desde la base de datos.
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

        System.out.printf("   • Tiempo medio de entrega: %s%n",
                formatDuracion(solucion.getLlegadaMediaPonderada()));

        final int MAX_ENVIOS_DETALLE = 5;
        System.out.printf("%n📋 DETALLE DE RUTAS ASIGNADAS - CICLO %d (primeros %d de %d)%n",
                ciclo, Math.min(MAX_ENVIOS_DETALLE, solucion.getEnvios().size()), solucion.getEnvios().size());
        System.out.println("=".repeat(80));

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM dd HH:mm", Locale.forLanguageTag("es-ES"));

        // Crear mapa de vuelos actualizados desde BD para evitar múltiples consultas
        Map<Integer, PlanDeVuelo> vuelosActualizadosMap = new HashMap<>();
        Set<Integer> vueloIds = new HashSet<>();

        // Recopilar todos los IDs de vuelos que se mostrarán
        for (Envio envio : solucion.getEnvios()) {
            try {
                List<ParteAsignada> partes = envio.getParteAsignadas();
                if (partes != null) {
                    for (ParteAsignada parte : partes) {
                        if (parte.getRuta() != null) {
                            for (PlanDeVuelo vuelo : parte.getRuta()) {
                                if (vuelo.getId() != null) {
                                    vueloIds.add(vuelo.getId());
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Ignorar errores al obtener partes
            }
        }

        // Cargar todos los vuelos actualizados de una vez
        if (!vueloIds.isEmpty()) {
            try {
                List<PlanDeVuelo> vuelosActualizados = planDeVueloService.obtenerPlanesDeVueloPorIds(new ArrayList<>(vueloIds));
                for (PlanDeVuelo vuelo : vuelosActualizados) {
                    if (vuelo.getId() != null) {
                        vuelosActualizadosMap.put(vuelo.getId(), vuelo);
                    }
                }
            } catch (Exception e) {
                System.err.printf("⚠️ Error al cargar vuelos actualizados: %s%n", e.getMessage());
            }
        }

        int enviosMostrados = 0;
        for (Envio envio : solucion.getEnvios()) {
            if (enviosMostrados >= MAX_ENVIOS_DETALLE) {
                break;
            }
            List<ParteAsignada> partesAsignadas = null;
            try {
                partesAsignadas = envio.getParteAsignadas();
            } catch (Exception e) {
                if (envio.getId() != null) {
                    try {
                        Optional<Envio> envioOpt = envioService.obtenerEnvioPorIdConPartesInicializadas(envio.getId());
                        if (envioOpt.isPresent()) {
                            partesAsignadas = envioOpt.get().getParteAsignadas();
                        }
                    } catch (Exception ex) {
                        partesAsignadas = new ArrayList<>();
                    }
                } else {
                    partesAsignadas = new ArrayList<>();
                }
            }

            boolean tienePartes = partesAsignadas != null && !partesAsignadas.isEmpty();
            if (envio.estaCompleto() || tienePartes) {
                enviosMostrados++;

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

                            // Obtener capacidad ocupada actualizada desde el mapa (ya cargado desde BD)
                            Integer capacidadOcupada = vuelo.getCapacidadOcupada();
                            Integer capacidadMaxima = vuelo.getCapacidadMaxima();

                            if (vuelo.getId() != null) {
                                PlanDeVuelo vueloActualizado = vuelosActualizadosMap.get(vuelo.getId());
                                if (vueloActualizado != null) {
                                    capacidadOcupada = vueloActualizado.getCapacidadOcupada();
                                    capacidadMaxima = vueloActualizado.getCapacidadMaxima();
                                }
                            }

                            System.out.printf("      ✈️  %s → %s | %s - %s | Cap: %d/%d%n",
                                    obtenerAeropuertoPorId(vuelo.getCiudadOrigen()).getCodigo(),
                                    obtenerAeropuertoPorId(vuelo.getCiudadDestino()).getCodigo(),
                                    formatFechaConOffset(vuelo.getZonedHoraOrigen(), vuelo.getHoraOrigen(),
                                            vuelo.getHusoHorarioOrigen(), formatter),
                                    formatFechaConOffset(vuelo.getZonedHoraDestino(), vuelo.getHoraDestino(),
                                            vuelo.getHusoHorarioDestino(), formatter),
                                    capacidadOcupada != null ? capacidadOcupada : 0,
                                    capacidadMaxima != null ? capacidadMaxima : 0);
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

    @Deprecated
    private void liberarProductosEntregados(LocalDateTime tiempoSimulado) {
        try {
            LocalDateTime fechaInicio = this.tiempoInicioSimulacion != null ? this.tiempoInicioSimulacion
                    : tiempoSimulado.minusDays(1);
            LocalDateTime fechaFin = tiempoSimulado;

            List<Envio> enviosConPartes;
            try {
                enviosConPartes = new ArrayList<>(envioService.obtenerEnviosEnRangoConPartes(
                        fechaInicio, "0", fechaFin, "0"));
            } catch (Exception e) {
                enviosConPartes = this.enviosOriginales != null ? new ArrayList<>(this.enviosOriginales)
                        : new ArrayList<>();
            }

            List<Envio> envios = enviosConPartes.stream()
                    .filter(e -> {
                        try {
                            return e.getParteAsignadas() != null && !e.getParteAsignadas().isEmpty()
                                    && e.getAeropuertoDestino() != null;
                        } catch (Exception ex) {
                            return false;
                        }
                    })
                    .collect(java.util.stream.Collectors.toList());

            Map<Integer, Aeropuerto> aeropuertosActualizados = new HashMap<>();
            List<ParteAsignada> partesParaActualizar = new ArrayList<>();
            int productosLiberados = 0;
            int partesEntregadas = 0;

            for (Envio envio : envios) {
                Integer aeropuertoDestinoId = envio.getAeropuertoDestino().getId();

                for (ParteAsignada parte : envio.getParteAsignadas()) {
                    if (parte.getEntregado() != null && parte.getEntregado()) {
                        continue;
                    }

                    if (parte.getLlegadaFinal() == null) {
                        continue;
                    }

                    if (parte.getRuta() == null || parte.getRuta().isEmpty()) {
                        parte.cargarRutaDesdeBD();
                    }

                    boolean llegoADestinoFinal = false;
                    if (parte.getRuta() != null && !parte.getRuta().isEmpty()) {
                        PlanDeVuelo ultimoVuelo = parte.getRuta().get(parte.getRuta().size() - 1);
                        if (ultimoVuelo.getCiudadDestino() != null &&
                                ultimoVuelo.getCiudadDestino().equals(aeropuertoDestinoId)) {
                            llegoADestinoFinal = true;
                        }
                    }

                    if (!llegoADestinoFinal) {
                        continue;
                    }

                    ZonedDateTime tiempoSimuladoZoned;
                    try {
                        java.time.ZoneId zonaLlegada = parte.getLlegadaFinal().getZone();
                        tiempoSimuladoZoned = tiempoSimulado.atZone(zonaLlegada);
                    } catch (Exception e) {
                        tiempoSimuladoZoned = tiempoSimulado.atZone(java.time.ZoneOffset.UTC)
                                .withZoneSameInstant(parte.getLlegadaFinal().getZone());
                    }

                    long horasTranscurridas = java.time.Duration.between(
                            parte.getLlegadaFinal().toInstant(), tiempoSimuladoZoned.toInstant()).toHours();

                    if (horasTranscurridas >= 2) {
                        Aeropuerto aeropuertoParaActualizar;
                        if (aeropuertosActualizados.containsKey(aeropuertoDestinoId)) {
                            aeropuertoParaActualizar = aeropuertosActualizados.get(aeropuertoDestinoId);
                        } else {
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

                        aeropuertoParaActualizar.desasignarCapacidad(parte.getCantidad());

                        productosLiberados += parte.getCantidad();
                        parte.setEntregado(true);
                        partesParaActualizar.add(parte);
                        partesEntregadas++;
                    }
                }
            }

            if (!partesParaActualizar.isEmpty()) {
                Map<Integer, Envio> enviosParaActualizar = new HashMap<>();
                Map<Integer, ParteAsignada> partesPorId = new HashMap<>();

                for (ParteAsignada parte : partesParaActualizar) {
                    partesPorId.put(parte.getId(), parte);
                    Integer envioId = parte.getEnvio() != null ? parte.getEnvio().getId() : null;
                    if (envioId == null)
                        continue;

                    if (!enviosParaActualizar.containsKey(envioId)) {
                        Optional<Envio> envioOpt = envioService.obtenerEnvioPorIdConPartesInicializadas(envioId);
                        if (envioOpt.isPresent()) {
                            Envio envio = envioOpt.get();

                            if (envio.getParteAsignadas() != null) {
                                for (ParteAsignada parteEnvio : envio.getParteAsignadas()) {
                                    if (partesPorId.containsKey(parteEnvio.getId())) {
                                        parteEnvio.setEntregado(true);
                                    }
                                }
                            }

                            if (envio.getParteAsignadas() != null && !envio.getParteAsignadas().isEmpty()) {
                                boolean todasEntregadas = envio.getParteAsignadas().stream()
                                        .allMatch(p -> p.getEntregado() != null && p.getEntregado());

                                if (todasEntregadas && envio.getEstado() != Envio.EstadoEnvio.ENTREGADO) {
                                    envio.setEstado(Envio.EstadoEnvio.ENTREGADO);
                                    try {
                                        envioService.insertarEnvio(envio);
                                    } catch (Exception e) {
                                        System.err.printf("❌ Error al persistir cambio de estado ENTREGADO: %s%n",
                                                e.getMessage());
                                    }
                                }
                            }

                            enviosParaActualizar.put(envioId, envio);
                        }
                    }
                }

                for (Envio envio : enviosParaActualizar.values()) {
                    envioService.insertarEnvio(envio);
                }

                if (!aeropuertosActualizados.isEmpty()) {
                    aeropuertoService.insertarListaAeropuertos(
                            new ArrayList<>(aeropuertosActualizados.values()));
                }
            }

        } catch (Exception e) {
            System.err.printf("❌ Error al liberar productos entregados: %s%n", e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Recarga desde la base de datos el estado actual de planes de vuelo y
     * aeropuertos.
     */
    private void recargarDatosBase(LocalDateTime inicioHorizonte, LocalDateTime finHorizonte) {
        LocalDateTime finConsultaVuelos = inicioHorizonte.plusDays(6);

        boolean usarCache = false;
        if (vuelosCacheados != null && cacheVuelosInicio != null && cacheVuelosFin != null) {
            boolean inicioCubierto = !inicioHorizonte.isBefore(cacheVuelosInicio);
            boolean finCubierto = !finConsultaVuelos.isAfter(cacheVuelosFin);
            usarCache = inicioCubierto && finCubierto;

            if (!usarCache) {
                long horasFaltantesFin = java.time.Duration.between(cacheVuelosFin, finConsultaVuelos).toHours();
                System.out.printf("⚠️ [CACHÉ] No usado: inicioCubierto=%s, finCubierto=%s (falta %dh)%n",
                        inicioCubierto, finCubierto, horasFaltantesFin);
            }
        }

        ArrayList<PlanDeVuelo> planesActualizados;

        if (usarCache) {
            System.out.printf("⚡ [recargarDatosBase] USANDO CACHÉ de vuelos (%d vuelos, ahorrando consulta BD)%n",
                    vuelosCacheados.size());
            planesActualizados = vuelosCacheados;
        } else {
            System.out.printf(
                    "📊 [recargarDatosBase] Cargando vuelos desde %s hasta %s (6 días para caché)%n",
                    inicioHorizonte.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                    finConsultaVuelos.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

            planesActualizados = planDeVueloService.obtenerVuelosEnRango(
                    inicioHorizonte, "0", finConsultaVuelos, "0");

            System.out.printf("✅ [recargarDatosBase] Vuelos cargados: %d (en lugar de 2+ millones)%n",
                    planesActualizados.size());

            vuelosCacheados = planesActualizados;
            cacheVuelosInicio = inicioHorizonte;
            cacheVuelosFin = finConsultaVuelos;
        }

        ArrayList<Aeropuerto> aeropuertosActualizados = aeropuertoService.obtenerTodosAeropuertos();

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
     * ⚡ OPTIMIZADO: Persiste los cambios en la base de datos después de ejecutar
     * GRASP
     */
    private void persistirCambios(Solucion solucion) {
        if (solucion == null || solucion.getEnvios() == null || solucion.getEnvios().isEmpty()) {
            return;
        }

        Set<Integer> envioIds = new HashSet<>();
        Set<Integer> vueloIds = new HashSet<>();
        Set<Integer> aeropuertoIds = new HashSet<>();

        for (Envio envioCopia : solucion.getEnvios()) {
            if (envioCopia.getId() == null) {
                continue;
            }
            envioIds.add(envioCopia.getId());

            if (envioCopia.getParteAsignadas() != null) {
                for (ParteAsignada parteCopia : envioCopia.getParteAsignadas()) {
                    if (parteCopia.getAeropuertoOrigen() != null && parteCopia.getAeropuertoOrigen().getId() != null) {
                        aeropuertoIds.add(parteCopia.getAeropuertoOrigen().getId());
                    }

                    if (parteCopia.getRuta() != null) {
                        for (int i = 0; i < parteCopia.getRuta().size(); i++) {
                            PlanDeVuelo vueloCopia = parteCopia.getRuta().get(i);
                            if (vueloCopia.getId() != null) {
                                vueloIds.add(vueloCopia.getId());

                                if (vueloCopia.getCiudadDestino() != null) {
                                    aeropuertoIds.add(vueloCopia.getCiudadDestino());
                                }
                                if (i > 0 && vueloCopia.getCiudadOrigen() != null) {
                                    aeropuertoIds.add(vueloCopia.getCiudadOrigen());
                                }
                            }
                        }
                    }
                }
            }
        }

        System.out.printf("📦 [persistirCambios] Cargando %d envíos, %d vuelos, %d aeropuertos en batch...%n",
                envioIds.size(), vueloIds.size(), aeropuertoIds.size());

        Map<Integer, Envio> enviosMap = new HashMap<>();
        if (!envioIds.isEmpty()) {
            List<Envio> enviosCargados = envioService.obtenerEnviosPorIdsConPartes(new ArrayList<>(envioIds));
            for (Envio envio : enviosCargados) {
                enviosMap.put(envio.getId(), envio);
            }
        }

        Map<Integer, PlanDeVuelo> vuelosMap = new HashMap<>();
        if (!vueloIds.isEmpty()) {
            List<PlanDeVuelo> vuelosCargados = planDeVueloService.obtenerPlanesDeVueloPorIds(new ArrayList<>(vueloIds));
            for (PlanDeVuelo vuelo : vuelosCargados) {
                vuelosMap.put(vuelo.getId(), vuelo);
            }
        }

        Map<Integer, Aeropuerto> aeropuertosMap = new HashMap<>();
        if (!aeropuertoIds.isEmpty()) {
            List<Aeropuerto> aeropuertosCargados = aeropuertoService
                    .obtenerAeropuertosPorIds(new ArrayList<>(aeropuertoIds));
            for (Aeropuerto aeropuerto : aeropuertosCargados) {
                aeropuertosMap.put(aeropuerto.getId(), aeropuerto);
            }
        }

        System.out.printf("✅ [persistirCambios] Cargados: %d envíos, %d vuelos, %d aeropuertos%n",
                enviosMap.size(), vuelosMap.size(), aeropuertosMap.size());

        Set<Integer> planesDeVueloModificados = new HashSet<>();
        Set<Integer> aeropuertosModificados = new HashSet<>();
        List<Envio> enviosParaActualizar = new ArrayList<>();

        for (Envio envioCopia : solucion.getEnvios()) {
            if (envioCopia.getId() == null) {
                continue;
            }

            Envio envioReal = enviosMap.get(envioCopia.getId());
            if (envioReal == null) {
                System.err.printf("⚠️ No se encontró el envío %d en la BD%n", envioCopia.getId());
                continue;
            }

            if (envioCopia.getParteAsignadas() != null) {
                for (ParteAsignada parteCopia : envioCopia.getParteAsignadas()) {
                    boolean parteExiste = false;
                    if (envioReal.getParteAsignadas() != null) {
                        for (ParteAsignada parteExistente : envioReal.getParteAsignadas()) {
                            if (parteExistente.getId() != null && parteExistente.getId().equals(parteCopia.getId())) {
                                parteExiste = true;
                                break;
                            }
                        }
                    }

                    if (parteCopia.getId() == null || !parteExiste) {
                        ParteAsignada nuevaParte = new ParteAsignada();
                        nuevaParte.setEnvio(envioReal);
                        nuevaParte.setCantidad(parteCopia.getCantidad());
                        nuevaParte.setLlegadaFinal(parteCopia.getLlegadaFinal());

                        if (parteCopia.getAeropuertoOrigen() != null
                                && parteCopia.getAeropuertoOrigen().getId() != null) {
                            nuevaParte
                                    .setAeropuertoOrigen(aeropuertosMap.get(parteCopia.getAeropuertoOrigen().getId()));
                        }

                        if (parteCopia.getRuta() != null && !parteCopia.getRuta().isEmpty()) {
                            List<PlanDeVuelo> rutaReal = new ArrayList<>();
                            for (int i = 0; i < parteCopia.getRuta().size(); i++) {
                                PlanDeVuelo vueloCopia = parteCopia.getRuta().get(i);
                                if (vueloCopia.getId() != null) {
                                    PlanDeVuelo vueloReal = vuelosMap.get(vueloCopia.getId());
                                    if (vueloReal != null) {
                                        rutaReal.add(vueloReal);
                                        planesDeVueloModificados.add(vueloCopia.getId());

                                        if (vueloCopia.getCiudadDestino() != null) {
                                            aeropuertosModificados.add(vueloCopia.getCiudadDestino());
                                        }
                                        if (i > 0 && vueloCopia.getCiudadOrigen() != null) {
                                            aeropuertosModificados.add(vueloCopia.getCiudadOrigen());
                                        }
                                    }
                                }
                            }
                            nuevaParte.setRuta(rutaReal);
                            nuevaParte.sincronizarRutaConBD();
                        }

                        if (envioReal.getParteAsignadas() == null) {
                            envioReal.setParteAsignadas(new ArrayList<>());
                        }
                        envioReal.getParteAsignadas().add(nuevaParte);
                    }
                }
            }

            if (envioReal.getParteAsignadas() != null && !envioReal.getParteAsignadas().isEmpty()) {
                if (envioReal.getEstado() == null || envioReal.getEstado() != Envio.EstadoEnvio.PLANIFICADO) {
                    envioReal.setEstado(Envio.EstadoEnvio.PLANIFICADO);
                }
            }

            enviosParaActualizar.add(envioReal);
        }

        List<PlanDeVuelo> planesParaActualizar = new ArrayList<>();
        for (Integer planId : planesDeVueloModificados) {
            PlanDeVuelo planReal = vuelosMap.get(planId);
            if (planReal != null) {
                Integer capacidadActualBD = planReal.getCapacidadOcupada() != null ? planReal.getCapacidadOcupada() : 0;
                Integer capacidadAdicionalCiclo = calcularCapacidadAsignada(planId, solucion.getEnvios());

                if (capacidadAdicionalCiclo != null && capacidadAdicionalCiclo > 0) {
                    Integer nuevaCapacidadOcupada = capacidadActualBD + capacidadAdicionalCiclo;
                    planReal.setCapacidadOcupada(nuevaCapacidadOcupada);
                    planesParaActualizar.add(planReal);
                }
            }
        }

        List<Aeropuerto> aeropuertosParaActualizar = new ArrayList<>();
        for (Integer aeropuertoId : aeropuertosModificados) {
            Aeropuerto aeropuertoReal = aeropuertosMap.get(aeropuertoId);
            if (aeropuertoReal != null) {
                Aeropuerto aeropuertoGrasp = obtenerAeropuertoPorId(aeropuertoId);
                if (aeropuertoGrasp != null && aeropuertoGrasp.getCapacidadOcupada() != null) {
                    aeropuertoReal.setCapacidadOcupada(aeropuertoGrasp.getCapacidadOcupada());
                    aeropuertosParaActualizar.add(aeropuertoReal);
                }
            }
        }

        try {
            if (!enviosParaActualizar.isEmpty()) {
                envioService.insertarListaEnvios(new ArrayList<>(enviosParaActualizar));
            }

            if (!planesParaActualizar.isEmpty()) {
                planDeVueloService.insertarListaPlanesDeVuelo(new ArrayList<>(planesParaActualizar));
            }

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
     */
    private void crearEventosTemporales(Solucion solucion, LocalDateTime tiempoReferencia) {
        long inicioCreacion = System.currentTimeMillis();

        if (solucion == null || solucion.getEnvios() == null) {
            return;
        }

        if (schedulerEventos == null) {
            System.err.println("⚠️ Scheduler de eventos no inicializado, no se pueden programar eventos");
            return;
        }

        final double FACTOR_CONVERSION;
        if (modoSimulacion == ModoSimulacion.OPERACIONES_DIARIAS) {
            FACTOR_CONVERSION = 1.0 / 60.0; // minutos simulados por segundo real (tiempo real)
        } else {
            FACTOR_CONVERSION = 2.0; // minutos simulados por segundo real
        }

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

                int totalVuelos = parte.getRuta().size();
                for (int i = 0; i < totalVuelos; i++) {
                    PlanDeVuelo vuelo = parte.getRuta().get(i);
                    boolean esPrimerVuelo = (i == 0);
                    boolean esUltimoVuelo = (i == totalVuelos - 1);

                    ZonedDateTime llegada = vuelo.getZonedHoraDestino();
                    if (llegada != null) {
                        LocalDateTime llegadaLocal = llegada.toLocalDateTime();

                        long minutosSimulados = Duration.between(tiempoSimuladoActual, llegadaLocal).toMinutes();
                        if (minutosSimulados >= 0) {
                            long delaySegundos = (long) (minutosSimulados / FACTOR_CONVERSION);

                            if (modoSimulacion == ModoSimulacion.OPERACIONES_DIARIAS && contadorEventos < 5) {
                                System.out.printf(
                                        "⏰ [OPERACIONES_DIARIAS] Evento llegada: %d min sim → %d seg real (tiempo real)%n",
                                        minutosSimulados, delaySegundos);
                            }

                            EventoTemporal eventoLlegada = new EventoTemporal(
                                    llegada,
                                    EventoTemporal.TipoEvento.LLEGADA_VUELO,
                                    vuelo,
                                    parte,
                                    cantidad,
                                    vuelo.getCiudadDestino(),
                                    esPrimerVuelo,
                                    esUltimoVuelo,
                                    envio);

                            try {
                                ScheduledFuture<?> futuro = schedulerEventos.schedule(
                                        () -> procesarEvento(eventoLlegada),
                                        delaySegundos,
                                        TimeUnit.SECONDS);

                                eventosProgramados.add(futuro);
                                contadorEventos++;
                            } catch (RejectedExecutionException e) {
                                System.err.printf(
                                        "⚠️ Scheduler de eventos saturado, evento de llegada rechazado (Vuelo %d). Threads activos: %d%n",
                                        vuelo.getId() != null ? vuelo.getId() : -1,
                                        schedulerEventos instanceof ThreadPoolExecutor
                                                ? ((ThreadPoolExecutor) schedulerEventos).getActiveCount()
                                                : -1);
                            }
                        }
                    }

                    ZonedDateTime salida = vuelo.getZonedHoraOrigen();
                    if (salida != null) {
                        LocalDateTime salidaLocal = salida.toLocalDateTime();

                        long minutosSimulados = Duration.between(tiempoSimuladoActual, salidaLocal).toMinutes();
                        if (minutosSimulados >= 0) {
                            long delaySegundos = (long) (minutosSimulados / FACTOR_CONVERSION);

                            if (modoSimulacion == ModoSimulacion.OPERACIONES_DIARIAS && contadorEventos < 5) {
                                System.out.printf(
                                        "⏰ [OPERACIONES_DIARIAS] Evento salida: %d min sim → %d seg real (tiempo real)%n",
                                        minutosSimulados, delaySegundos);
                            }

                            EventoTemporal eventoSalida = new EventoTemporal(
                                    salida,
                                    EventoTemporal.TipoEvento.SALIDA_VUELO,
                                    vuelo,
                                    parte,
                                    cantidad,
                                    vuelo.getCiudadOrigen(),
                                    esPrimerVuelo,
                                    esUltimoVuelo,
                                    envio);

                            try {
                                ScheduledFuture<?> futuro = schedulerEventos.schedule(
                                        () -> procesarEvento(eventoSalida),
                                        delaySegundos,
                                        TimeUnit.SECONDS);

                                eventosProgramados.add(futuro);
                                contadorEventos++;
                            } catch (RejectedExecutionException e) {
                                System.err.printf(
                                        "⚠️ Scheduler de eventos saturado, evento de salida rechazado (Vuelo %d). Threads activos: %d%n",
                                        vuelo.getId() != null ? vuelo.getId() : -1,
                                        schedulerEventos instanceof ThreadPoolExecutor
                                                ? ((ThreadPoolExecutor) schedulerEventos).getActiveCount()
                                                : -1);
                            }
                        }
                    }
                }
            }
        }

        long tiempoCreacion = System.currentTimeMillis() - inicioCreacion;
        System.out.printf("📅 [crearEventosTemporales] Programados %d eventos temporales desde %s (en %d ms)%n",
                contadorEventos, tiempoReferencia.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                tiempoCreacion);
    }

    /**
     * ✅ Limpia eventos temporales que ya se ejecutaron o fueron cancelados.
     */
    private void limpiarEventosEjecutados() {
        if (eventosProgramados == null || eventosProgramados.isEmpty()) {
            return;
        }

        synchronized (eventosProgramados) {
            int antes = eventosProgramados.size();

            List<ScheduledFuture<?>> eventosAMantener = new ArrayList<>();
            for (ScheduledFuture<?> futuro : eventosProgramados) {
                if (futuro != null && !futuro.isDone() && !futuro.isCancelled()) {
                    eventosAMantener.add(futuro);
                }
            }

            eventosProgramados.clear();
            eventosProgramados.addAll(eventosAMantener);

            int despues = eventosProgramados.size();

            if (antes > 1000 || (antes - despues) > 100) {
                System.out.printf(
                        "🧹 [limpiarEventosEjecutados] Limpiados %d eventos ejecutados (quedan %d pendientes)%n",
                        antes - despues, despues);
            }
        }
    }

    @Deprecated
    private void procesarEventosTemporales(LocalDateTime tiempoActual) {
        // Obsoleto: procesar eventos en lotes
    }

    /**
     * Procesa un evento temporal individual (llegada o salida de vuelo)
     */
    private void procesarEvento(EventoTemporal evento) {
        try {
            if (eventosProgramados != null && eventosProgramados.size() > 1000 &&
                    Math.random() < 0.1) {
                limpiarEventosEjecutados();
            }

            if (evento.getTipo() == EventoTemporal.TipoEvento.LLEGADA_VUELO) {
                aeropuertoService.incrementarCapacidadOcupada(evento.getAeropuertoId(), evento.getCantidad());

                PlanDeVuelo vuelo = evento.getVuelo();
                PlanDeVuelo vueloReal = null;
                if (vuelo != null && vuelo.getId() != null) {
                    Optional<PlanDeVuelo> vueloOpt = planDeVueloService.obtenerPlanDeVueloPorId(vuelo.getId());
                    if (vueloOpt.isPresent()) {
                        vueloReal = vueloOpt.get();
                        int capacidadActual = vueloReal.getCapacidadOcupada() != null ? vueloReal.getCapacidadOcupada()
                                : 0;
                        int cantidadFaltante = evento.getCantidad() - capacidadActual;
                        if (cantidadFaltante > 0) {
                            vueloReal.asignar(cantidadFaltante);
                        }
                    }
                }

                Envio envio = evento.getEnvio();
                if (evento.isUltimoVuelo() && envio != null && envio.getId() != null) {
                    try {
                        Optional<Envio> envioOpt = envioService.obtenerEnvioPorIdConPartesInicializadas(envio.getId());
                        if (envioOpt.isPresent()) {
                            Envio envioReal = envioOpt.get();
                            if (envioReal.getEstado() != Envio.EstadoEnvio.FINALIZADO) {
                                envioReal.setEstado(Envio.EstadoEnvio.FINALIZADO);
                                envioService.insertarEnvio(envioReal);
                            }
                        }
                    } catch (Exception e) {
                        System.err.printf("❌ Error al cambiar estado del envío: %s%n", e.getMessage());
                    }

                    boolean llegoADestinoFinal = false;
                    if (envio != null && envio.getAeropuertoDestino() != null && vuelo != null
                            && vuelo.getCiudadDestino() != null) {
                        llegoADestinoFinal = vuelo.getCiudadDestino().equals(envio.getAeropuertoDestino().getId());
                    }

                    if (llegoADestinoFinal && vuelo != null && vuelo.getZonedHoraDestino() != null) {
                        try {
                            Optional<Envio> envioConPartesOpt = envioService
                                    .obtenerEnvioPorIdConPartesInicializadas(envio.getId());
                            ParteAsignada parteParaLiberacion = null;

                            if (envioConPartesOpt.isPresent()) {
                                Envio envioConPartes = envioConPartesOpt.get();
                                if (envioConPartes.getParteAsignadas() != null) {
                                    for (ParteAsignada parteEnvio : envioConPartes.getParteAsignadas()) {
                                        if (parteEnvio.getRuta() != null && !parteEnvio.getRuta().isEmpty()) {
                                            PlanDeVuelo ultimoVueloParte = parteEnvio.getRuta()
                                                    .get(parteEnvio.getRuta().size() - 1);
                                            if (ultimoVueloParte.getId() != null && vuelo.getId() != null
                                                    && ultimoVueloParte.getId().equals(vuelo.getId())) {
                                                parteParaLiberacion = parteEnvio;
                                                break;
                                            }
                                        }
                                    }
                                }
                            }

                            if (parteParaLiberacion == null && evento.getParte() != null
                                    && evento.getParte().getId() != null) {
                                if (envioConPartesOpt.isPresent()) {
                                    Envio envioConPartes = envioConPartesOpt.get();
                                    if (envioConPartes.getParteAsignadas() != null) {
                                        for (ParteAsignada parteEnvio : envioConPartes.getParteAsignadas()) {
                                            if (parteEnvio.getId() != null
                                                    && parteEnvio.getId().equals(evento.getParte().getId())) {
                                                parteParaLiberacion = parteEnvio;
                                                break;
                                            }
                                        }
                                    }
                                }
                            }

                            if (parteParaLiberacion == null) {
                                System.err.printf(
                                        "⚠️ [LiberarProductos] No se pudo encontrar la parte para el envío %d y vuelo %d%n",
                                        envio.getId(), vuelo.getId() != null ? vuelo.getId() : -1);
                            } else {

                                ZonedDateTime tiempoLlegada = vuelo.getZonedHoraDestino();
                                ZonedDateTime tiempoLiberacion = tiempoLlegada.plusHours(1);

                                final double FACTOR_CONVERSION;
                                if (modoSimulacion == ModoSimulacion.OPERACIONES_DIARIAS) {
                                    FACTOR_CONVERSION = 1.0 / 60.0;
                                } else {
                                    FACTOR_CONVERSION = 2.0;
                                }

                                LocalDateTime tiempoReferencia = tiempoLlegada.toLocalDateTime();

                                long minutosSimulados = Duration.between(tiempoReferencia,
                                        tiempoLiberacion.toLocalDateTime()).toMinutes();

                                if (minutosSimulados >= 0) {
                                    long delaySegundos = (long) (minutosSimulados / FACTOR_CONVERSION);

                                    EventoTemporal eventoLiberacion = new EventoTemporal(
                                            tiempoLiberacion,
                                            EventoTemporal.TipoEvento.LIBERAR_PRODUCTOS,
                                            vuelo,
                                            parteParaLiberacion,
                                            evento.getCantidad(),
                                            vuelo.getCiudadDestino(),
                                            false,
                                            false,
                                            envio);

                                    ScheduledFuture<?> futuro = schedulerEventos.schedule(
                                            () -> procesarEvento(eventoLiberacion),
                                            delaySegundos,
                                            TimeUnit.SECONDS);

                                    eventosProgramados.add(futuro);
                                }
                            }
                        } catch (Exception e) {
                            System.err.printf("❌ Error al programar evento de liberación: %s%n", e.getMessage());
                            e.printStackTrace();
                        }
                    }
                }

                try {
                    if (vueloReal != null) {
                        planDeVueloService.insertarPlanDeVuelo(vueloReal);
                    }
                } catch (Exception e) {
                    System.err.printf("❌ Error al persistir cambios del vuelo: %s%n", e.getMessage());
                    e.printStackTrace();
                }

            } else if (evento.getTipo() == EventoTemporal.TipoEvento.SALIDA_VUELO) {
                aeropuertoService.decrementarCapacidadOcupada(evento.getAeropuertoId(), evento.getCantidad());

                Envio envio = evento.getEnvio();
                if (evento.isPrimerVuelo() && envio != null && envio.getId() != null) {
                    try {
                        Optional<Envio> envioOpt = envioService.obtenerEnvioPorIdConPartesInicializadas(envio.getId());
                        if (envioOpt.isPresent()) {
                            Envio envioReal = envioOpt.get();
                            if (envioReal.getEstado() != Envio.EstadoEnvio.EN_RUTA) {
                                envioReal.setEstado(Envio.EstadoEnvio.EN_RUTA);
                                envioService.insertarEnvio(envioReal);
                            }
                        }
                    } catch (Exception e) {
                        System.err.printf("❌ Error al cambiar estado del envío: %s%n", e.getMessage());
                    }
                }

            } else if (evento.getTipo() == EventoTemporal.TipoEvento.LIBERAR_PRODUCTOS) {
                aeropuertoService.decrementarCapacidadOcupada(evento.getAeropuertoId(), evento.getCantidad());

                ParteAsignada parte = evento.getParte();
                Envio envio = evento.getEnvio();
                if (parte != null && parte.getId() != null && envio != null && envio.getId() != null) {
                    try {
                        Optional<Envio> envioOpt = envioService.obtenerEnvioPorIdConPartesInicializadas(envio.getId());
                        if (envioOpt.isPresent()) {
                            Envio envioReal = envioOpt.get();
                            boolean parteEncontrada = false;

                            if (envioReal.getParteAsignadas() != null) {
                                for (ParteAsignada parteEnvio : envioReal.getParteAsignadas()) {
                                    if (parteEnvio.getId() != null && parteEnvio.getId().equals(parte.getId())) {
                                        parteEnvio.setEntregado(true);
                                        parteEncontrada = true;
                                        break;
                                    }
                                }
                            }

                            if (!parteEncontrada) {
                                System.err.printf(
                                        "⚠️ [LiberarProductos] No se encontró la parte ID %d en el envío %d%n",
                                        parte.getId(), envio.getId());
                            }

                            if (envioReal.getParteAsignadas() != null && !envioReal.getParteAsignadas().isEmpty()) {
                                long partesEntregadas = envioReal.getParteAsignadas().stream()
                                        .filter(p -> p.getEntregado() != null && p.getEntregado())
                                        .count();
                                long totalPartes = envioReal.getParteAsignadas().size();

                                boolean todasEntregadas = partesEntregadas == totalPartes;

                                if (todasEntregadas && envioReal.getEstado() != Envio.EstadoEnvio.ENTREGADO) {
                                    envioReal.setEstado(Envio.EstadoEnvio.ENTREGADO);
                                    envioService.insertarEnvio(envioReal);
                                } else if (!todasEntregadas) {
                                    envioService.insertarEnvio(envioReal);
                                } else {
                                    envioService.insertarEnvio(envioReal);
                                }
                            } else {
                                System.err.printf("⚠️ [LiberarProductos] Envío %d no tiene partes asignadas%n",
                                        envio.getId());
                            }
                        } else {
                            System.err.printf("⚠️ [LiberarProductos] No se encontró el envío %d en la BD%n",
                                    envio.getId());
                        }
                    } catch (Exception e) {
                        System.err.printf("❌ Error al procesar liberación de productos: %s%n", e.getMessage());
                        e.printStackTrace();
                    }
                } else {
                    System.err.printf("⚠️ [LiberarProductos] Datos incompletos: parte=%s, envio=%s%n",
                            parte != null && parte.getId() != null ? parte.getId().toString() : "null",
                            envio != null && envio.getId() != null ? envio.getId().toString() : "null");
                }
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
     */
    public Map<String, Object> obtenerResumenUltimaSimulacion() {
        Map<String, Object> resumen = new HashMap<>();

        List<Envio> envios = this.enviosOriginales != null ? this.enviosOriginales : envioService.obtenerEnvios();

        int totalPedidos = envios.size();
        int pedidosCompletados = 0;
        int pedidosParciales = 0;
        int pedidosSinAsignar = 0;
        int totalProductos = 0;
        int productosAsignados = 0;
        int productosSinAsignar;

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

        List<PlanDeVuelo> vuelos = grasp.getPlanesDeVuelo() != null ? grasp.getPlanesDeVuelo()
                : planDeVueloService.obtenerListaPlanesDeVuelo();
        Map<Integer, Integer> productosPorVuelo = new HashMap<>();
        int totalProductosEnVuelos = 0;
        int vuelosUtilizados;

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

        Map<String, Object> infoGeneral = new HashMap<>();
        infoGeneral.put("cicloActual", cicloActual.get());
        infoGeneral.put("modoSimulacion", modoSimulacion != null ? modoSimulacion.name() : "NORMAL");
        infoGeneral.put("fechaInicioSimulacion",
                tiempoInicioSimulacion != null ? tiempoInicioSimulacion.toString() : "N/A");
        infoGeneral.put("ultimoHorizontePlanificado",
                ultimoHorizontePlanificado != null ? ultimoHorizontePlanificado.toString() : "N/A");
        infoGeneral.put("enEjecucion", enEjecucion);
        resumen.put("informacionGeneral", infoGeneral);

        Map<String, Object> statsPedidos = new HashMap<>();
        statsPedidos.put("totalPedidos", totalPedidos);
        statsPedidos.put("pedidosCompletados", pedidosCompletados);
        statsPedidos.put("pedidosParciales", pedidosParciales);
        statsPedidos.put("pedidosSinAsignar", pedidosSinAsignar);
        statsPedidos.put("tasaExito", totalPedidos > 0 ? (pedidosCompletados * 100.0 / totalPedidos) : 0.0);
        resumen.put("estadisticasPedidos", statsPedidos);

        Map<String, Object> statsPorEstado = new HashMap<>();
        statsPorEstado.put("enviosPlanificados", enviosPlanificados);
        statsPorEstado.put("enviosEnRuta", enviosEnRuta);
        statsPorEstado.put("enviosFinalizados", enviosFinalizados);
        statsPorEstado.put("enviosEntregados", enviosEntregados);
        statsPorEstado.put("enviosSinEstado", enviosSinEstado);
        resumen.put("estadisticasPorEstado", statsPorEstado);

        Map<String, Object> statsProductos = new HashMap<>();
        statsProductos.put("totalProductos", totalProductos);
        statsProductos.put("productosAsignados", productosAsignados);
        statsProductos.put("productosSinAsignar", productosSinAsignar);
        statsProductos.put("tasaAsignacion", totalProductos > 0 ? (productosAsignados * 100.0 / totalProductos) : 0.0);
        resumen.put("estadisticasProductos", statsProductos);

        Map<String, Object> statsVuelos = new HashMap<>();
        statsVuelos.put("totalVuelosDisponibles", vuelos.size());
        statsVuelos.put("vuelosUtilizados", vuelosUtilizados);
        statsVuelos.put("totalProductosEnVuelos", totalProductosEnVuelos);
        resumen.put("estadisticasVuelos", statsVuelos);

        resumen.put("pedidosSinCompletar", pedidosSinCompletar);
        resumen.put("productosPorVuelo", resumenVuelos);
        resumen.put("productosPorAeropuerto", productosPorAeropuerto);
        resumen.put("timestamp", LocalDateTime.now().toString());

        return resumen;
    }
}
