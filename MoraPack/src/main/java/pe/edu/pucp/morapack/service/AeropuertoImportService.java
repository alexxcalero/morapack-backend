package pe.edu.pucp.morapack.service;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import pe.edu.pucp.morapack.model.Aeropuerto;
import pe.edu.pucp.morapack.model.Ciudad;
import pe.edu.pucp.morapack.model.Continente;
import pe.edu.pucp.morapack.repo.AeropuertoRepository;
import pe.edu.pucp.morapack.repo.CiudadRepository;
import pe.edu.pucp.morapack.repo.ContinenteRepository;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AeropuertoImportService {

    private final ContinenteRepository continenteRepository;
    private final CiudadRepository ciudadRepository;
    private final AeropuertoRepository aeropuertoRepository;

    public void importarDesdeArchivo(String nombreArchivo, String nombreContinente) throws Exception {
        // 1) Buscar continente
        Continente continente = continenteRepository.findByNombre(nombreContinente)
                .orElseThrow(() -> new IllegalArgumentException("No se encontró el continente: " + nombreContinente));

        // 2) Cargar archivo desde resources/data
        ClassPathResource resource = new ClassPathResource("data/" + nombreArchivo);
        if (!resource.exists()) {
            throw new IllegalArgumentException("No se encontró el archivo: " + nombreArchivo);
        }

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                procesarLinea(line, continente);
            }
        }
    }

    private void procesarLinea(String line, Continente continente) {
        // Usamos la lógica de "ancho fijo" aproximado.
        // 0-3   -> id local (no usamos)
        // 3-9   -> código IATA
        // 9-29  -> nombre ciudad
        // 29-47 -> nombre país
        // 47-55 -> abreviatura ciudad (no se usa)
        // 55-62 -> GMT (offset, ej: "-5")
        // 62-71 -> capacidad máxima (ej: "430")
        // 71+   -> texto con "Latitude: ... Longitude: ..."

        String codigoIata = safeSubstring(line, 3, 9).trim();
        String nombreCiudad = safeSubstring(line, 9, 29).trim();
        String nombrePais = safeSubstring(line, 29, 47).trim();
        String gmtStr = safeSubstring(line, 55, 62).trim();
        String capacidadStr = safeSubstring(line, 62, 71).trim();
        String coordsPart = line.length() > 71 ? line.substring(71).trim() : "";

        // GMT a huso horario, ej. "-5" -> "GMT-5"
        String husoHorario = gmtStr.isEmpty() ? null : "GMT" + gmtStr;

        int capacidadMaxima = Integer.parseInt(capacidadStr);

        // Parsear coordenadas
        double[] latLon = parseLatLon(coordsPart);
        BigDecimal latitud = BigDecimal.valueOf(latLon[0]);
        BigDecimal longitud = BigDecimal.valueOf(latLon[1]);

        // 1) Buscar o crear ciudad
        Ciudad ciudad = ciudadRepository.findAll().stream()
                .filter(c -> c.getNombre().equalsIgnoreCase(nombreCiudad)
                        && c.getNombrePais().equalsIgnoreCase(nombrePais))
                .findFirst()
                .orElseGet(() -> {
                    Ciudad nueva = Ciudad.builder()
                            .nombre(nombreCiudad)
                            .nombrePais(nombrePais)
                            .continente(continente)
                            .build();
                    return ciudadRepository.save(nueva);
                });

        // 2) Buscar aeropuerto por código IATA o crearlo
        Aeropuerto aeropuerto = aeropuertoRepository.findAll().stream()
                .filter(a -> a.getCodigoIata().equalsIgnoreCase(codigoIata))
                .findFirst()
                .orElseGet(() -> Aeropuerto.builder()
                        .codigoIata(codigoIata)
                        .nombre(nombreCiudad) 
                        .ciudad(ciudad)
                        .capacidadMaxima(capacidadMaxima)
                        .ocupacionActual(0)
                        .esSede(false) // por defecto no es sede; luego actualizar LIM/BRU/BAK
                        .husoHorario(husoHorario)
                        .latitud(latitud)
                        .longitud(longitud)
                        .build()
                );

        // 3) Actualizar información por si cambió el archivo
        aeropuerto.setCiudad(ciudad);
        aeropuerto.setCapacidadMaxima(capacidadMaxima);
        aeropuerto.setHusoHorario(husoHorario);
        aeropuerto.setLatitud(latitud);
        aeropuerto.setLongitud(longitud);

        aeropuertoRepository.save(aeropuerto);
    }

    private String safeSubstring(String s, int start, int end) {
        if (s.length() <= start) return "";
        return s.substring(start, Math.min(end, s.length()));
    }

    /**
     * Parsea un texto tipo:
     * "Latitude: 04° 42' 05\" N   Longitude:  74° 08' 49\" W"
     * y devuelve [latitudDecimal, longitudDecimal]
     */
    private double[] parseLatLon(String text) {
        // Normalizar espacios
        String normalized = text.replaceAll("\\s+", " ").trim();

        // Separar en "Longitude:"
        String[] parts = normalized.split("Longitude:");
        if (parts.length != 2) {
            return new double[]{0.0, 0.0};
        }

        String latPart = parts[0].replace("Latitude:", "").trim();
        String lonPart = parts[1].trim();

        double lat = parseDms(latPart);
        double lon = parseDms(lonPart);

        return new double[]{lat, lon};
    }

    /**
     * Parsea algo como "04° 42' 05\" N" o "074° 08' 49\" W" a grados decimales.
     */
    private double parseDms(String dms) {
        // Ejemplo: "04° 42' 05\" N"
        String clean = dms.replace("\"", "").trim();
        String[] tokens = clean.split(" ");

        // tokens esperados: [deg°, min', sec, N/S/E/W]
        int deg = Integer.parseInt(tokens[0].replace("°", "").trim());
        int min = Integer.parseInt(tokens[1].replace("'", "").trim());
        int sec = Integer.parseInt(tokens[2].trim());
        String dir = tokens[3].toUpperCase(Locale.ROOT);

        double decimal = deg + (min / 60.0) + (sec / 3600.0);

        if (dir.equals("S") || dir.equals("W")) {
            decimal = -decimal;
        }
        return decimal;
    }
}
