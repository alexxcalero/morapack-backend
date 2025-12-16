package pe.edu.pucp.morapack.dtos;

import lombok.Data;

@Data
public class EnvioSimDiaResumenDto {
    private long total;
    private long enVuelo;
    private long enEspera;
    private long sinEstado;
}
