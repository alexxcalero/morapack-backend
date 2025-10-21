package pe.edu.pucp.morapack.dtos;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanDeVueloResponse {
    private Integer idTramo;
    private Integer ciudadOrigen;
    private String horaOrigen;
    private Double longitudOrigen;
    private Double latitudOrigen;
    private Integer ciudadDestino;
    private String horaDestino;
    private Double longitudDestino;
    private Double latitudDestino;
    private Integer capacidadMaxima;
    private Integer capacidadOcupada;
    private Integer estado;

    public Boolean estaLleno() {
        return this.capacidadMaxima == this.capacidadOcupada;
    }
}
