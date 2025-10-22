package pe.edu.pucp.morapack.services;

import pe.edu.pucp.morapack.models.Continente;

import java.util.ArrayList;
import java.util.Optional;

public interface ContinenteService {
    Continente insertarContinente(Continente continente);
    Optional<Continente> obtenerAeropuertoPorNombre(String nombre);
    ArrayList<Continente> obtenerTodosContinentes();
}
