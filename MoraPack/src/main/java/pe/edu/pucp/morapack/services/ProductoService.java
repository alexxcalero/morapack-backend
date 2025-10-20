package pe.edu.pucp.morapack.services;

import pe.edu.pucp.morapack.models.Producto;

import java.util.ArrayList;
import java.util.Optional;

public interface ProductoService {
    Producto insertarPaquete(Producto producto);
    ArrayList<Producto> insertarListaProductos(ArrayList<Producto> productos);
    ArrayList<Producto> obtenerTodosProductos();
    Optional<Producto> obtenerProductosPorId(Integer id);
}
