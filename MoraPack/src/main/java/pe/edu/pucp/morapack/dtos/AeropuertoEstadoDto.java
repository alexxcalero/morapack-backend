package pe.edu.pucp.morapack.dtos;

import lombok.Data;

@Data
public class AeropuertoEstadoDto {

    // El front busca id o idAeropuerto:
    // const id = a.id ?? a.idAeropuerto;
    private Long idAeropuerto;
    private Long id;                // por comodidad, puedes llenar ambos

    private Integer capacidadOcupada;
    private Integer capacidadMaxima;
}
