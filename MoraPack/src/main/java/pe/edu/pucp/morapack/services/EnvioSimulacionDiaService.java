package pe.edu.pucp.morapack.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pe.edu.pucp.morapack.dtos.EnvioSimDiaDto;
import pe.edu.pucp.morapack.dtos.EnvioSimDiaResumenDto;
import pe.edu.pucp.morapack.models.Envio;
import pe.edu.pucp.morapack.models.EstadoSimulacionDia;
import pe.edu.pucp.morapack.repository.EnvioRepository;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Servicio para clasificar envíos de un día según un instante de simulación (simMs).
 *
 * IMPORTANTE: este servicio YA NO depende de RelojSimulacionDiaService ni de Planificador.
 * El "instante de simulación" (simMs) se pasa como parámetro.
 */
@Service
@RequiredArgsConstructor
public class EnvioSimulacionDiaService {

    private final EnvioRepository envioRepository;

    private static final ZoneId ZONA_SIMULACION = ZoneId.of("America/Lima");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE; // "yyyyMMdd"

    /**
     * Devuelve la lista de envíos de una fecha (yyyyMMdd) clasificados como
     * EN_VUELO / EN_ESPERA / SIN_ESTADO según el instante de simulación simMs.
     */
    public List<EnvioSimDiaDto> obtenerEnviosClasificados(String fechaYyyyMmDd, long simMs) {
        LocalDate fecha = LocalDate.parse(fechaYyyyMmDd, YYYYMMDD);
        Instant simNow = Instant.ofEpochMilli(simMs);
        return obtenerEnviosClasificados(fecha, simNow);
    }

    /**
     * Igual que el anterior pero con LocalDate + Instant.
     */
    public List<EnvioSimDiaDto> obtenerEnviosClasificados(LocalDate fecha, Instant simNow) {
        List<Envio> envios = findEnviosParaFecha(fecha);
        return envios.stream()
                .map(e -> mapToSimDiaDto(e, simNow))
                .collect(Collectors.toList());
    }

    /**
     * Devuelve solo el resumen (total, en vuelo, en espera, sin estado) para una fecha (yyyyMMdd)
     * y un instante de simulación simMs.
     */
    public EnvioSimDiaResumenDto obtenerResumen(String fechaYyyyMmDd, long simMs) {
        LocalDate fecha = LocalDate.parse(fechaYyyyMmDd, YYYYMMDD);
        Instant simNow = Instant.ofEpochMilli(simMs);
        return obtenerResumen(fecha, simNow);
    }

    /**
     * Igual que el anterior pero con LocalDate + Instant.
     */
    public EnvioSimDiaResumenDto obtenerResumen(LocalDate fecha, Instant simNow) {
        List<EnvioSimDiaDto> lista = obtenerEnviosClasificados(fecha, simNow);

        long total = lista.size();
        long enEspera = lista.stream()
                .filter(d -> d.getEstadoSimulacion() == EstadoSimulacionDia.SIN_ESTADO)
                .count();
        long enVuelo = total - enEspera;

        EnvioSimDiaResumenDto resumen = new EnvioSimDiaResumenDto();
        resumen.setTotal(total);
        resumen.setEnVuelo(enVuelo);
        resumen.setEnEspera(enEspera);
        return resumen;
    }

    // ---------- Helpers privados ----------

    private List<Envio> findEnviosParaFecha(LocalDate fecha) {
        LocalDateTime inicio = fecha.atStartOfDay();
        LocalDateTime fin = fecha.plusDays(1).atStartOfDay().minusSeconds(1);
        return envioRepository.findByFechaIngresoBetween(inicio, fin);
    }

    private EnvioSimDiaDto mapToSimDiaDto(Envio envio, Instant simNow) {
        EnvioSimDiaDto dto = new EnvioSimDiaDto();
        dto.setId(envio.getId());
        dto.setFechaIngreso(envio.getFechaIngreso());

        // 1) Si el estado del Envío en BD es NULL -> SIN_ESTADO
        if (envio.getEstado() == null) {
            dto.setEstadoSimulacion(EstadoSimulacionDia.SIN_ESTADO);
            return dto;
        }

        // 2) Si por alguna razón fechaIngreso está null, lo consideras en espera
        if (envio.getFechaIngreso() == null) {
            dto.setEstadoSimulacion(EstadoSimulacionDia.EN_ESPERA);
            return dto;
        }

        // 3) Lógica simple basada en "ya ingresó a la simulación" vs "aún no ingresa"
        LocalDateTime fi = envio.getFechaIngreso();
        Instant ingresoInstant = fi.atZone(ZONA_SIMULACION).toInstant();

        if (!ingresoInstant.isAfter(simNow)) {
            dto.setEstadoSimulacion(EstadoSimulacionDia.EN_VUELO);
        } else {
            dto.setEstadoSimulacion(EstadoSimulacionDia.EN_ESPERA);
        }

        return dto;
    }
}
