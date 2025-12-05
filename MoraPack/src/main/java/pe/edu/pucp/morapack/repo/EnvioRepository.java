// src/main/java/pe/edu/pucp/morapack/repo/EnvioRepository.java
package pe.edu.pucp.morapack.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pe.edu.pucp.morapack.model.Envio;
import pe.edu.pucp.morapack.model.EstadoEnvio;

import java.time.LocalDateTime;
import java.util.List;

public interface EnvioRepository extends JpaRepository<Envio, Long> {

    // 🔹 1) Método derivado que usa SimulacionDataLoader
    List<Envio> findByFechaCreacionBetween(LocalDateTime inicio, LocalDateTime fin);

    // 🔹 2) Método interno para el planificador GRASP (con enums parametrizados)
    @Query("""
        SELECT e
        FROM Envio e
        WHERE e.estado NOT IN (:estadoFinal, :estadoEntregado)
          AND e.fechaCreacion <= :finVentana
          AND e.fechaLimiteEntrega >= :inicio
        ORDER BY e.fechaLimiteEntrega ASC
    """)
    List<Envio> findReprogramablesEnVentanaInternal(
            @Param("inicio") LocalDateTime inicio,
            @Param("finVentana") LocalDateTime finVentana,
            @Param("estadoFinal") EstadoEnvio estadoFinal,
            @Param("estadoEntregado") EstadoEnvio estadoEntregado
    );

    // 🔹 3) Wrapper cómodo que es el que usa PlanificadorGraspService
    default List<Envio> findReprogramablesEnVentana(LocalDateTime inicio,
                                                    LocalDateTime finVentana) {
        return findReprogramablesEnVentanaInternal(
                inicio,
                finVentana,
                EstadoEnvio.EN_DESTINO_FINAL,
                EstadoEnvio.ENTREGADO
        );
    }
}
