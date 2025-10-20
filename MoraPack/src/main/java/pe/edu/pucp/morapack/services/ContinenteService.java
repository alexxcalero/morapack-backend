package pe.edu.pucp.morapack.services;

import pe.edu.pucp.morapack.models.Continente;

import java.util.ArrayList;

public interface ContinenteService {
    Continente insertarContinente(Continente continente);
    ArrayList<Continente> obtenerTodosContinentes();
}
