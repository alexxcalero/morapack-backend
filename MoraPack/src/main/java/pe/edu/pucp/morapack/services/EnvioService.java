package pe.edu.pucp.morapack.services;

import pe.edu.pucp.morapack.models.Envio;
import pe.edu.pucp.morapack.models.Pais;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;

public interface EnvioService {

    Envio insertarEnvio(Envio envio);
    ArrayList<Envio> insertarListaEnvios(ArrayList<Envio> envios);
    ArrayList<Envio> obtenerEnvios();
    Optional<Envio> obtenerEnvioPorId(Integer id);
    ArrayList<Envio> obtenerEnviosPorFecha(LocalDate fecha);
    ArrayList<Envio> obtenerEnviosPorFecha(LocalDateTime fechaInicio, String husoHorario, LocalDateTime fechaFin);
    Integer calcularTotalProductosEnvio(ArrayList<Envio> envios);
    Integer tipoVuelo(Integer ciudadOrigen, Integer ciudadDestino, ArrayList<Pais> paises);
}
