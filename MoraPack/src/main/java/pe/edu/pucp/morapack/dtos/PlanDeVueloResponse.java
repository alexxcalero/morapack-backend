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
    private String longitudOrigen;  // Verificar luego como sera esto
    private String latitudOrigen;  // Verificar luego como sera esto
    private Integer ciudadDestino;
    private String horaDestino;
    private String longitudDestino;  // Verificar luego como sera esto
    private String latitudDestino;  // Verificar luego como sera esto
    private Integer capacidadMaxima;
    private Integer capacidadOcupada;
    private Integer estado;

    public Boolean estaLleno() {
        return this.capacidadMaxima == this.capacidadOcupada;
    }
}
