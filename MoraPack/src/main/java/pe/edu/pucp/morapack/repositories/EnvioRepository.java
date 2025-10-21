package pe.edu.pucp.morapack.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pe.edu.pucp.morapack.models.Envio;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;

@Repository
public interface EnvioRepository extends JpaRepository<Envio, Integer> {
    @Query("SELECT e FROM Envio e WHERE FUNCTION('DATE', e.fechaIngreso) = :fecha")
    ArrayList<Envio> findByFechaIngreso(LocalDate fecha);

    @Query("SELECT e FROM Envio e LEFT JOIN FETCH e.productos " +
            "WHERE e.fechaIngreso BETWEEN " +
            "FUNCTION('CONVERT_TZ', :fechaInicio, :husoHorarioInicio, e.husoHorarioOrigen) " +
            "AND " +
            "FUNCTION('CONVERT_TZ', :fechaFin, :husoHorarioInicio, e.husoHorarioOrigen)")
    ArrayList<Envio> findByFechaIngresoInRange(@Param("fechaInicio") LocalDateTime fechaInicio,
                                               @Param("husoHorarioInicio") String husoHorarioInicio,
                                               @Param("fechaFin") LocalDateTime fechaFin);
}
