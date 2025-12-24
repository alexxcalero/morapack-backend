package pe.edu.pucp.morapack.services;

import org.springframework.stereotype.Service;
import pe.edu.pucp.morapack.models.*;
import pe.edu.pucp.morapack.repository.ParteAsignadaPlanDeVueloRepository;
import pe.edu.pucp.morapack.services.EnvioService;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Servicio para programar la liberación de capacidad de aeropuertos
 * cuando los pedidos llegan a su destino final.
 */
@Service
public class LiberacionCapacidadService {

    private final ParteAsignadaPlanDeVueloRepository parteAsignadaPlanDeVueloRepository;
    private final AeropuertoService aeropuertoService;
    private final EnvioService envioService;
    private Planificador planificador;

    public LiberacionCapacidadService(
            ParteAsignadaPlanDeVueloRepository parteAsignadaPlanDeVueloRepository,
            AeropuertoService aeropuertoService,
            EnvioService envioService) {
        this.parteAsignadaPlanDeVueloRepository = parteAsignadaPlanDeVueloRepository;
        this.aeropuertoService = aeropuertoService;
        this.envioService = envioService;
    }

    /**
     * Establece la referencia al planificador (debe llamarse desde PlanificadorController)
     */
    public void setPlanificador(Planificador planificador) {
        this.planificador = planificador;
    }

