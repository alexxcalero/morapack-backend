package pe.edu.pucp.morapack.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.pucp.morapack.model.Aeropuerto;
import pe.edu.pucp.morapack.repo.AeropuertoRepository;

@Service
@RequiredArgsConstructor
public class AeropuertoService {

    private final AeropuertoRepository aeropuertoRepository;

    @Transactional
    public Aeropuerto actualizarSedePorCodigoIata(String codigoIata, boolean esSede) {
        Aeropuerto aeropuerto = aeropuertoRepository.findByCodigoIataIgnoreCase(codigoIata)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No se encontró aeropuerto con código IATA: " + codigoIata));

        aeropuerto.setEsSede(esSede);
        return aeropuertoRepository.save(aeropuerto);
    }
}
