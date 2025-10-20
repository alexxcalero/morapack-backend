package pe.edu.pucp.morapack.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pe.edu.pucp.morapack.models.Producto;

@Repository
public interface ProductoRepository extends JpaRepository<Producto, Integer> {
}