    /**
     * Verifica qué pedidos llegaron a su destino final en un vuelo y programa
     * la liberación de capacidad después de 2 horas simuladas.
     *
     * @param vueloId ID del vuelo que aterrizó
     * @param aeropuertoDestinoId ID del aeropuerto donde aterrizó el vuelo
     */
    public void verificarYProgramarLiberacion(Integer vueloId, Integer aeropuertoDestinoId) {
        if (planificador == null) {
            System.err.println("⚠️ Planificador no inicializado, no se puede programar liberación");
            return;
        }

        if (vueloId == null || aeropuertoDestinoId == null) {
            return;
        }

        try {
            // Buscar todas las partes asignadas que usan este vuelo
            List<ParteAsignadaPlanDeVuelo> relaciones = parteAsignadaPlanDeVueloRepository.findAll();

            for (ParteAsignadaPlanDeVuelo relacion : relaciones) {
                if (relacion.getPlanDeVuelo() == null ||
                    relacion.getPlanDeVuelo().getId() == null ||
                    !relacion.getPlanDeVuelo().getId().equals(vueloId)) {
                    continue;
                }

                ParteAsignada parte = relacion.getParteAsignada();
                if (parte == null || parte.getEnvio() == null) {
                    continue;
                }

                // Cargar la ruta desde BD si es necesario
                // Manejar excepciones por si hay problemas con el ordenamiento
                try {
                    parte.cargarRutaDesdeBD();
                } catch (Exception e) {
                    System.err.printf("⚠️ Error al cargar ruta desde BD para parte %d: %s%n",
                        parte.getId() != null ? parte.getId() : -1, e.getMessage());
                    continue; // Saltar esta parte y continuar con la siguiente
                }

                // Verificar si este vuelo es el último de la ruta (llegada a destino final)
                List<PlanDeVuelo> ruta = parte.getRuta();
                if (ruta == null || ruta.isEmpty()) {
                    continue;
                }

                PlanDeVuelo ultimoVuelo = ruta.get(ruta.size() - 1);
                if (ultimoVuelo.getId() == null || !ultimoVuelo.getId().equals(vueloId)) {
                    // Este vuelo no es el último, el pedido está en escala
                    continue;
                }

                // Verificar que el aeropuerto destino del vuelo coincida con el destino final del envío
                Envio envio = parte.getEnvio();
                if (envio.getAeropuertoDestino() == null ||
                    envio.getAeropuertoDestino().getId() == null ||
                    !envio.getAeropuertoDestino().getId().equals(aeropuertoDestinoId)) {
                    // No llegó a su destino final
                    continue;
                }

                // ✅ Este pedido llegó a su destino final
                // Programar liberación de capacidad después de 2 horas simuladas
                Integer cantidad = parte.getCantidad();
                if (cantidad == null || cantidad <= 0) {
                    continue;
                }

                programarLiberacionCapacidad(aeropuertoDestinoId, cantidad, parte.getId());
            }
        } catch (Exception e) {
            System.err.printf("❌ Error al verificar y programar liberación para vuelo %d: %s%n",
                vueloId, e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Programa la liberación de capacidad después de 2 horas simuladas.
     */
    private void programarLiberacionCapacidad(Integer aeropuertoId, Integer cantidad, Integer parteId) {
        ScheduledExecutorService schedulerEventos = planificador.getSchedulerEventos();
        if (schedulerEventos == null) {
            System.err.println("⚠️ Scheduler de eventos no disponible");
            return;
        }

        // Obtener el modo de simulación para calcular el factor de conversión
        Planificador.ModoSimulacion modoSimulacion = planificador.getModoSimulacion();

        // Calcular el delay en segundos reales para 2 horas simuladas
        long delaySegundos;
        if (modoSimulacion == Planificador.ModoSimulacion.OPERACIONES_DIARIAS) {
            // Tiempo real: 2 horas = 7200 segundos
            delaySegundos = 2 * 60 * 60;
        } else {
            // Simulación semanal: delay de 40 segundos reales para 2 horas simuladas
            delaySegundos = 30;

            /* Código anterior con cálculo de factor de conversión (comentado):
            // Simulación semanal: usar factor de conversión similar al usado en crearEventosTemporales
            // En crearEventosTemporales: FACTOR_CONVERSION = 2.0 (minutos simulados por segundo real)
            // Esto significa: 1 segundo real = 2 minutos simulados = 120 segundos simulados
            // Para 2 horas simuladas = 120 minutos simulados = 7200 segundos simulados
            // delay = 7200 / 120 = 60 segundos reales
            // Pero el usuario dice: "si yo planifico en 2 minutos, 4 horas. entendería que en un minuto pasan 2 horas"
            // Esto significa: 1 minuto real = 2 horas simuladas = 120 minutos simulados
            // Factor: 120 minutos simulados / 1 minuto real = 120 minutos simulados por minuto real
            // = 2 minutos simulados por segundo real (120/60 = 2)
            // Para 2 horas simuladas (120 minutos): delay = 120 / 2 = 60 segundos reales
            final double FACTOR_CONVERSION = 2.0; // minutos simulados por segundo real
            long minutosSimulados = 2 * 60; // 2 horas = 120 minutos simulados
            delaySegundos = (long) (minutosSimulados / FACTOR_CONVERSION); // 120 / 2 = 60 segundos
            */
        }

        // Programar la tarea
        schedulerEventos.schedule(() -> {
            try {
                boolean exito = aeropuertoService.disminuirCapacidadOcupada(aeropuertoId, cantidad);
                if (exito) {
                    System.out.printf("✅ Capacidad liberada en aeropuerto %d: -%d unidades (parte %d) después de 2 horas simuladas%n",
                        aeropuertoId, cantidad, parteId);

                    // ⚡ Actualizar estado del envío a ENTREGADO
                    envioService.actualizarEstadoAEntregado(parteId);
                } else {
                    System.err.printf("❌ No se pudo liberar capacidad en aeropuerto %d para parte %d%n",
                        aeropuertoId, parteId);
                }
            } catch (Exception e) {
                System.err.printf("❌ Error al liberar capacidad en aeropuerto %d: %s%n",
                    aeropuertoId, e.getMessage());
            }
        }, delaySegundos, TimeUnit.SECONDS);

        System.out.printf("📅 Programada liberación de capacidad en aeropuerto %d: -%d unidades después de %d segundos (2 horas simuladas)%n",
            aeropuertoId, cantidad, delaySegundos);
    }
}
