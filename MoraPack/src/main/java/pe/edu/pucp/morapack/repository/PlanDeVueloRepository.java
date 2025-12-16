package pe.edu.pucp.morapack.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pe.edu.pucp.morapack.dtos.PlanDeVueloResponse;
import pe.edu.pucp.morapack.models.PlanDeVuelo;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
public interface PlanDeVueloRepository extends JpaRepository<PlanDeVuelo, Integer> {
        @Query("SELECT new pe.edu.pucp.morapack.dtos.PlanDeVueloResponse(" +
                        "p.id, " +
                        "p.ciudadOrigen, " +
                        "CONCAT(CAST(FUNCTION('DATE_FORMAT', p.horaOrigen, '%Y-%m-%d %H:%i:%s') AS string),'Z',ao.husoHorario), "
                        +
                        "ao.longitud, ao.latitud, " +
                        "p.ciudadDestino, " +
                        "CONCAT(CAST(FUNCTION('DATE_FORMAT', p.horaDestino, '%Y-%m-%d %H:%i:%s') AS string),'Z',ad.husoHorario), "
                        +
                        "ad.longitud, ad.latitud, " +
                        "p.capacidadMaxima, " +
                        // "p.capacidadOcupada, " +
                        "p.estado) " +
                        "FROM PlanDeVuelo p " +
                        "JOIN Aeropuerto ao ON p.ciudadOrigen = ao.id " +
                        "JOIN Aeropuerto ad ON p.ciudadDestino = ad.id")
        ArrayList<PlanDeVueloResponse> queryPlanDeVueloWithAeropuerto();

        /**
         * Obtiene vuelos cuya hora de origen está dentro del rango especificado.
         * Esta consulta es optimizada para trabajar con índices en horaOrigen.
         */
        @Query("SELECT p FROM PlanDeVuelo p WHERE p.horaOrigen >= :fechaInicio AND p.horaOrigen <= :fechaFin")
        ArrayList<PlanDeVuelo> findByHoraOrigenBetween(
                        @Param("fechaInicio") LocalDateTime fechaInicio,
                        @Param("fechaFin") LocalDateTime fechaFin);

        /**
         * Obtiene vuelos cuya hora de origen es igual o posterior a la fecha
         * especificada.
         * Esta consulta es optimizada para trabajar con índices en horaOrigen.
         */
        @Query("SELECT p FROM PlanDeVuelo p WHERE p.horaOrigen >= :fechaInicio")
        ArrayList<PlanDeVuelo> findByHoraOrigenGreaterThanEqual(@Param("fechaInicio") LocalDateTime fechaInicio);

        // Muestra de 100 vuelos más recientes (por hora de salida)
        List<PlanDeVuelo> findTop100ByOrderByHoraOrigenDesc();

        List<PlanDeVuelo> findTop100ByHoraOrigenGreaterThanEqualOrderByHoraOrigenAsc(LocalDateTime fechaInicio);

        @Query("SELECT p FROM PlanDeVuelo p WHERE p.horaOrigen >= :fechaInicio ORDER BY p.horaOrigen ASC")
        List<PlanDeVuelo> findProximosDesde(@Param("fechaInicio") LocalDateTime fechaInicio, Pageable pageable);

}
