package pe.edu.pucp.morapack.services.servicesImp;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pe.edu.pucp.morapack.models.Producto;
import pe.edu.pucp.morapack.repositories.ProductoRepository;
import pe.edu.pucp.morapack.services.ProductoService;

import java.util.ArrayList;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ProductoServiceImp implements ProductoService {
    private final ProductoRepository productoRepository;

    @Override
    public Producto insertarPaquete(Producto producto) {
        return productoRepository.save(producto);
    }

    @Override
    public ArrayList<Producto> insertarListaProductos(ArrayList<Producto> productos) {
        return (ArrayList<Producto>)productoRepository.saveAll(productos);
    }

    @Override
    public ArrayList<Producto> obtenerTodosProductos() {
        return (ArrayList<Producto>)productoRepository.findAll();
    }

    @Override
    public Optional<Producto> obtenerProductosPorId(Integer id) {
        return productoRepository.findById(id);
    }
}
