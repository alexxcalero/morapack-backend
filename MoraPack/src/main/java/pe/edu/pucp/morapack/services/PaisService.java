package pe.edu.pucp.morapack.services;

import pe.edu.pucp.morapack.models.Pais;

import java.util.ArrayList;

public interface PaisService {
    Pais insertarPais(Pais pais);
    ArrayList<Pais> obtenerTodosPaises();
}
