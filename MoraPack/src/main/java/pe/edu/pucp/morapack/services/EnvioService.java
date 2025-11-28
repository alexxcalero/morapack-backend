package pe.edu.pucp.morapack.services;

import pe.edu.pucp.morapack.models.Envio;
import pe.edu.pucp.morapack.models.Pais;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

    ArrayList<Envio> obtenerEnviosEnRango(LocalDateTime fechaInicio, String husoHorarioInicio, LocalDateTime fechaFin,
            String husoHorarioFin);

    ArrayList<Envio> obtenerEnviosDesdeFecha(LocalDateTime fechaInicio, String husoHorarioInicio);

    // ⚡ Métodos optimizados sin cargar ParteAsignadas para inicialización
    ArrayList<Envio> obtenerEnviosSinPartesEnRango(LocalDateTime fechaInicio, String husoHorarioInicio,
            LocalDateTime fechaFin, String husoHorarioFin);

    ArrayList<Envio> obtenerEnviosSinPartesDesdeFecha(LocalDateTime fechaInicio, String husoHorarioInicio);
}
