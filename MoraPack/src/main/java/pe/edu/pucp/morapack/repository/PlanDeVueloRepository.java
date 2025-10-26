package pe.edu.pucp.morapack.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pe.edu.pucp.morapack.dtos.PlanDeVueloResponse;
import pe.edu.pucp.morapack.models.PlanDeVuelo;

import java.time.LocalDateTime;
import java.util.ArrayList;

@Repository
public interface PlanDeVueloRepository extends JpaRepository<PlanDeVuelo, Integer> {
    @Query("SELECT new pe.edu.pucp.morapack.dtos.PlanDeVueloResponse(" +
            "p.id, " +
            "p.ciudadOrigen, " +
            "CONCAT(CAST(FUNCTION('DATE_FORMAT', p.horaOrigen, '%Y-%m-%d %H:%i:%s') AS string),'Z',ao.husoHorario), " +
            "ao.longitud, ao.latitud, " +
            "p.ciudadDestino, " +
            "CONCAT(CAST(FUNCTION('DATE_FORMAT', p.horaDestino, '%Y-%m-%d %H:%i:%s') AS string),'Z',ad.husoHorario), " +
            "ad.longitud, ad.latitud, " +
            "p.capacidadMaxima, " +
            // "p.capacidadOcupada, " +
            "p.estado) " +
            "FROM PlanDeVuelo p " +
            "JOIN Aeropuerto ao ON p.ciudadOrigen = ao.id " +
            "JOIN Aeropuerto ad ON p.ciudadDestino = ad.id")
    ArrayList<PlanDeVueloResponse> queryPlanDeVueloWithAeropuerto();
}
