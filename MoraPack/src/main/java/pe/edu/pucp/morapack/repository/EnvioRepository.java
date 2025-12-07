package pe.edu.pucp.morapack.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pe.edu.pucp.morapack.models.Envio;
import pe.edu.pucp.morapack.models.ParteAsignada;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
public interface EnvioRepository extends JpaRepository<Envio, Integer> {
        @Query("SELECT e FROM Envio e WHERE FUNCTION('DATE', e.fechaIngreso) = :fecha")
        ArrayList<Envio> findByFechaIngreso(LocalDate fecha);

        @Query("SELECT e FROM Envio e WHERE e.aeropuertoOrigen.id = :idAeropuerto")
        ArrayList<Envio> findByAeropuertoOrigen(@Param("idAeropuerto") Integer idAeropuerto);

        @Query("SELECT e FROM Envio e WHERE e.aeropuertoDestino.id = :idAeropuerto")
        ArrayList<Envio> findByAeropuertoDestino(@Param("idAeropuerto") Integer idAeropuerto);

        /**
         * Obtiene envíos cuya fecha de ingreso está dentro del rango especificado.
         * Esta consulta es optimizada para trabajar con índices en fechaIngreso.
         */
        @Query("SELECT e FROM Envio e WHERE e.fechaIngreso >= :fechaInicio AND e.fechaIngreso <= :fechaFin")
        ArrayList<Envio> findByFechaIngresoBetween(
                        @Param("fechaInicio") LocalDateTime fechaInicio,
                        @Param("fechaFin") LocalDateTime fechaFin);

        /**
         * Obtiene envíos cuya fecha de ingreso es igual o posterior a la fecha
         * especificada.
         * Esta consulta es optimizada para trabajar con índices en fechaIngreso.
         */
        @Query("SELECT e FROM Envio e WHERE e.fechaIngreso >= :fechaInicio")
        ArrayList<Envio> findByFechaIngresoGreaterThanEqual(@Param("fechaInicio") LocalDateTime fechaInicio);

        /**
         * Obtiene envíos CON sus parteAsignadas para inicialización del planificador.
         * Usa JOIN FETCH para cargar relaciones en una sola query.
         * ⚠️ Solo usar cuando realmente se necesiten las partes asignadas.
         */
        @Query("SELECT DISTINCT e FROM Envio e LEFT JOIN FETCH e.parteAsignadas WHERE e.fechaIngreso >= :fechaInicio AND e.fechaIngreso <= :fechaFin")
        ArrayList<Envio> findByFechaIngresoBetweenWithPartes(
                        @Param("fechaInicio") LocalDateTime fechaInicio,
                        @Param("fechaFin") LocalDateTime fechaFin);

        /**
         * Obtiene envíos CON sus parteAsignadas desde una fecha específica.
         * Usa JOIN FETCH para cargar relaciones en una sola query.
         * ⚠️ Solo usar cuando realmente se necesiten las partes asignadas.
         */
        @Query("SELECT DISTINCT e FROM Envio e LEFT JOIN FETCH e.parteAsignadas WHERE e.fechaIngreso >= :fechaInicio")
        ArrayList<Envio> findByFechaIngresoGreaterThanEqualWithPartes(@Param("fechaInicio") LocalDateTime fechaInicio);

        /**
         * ⚡ OPTIMIZADO: Obtiene SOLO envíos que tienen partes asignadas (pendientes de
         * entrega).
         * Usa INNER JOIN FETCH para traer solo envíos con partes.
         * ⚠️ NOTA: No se puede hacer JOIN FETCH de múltiples bags (List) en una query.
         * Los vuelosRuta se cargarán en una segunda query.
         */
        @Query("SELECT DISTINCT e FROM Envio e " +
                        "INNER JOIN FETCH e.parteAsignadas pa " +
                        "LEFT JOIN FETCH pa.aeropuertoOrigen " +
                        "LEFT JOIN FETCH e.aeropuertoDestino")
        ArrayList<Envio> findEnviosConPartesAsignadas();

