package pe.edu.pucp.morapack.dtos;

import lombok.Data;
import java.time.LocalDateTime;
import pe.edu.pucp.morapack.models.EstadoSimulacionDia;

@Data
public class EnvioSimDiaDto {
    private Integer id;
    private LocalDateTime fechaIngreso;
    private EstadoSimulacionDia estadoSimulacion;
}
