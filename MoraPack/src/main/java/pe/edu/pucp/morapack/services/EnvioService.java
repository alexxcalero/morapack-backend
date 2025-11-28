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
         * ⚡ OPTIMIZADO: Obtiene solo los CONTEOS de pedidos por estado.
         * NO carga los 43K+ envíos, solo ejecuta queries COUNT.
         * Ideal para el endpoint /estado que se llama frecuentemente.
         */
        Map<String, Object> obtenerConteosPedidosRapido();
}