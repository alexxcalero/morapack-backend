package pe.edu.pucp.morapack.service;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.pucp.morapack.model.Aeropuerto;
import pe.edu.pucp.morapack.model.TipoRuta;
import pe.edu.pucp.morapack.model.Vuelo;
import pe.edu.pucp.morapack.repo.AeropuertoRepository;
import pe.edu.pucp.morapack.repo.VueloRepository;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class VueloImportService {

    private final AeropuertoRepository aeropuertoRepository;
    private final VueloRepository vueloRepository;

    private static final int BATCH_SIZE = 1000;

    @Transactional
    public void importarDesdeArchivo(String nombreArchivo) throws Exception {
        ClassPathResource resource = new ClassPathResource("data/" + nombreArchivo);
        if (!resource.exists()) {
            throw new IllegalArgumentException("No se encontró el archivo: " + nombreArchivo);
        }

        List<Vuelo> buffer = new ArrayList<>(BATCH_SIZE);

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                Vuelo vuelo = procesarLinea(line);
                buffer.add(vuelo);

                if (buffer.size() >= BATCH_SIZE) {
                    vueloRepository.saveAll(buffer);
                    buffer.clear();
                }
            }
        }

        // Guardar los que queden en el buffer
        if (!buffer.isEmpty()) {
            vueloRepository.saveAll(buffer);
        }
    }

    /**
     * Procesar una línea del archivo vuelos.txt y construir un objeto Vuelo (sin guardarlo aún).
     *
     * Formato de línea:
     *   ORIG-DEST-HH:mm-HH:mm-CCCC
     * Ejemplo:
     *   SKBO-SEQM-03:34-05:21-0300
     */
    private Vuelo procesarLinea(String line) {
        String[] parts = line.split("-");
        if (parts.length < 5) {
            throw new IllegalArgumentException("Línea de vuelo inválida: " + line);
        }

        String codigoOrigen = parts[0].trim();
        String codigoDestino = parts[1].trim();
        String horaSalidaStr = parts[2].trim();   // HH:mm
        String horaLlegadaStr = parts[3].trim();  // HH:mm
        String capacidadStr = parts[4].trim();    // 0300

        int capacidadMaxima = Integer.parseInt(capacidadStr);

        Aeropuerto origen = aeropuertoRepository.findByCodigoIataIgnoreCase(codigoOrigen)
                .orElseThrow(() -> new IllegalArgumentException("No se encontró aeropuerto origen: " + codigoOrigen));

        Aeropuerto destino = aeropuertoRepository.findByCodigoIataIgnoreCase(codigoDestino)
                .orElseThrow(() -> new IllegalArgumentException("No se encontró aeropuerto destino: " + codigoDestino));

        LocalTime horaSalida = LocalTime.parse(horaSalidaStr);
        LocalTime horaLlegada = LocalTime.parse(horaLlegadaStr);

        // ----------------- Cálculo de duración usando husos horarios de AEROPUERTO -----------------
        int offsetOrigen = parseOffsetHoras(origen.getHusoHorario());   // "GMT-5" -> -5
        int offsetDestino = parseOffsetHoras(destino.getHusoHorario()); // "GMT+2" -> 2

        LocalTime duracion = calcularDuracionVuelo(horaSalida, offsetOrigen, horaLlegada, offsetDestino);
        // -------------------------------------------------------------------------------------------

        // Tipo de ruta según continente
        TipoRuta tipoRuta;
        Integer contOrigen = origen.getCiudad().getContinente().getId();
        Integer contDestino = destino.getCiudad().getContinente().getId();
        if (contOrigen != null && contOrigen.equals(contDestino)) {
            tipoRuta = TipoRuta.MISMO_CONTINENTE;
        } else {
            tipoRuta = TipoRuta.DISTINTO_CONTINENTE;
        }

        String codigoVuelo = codigoOrigen + "-" + codigoDestino;

        // Posición inicial del vuelo = posición del aeropuerto de origen
        BigDecimal latitudInicial = origen.getLatitud();
        BigDecimal longitudInicial = origen.getLongitud();

        return Vuelo.builder()
                .codigoVuelo(codigoVuelo)
                .aeropuertoOrigen(origen)
                .aeropuertoDestino(destino)
                .horaSalida(horaSalida)
                .horaLlegadaEstimada(horaLlegada)
                .capacidadMaxima(capacidadMaxima)
                .tipoRuta(tipoRuta)
                .tiempoVuelo(duracion)
                .latitud(latitudInicial)
                .longitud(longitudInicial)
                .build();
    }

    /**
     * Parsea "GMT-5", "UTC+2", etc. a un entero de horas.
     */
    private int parseOffsetHoras(String huso) {
        if (huso == null || huso.isBlank()) {
            return 0;
        }
        String clean = huso.trim().toUpperCase();
        clean = clean.replace("GMT", "").replace("UTC", "").trim(); // ej. "-5" o "+2"
        if (clean.isEmpty()) {
            return 0;
        }
        return Integer.parseInt(clean);
    }

    /**
     * Aplicar la fórmula:
     * 1) H_salida_UTC = H_salida_local - GMT_origen
     * 2) H_llegada_UTC = H_llegada_local - GMT_destino
     * 3) Duración = H_llegada_UTC - H_salida_UTC (ajustando si cruza medianoche)
     */
    private LocalTime calcularDuracionVuelo(LocalTime horaSalidaLocal, int gmtOrigen,
                                            LocalTime horaLlegadaLocal, int gmtDestino) {

        int salidaLocalMin = horaSalidaLocal.getHour() * 60 + horaSalidaLocal.getMinute();
        int llegadaLocalMin = horaLlegadaLocal.getHour() * 60 + horaLlegadaLocal.getMinute();

        // 1) y 2) Convertir a UTC (en minutos)
        int salidaUtcMin = salidaLocalMin - gmtOrigen * 60;
        int llegadaUtcMin = llegadaLocalMin - gmtDestino * 60;

        // Normalizar a [0, 1440)
        salidaUtcMin = mod1440(salidaUtcMin);
        llegadaUtcMin = mod1440(llegadaUtcMin);

        // 3) Diferencia
        int diffMin = llegadaUtcMin - salidaUtcMin;
        if (diffMin < 0) {
            diffMin += 24 * 60; // llegó al día siguiente en UTC
        }

        int horas = diffMin / 60;
        int minutos = diffMin % 60;

        return LocalTime.of(horas, minutos);
    }

    private int mod1440(int minutos) {
        int m = minutos % (24 * 60);
        if (m < 0) m += 24 * 60;
        return m;
    }
}
