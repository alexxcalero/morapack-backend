package pe.edu.pucp.morapack.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import pe.edu.pucp.morapack.model.Vuelo;
import pe.edu.pucp.morapack.model.Aeropuerto;
import pe.edu.pucp.morapack.model.TipoRuta;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface VueloRepository extends JpaRepository<Vuelo, Long> {

    List<Vuelo> findByAeropuertoOrigenAndAeropuertoDestino(
            Aeropuerto origen,
            Aeropuerto destino
    );

    List<Vuelo> findByTipoRuta(TipoRuta tipoRuta);

    @Query("""
        SELECT v
        FROM Vuelo v
        WHERE v.horaSalida BETWEEN :inicio AND :finVentana
        """)
    List<Vuelo> findVuelosEnVentana(LocalDateTime inicio,
                                    LocalDateTime finVentana);
}
