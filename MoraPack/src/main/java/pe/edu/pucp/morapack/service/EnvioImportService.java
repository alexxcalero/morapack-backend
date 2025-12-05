package pe.edu.pucp.morapack.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.pucp.morapack.model.Aeropuerto;
import pe.edu.pucp.morapack.model.Envio;
import pe.edu.pucp.morapack.repo.AeropuertoRepository;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class EnvioImportService {

    private final AeropuertoRepository aeropuertoRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private static final int BATCH_SIZE = 1000;

    @Transactional
    public void importarPedidosDestino(String codigoIataDestino) throws Exception {
        String nombreArchivo = "pedidos/_pedidos_" + codigoIataDestino + "_.txt";

        ClassPathResource resource = new ClassPathResource("data/" + nombreArchivo);
        if (!resource.exists()) {
            throw new IllegalArgumentException("No se encontró el archivo de pedidos: " + nombreArchivo);
        }

        Aeropuerto aeropuertoDestino = aeropuertoRepository.findByCodigoIataIgnoreCase(codigoIataDestino)
                .orElseThrow(() -> new IllegalArgumentException("No se encontró aeropuerto destino: " + codigoIataDestino));

        int count = 0;

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                Envio envio = parseLineaPedido(line, aeropuertoDestino);
                entityManager.persist(envio);
                count++;

                if (count % BATCH_SIZE == 0) {
                    entityManager.flush();
                    entityManager.clear();
                }
            }

            // flush final
            entityManager.flush();
            entityManager.clear();
        }
    }

    private Envio parseLineaPedido(String line, Aeropuerto aeropuertoDestino) {
        // id_pedido-aaaammdd-hh-mm-dest-###-IdCliente
        String[] parts = line.split("-");
        if (parts.length != 7) {
            throw new IllegalArgumentException("Línea de pedido inválida: " + line);
        }

        String idPedido     = parts[0].trim(); // 000000001
        String fechaStr     = parts[1].trim(); // 20250102
        String hhStr        = parts[2].trim(); // 00
        String mmStr        = parts[3].trim(); // 50
        String destArchivo  = parts[4].trim(); // VIDP
        String cantidadStr  = parts[5].trim(); // 002
        String idCliente    = parts[6].trim(); // 0029563

        if (!destArchivo.equalsIgnoreCase(aeropuertoDestino.getCodigoIata())) {
            throw new IllegalArgumentException("Destino en línea (" + destArchivo +
                    ") no coincide con aeropuerto destino del archivo (" + aeropuertoDestino.getCodigoIata() + ")");
        }

        DateTimeFormatter formatterFecha = DateTimeFormatter.ofPattern("yyyyMMdd");
        LocalDate fecha = LocalDate.parse(fechaStr, formatterFecha);

        int hh = Integer.parseInt(hhStr);
        int mm = Integer.parseInt(mmStr);
        LocalTime hora = LocalTime.of(hh, mm);

        LocalDateTime fechaCreacion = LocalDateTime.of(fecha, hora);
        LocalDateTime fechaLimite   = fechaCreacion.plusDays(3);

        int cantidad = Integer.parseInt(cantidadStr);
        String codigoEnvio = idPedido + "-" + destArchivo;

        return Envio.builder()
                .codigoEnvio(codigoEnvio)
                .codigoCliente(idCliente)
                .aeropuertoOrigen(null)
                .aeropuertoDestino(aeropuertoDestino)
                .cantidad(cantidad)
                .fechaCreacion(fechaCreacion)
                .fechaLimiteEntrega(fechaLimite)
                .fechaEntregaReal(null)
                // estado por defecto: PENDIENTE_PLANIFICACION (con @Builder.Default)
                .build();
    }
}
