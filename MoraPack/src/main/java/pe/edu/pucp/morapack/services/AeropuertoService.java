package pe.edu.pucp.morapack.services;

import pe.edu.pucp.morapack.models.Aeropuerto;

import java.util.ArrayList;
import java.util.Optional;

public interface AeropuertoService {
    Aeropuerto insertarAeropuerto(Aeropuerto aeropuerto);
    ArrayList<Aeropuerto> insertarListaAeropuertos(ArrayList<Aeropuerto> aeropuertos);
    Optional<Aeropuerto> obtenerAeropuertoPorId(Integer id);
    Optional<Aeropuerto> obtenerAeropuertoPorCodigo(String codigo);
    ArrayList<Aeropuerto> obtenerTodosAeropuertos();
}
