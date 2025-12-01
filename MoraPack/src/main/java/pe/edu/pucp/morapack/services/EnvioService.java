package pe.edu.pucp.morapack.services;

import pe.edu.pucp.morapack.models.Envio;
import pe.edu.pucp.morapack.models.Pais;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface EnvioService {
        Envio insertarEnvio(Envio envio);

        ArrayList<Envio> insertarListaEnvios(ArrayList<Envio> envios);

        ArrayList<Envio> obtenerEnvios();

        Optional<Envio> obtenerEnvioPorId(Integer id);

        ArrayList<Envio> obtenerEnviosPorFecha(LocalDate fecha);

        Integer calcularTotalProductosEnvio(ArrayList<Envio> envios);

        ArrayList<Envio> obtenerEnviosPorAeropuertoOrigen(Integer idAeropuerto);

        ArrayList<Envio> obtenerEnviosPorAeropuertoDestino(Integer idAeropuerto);

        ArrayList<Envio> obtenerEnviosFisicamenteEnAeropuerto(Integer idAeropuerto);

        String determinarEstadoPedido(Envio envio);

        Map<String, Object> obtenerPedidosConEstado();

        ArrayList<Envio> obtenerEnviosEnRango(LocalDateTime fechaInicio, String husoHorarioInicio,
                        LocalDateTime fechaFin,
                        String husoHorarioFin);

        ArrayList<Envio> obtenerEnviosDesdeFecha(LocalDateTime fechaInicio, String husoHorarioInicio);

        // Nuevos métodos para el planificador que necesitan parteAsignadas cargadas
        ArrayList<Envio> obtenerEnviosEnRangoConPartes(LocalDateTime fechaInicio, String husoHorarioInicio,
                        LocalDateTime fechaFin, String husoHorarioFin);

        ArrayList<Envio> obtenerEnviosDesdeFechaConPartes(LocalDateTime fechaInicio, String husoHorarioInicio);

        /**
         * ⚡ OPTIMIZADO: Obtiene solo envíos que tienen partes asignadas.
         * Ideal para el catálogo de envíos pendientes en el frontend.
         */
        List<Envio> obtenerEnviosConPartesAsignadas();

        /**
         * ⚡ OPTIMIZADO CON LÍMITE: Obtiene solo envíos que tienen partes asignadas
         * con un límite máximo para evitar OOM.
         */
        List<Envio> obtenerEnviosConPartesAsignadasLimitado(int limite);

        /**
         * ⚡ PARA RUTAS: Obtiene envíos con partes Y sus vuelos de ruta cargados.
         * Este método es más pesado pero necesario para mostrar aviones con envíos.
         */
        List<Envio> obtenerEnviosConPartesYVuelosLimitado(int limite);

        /**
         * ⚡ OPTIMIZADO: Cuenta envíos por estado directamente en la base de datos.
         * Esto evita cargar todos los envíos en memoria.
         */
        long contarEnviosPorEstado(Envio.EstadoEnvio estado);
}
