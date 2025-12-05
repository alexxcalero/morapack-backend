package pe.edu.pucp.morapack.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class VueloSimDTO {

    private Long idVuelo;
    private String codigoVuelo;
    private double latitud;
    private double longitud;
    private String estado; // EN_ORIGEN, EN_VUELO, EN_DESTINO
}