        /**
         * ⚡ OPTIMIZADO CON LÍMITE: Obtiene SOLO envíos que tienen partes asignadas
         * con partes NO entregadas, limitado a N registros para evitar OOM.
         * ⚠️ ORDENADO por fecha_ingreso DESC para obtener los más recientes primero.
         */
        @Query(value = "SELECT DISTINCT e.* FROM envio e " +
                        "INNER JOIN parte_asignada pa ON pa.id_envio = e.id " +
                        "WHERE pa.entregado IS NULL OR pa.entregado = false " +
                        "ORDER BY e.fecha_ingreso DESC " +
                        "LIMIT :limite", nativeQuery = true)
        List<Envio> findEnviosConPartesAsignadasLimitado(@Param("limite") int limite);

        /**
         * ⚡ VERSIÓN LIGERA: Carga partes SIN los vuelos de la ruta.
         * Ideal para el catálogo del frontend donde no necesitamos los vuelos.
         */
        @Query("SELECT DISTINCT pa FROM ParteAsignada pa " +
                        "LEFT JOIN FETCH pa.aeropuertoOrigen " +
                        "WHERE pa.envio.id IN :envioIds AND (pa.entregado IS NULL OR pa.entregado = false)")
        List<ParteAsignada> findPartesBasicasByEnvioIds(@Param("envioIds") List<Integer> envioIds);

        /**
         * Segunda query para cargar vuelosRuta de las partes asignadas.
         * Se usa después de findEnviosConPartesAsignadas para evitar
         * MultipleBagFetchException.
         * ⚠️ CUIDADO: Esta query puede cargar MUCHOS datos. Usar solo cuando sea
         * necesario.
         */
        @Query("SELECT DISTINCT pa FROM ParteAsignada pa " +
                        "LEFT JOIN FETCH pa.vuelosRuta vr " +
                        "LEFT JOIN FETCH vr.planDeVuelo " +
                        "WHERE pa.envio.id IN :envioIds")
        ArrayList<ParteAsignada> findPartesConVuelosByEnvioIds(@Param("envioIds") List<Integer> envioIds);

        /**
         * ⚡ OPTIMIZADO: Cuenta envíos por estado directamente en la base de datos.
         * Esto evita cargar todos los envíos en memoria.
         */
        @Query("SELECT COUNT(e) FROM Envio e WHERE e.estado = :estado")
        long countByEstado(@Param("estado") Envio.EstadoEnvio estado);

        /**
         * ⚡ OPTIMIZADO: Cuenta envíos sin estado (null).
         */
        @Query("SELECT COUNT(e) FROM Envio e WHERE e.estado IS NULL")
        long countByEstadoIsNull();

        /**
         * 🔍 Busca envíos por ID exacto, con límite de resultados.
         * Incluye envíos con partes asignadas para poder cargar sus rutas.
         */
        @Query(value = "SELECT DISTINCT e.* FROM envio e " +
                        "LEFT JOIN parte_asignada pa ON pa.id_envio = e.id " +
                        "WHERE e.id = :id " +
                        "LIMIT :limite", nativeQuery = true)
        List<Envio> buscarPorIdExacto(@Param("id") Integer id, @Param("limite") int limite);

        /**
         * 🔍 Busca envíos cuyo ID contiene el patrón dado (búsqueda parcial).
         * Útil para encontrar envíos cuando solo se conoce parte del ID.
         */
        @Query(value = "SELECT DISTINCT e.* FROM envio e " +
                        "LEFT JOIN parte_asignada pa ON pa.id_envio = e.id " +
                        "WHERE CAST(e.id AS CHAR) LIKE :patron " +
                        "LIMIT :limite", nativeQuery = true)
        List<Envio> buscarPorIdParcial(@Param("patron") String patron, @Param("limite") int limite);

        /**
         * ⚡ OPERACIONES DIARIAS: Obtiene envíos con estado NULL (sin filtrar por fecha).
         * El filtrado por fecha se hace en memoria considerando husos horarios.
         */
        @Query("SELECT e FROM Envio e WHERE e.estado IS NULL")
        ArrayList<Envio> findByEstadoIsNull();
}
