package pe.edu.pucp.morapack.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "plan_de_vuelo")
public class PlanDeVuelo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(unique = true, nullable = false)
    private Integer id;

    private Integer ciudadOrigen;
    private Integer ciudadDestino;
    private LocalDateTime horaOrigen;
    private LocalDateTime horaDestino;
    private String husoHorarioOrigen;
    private String husoHorarioDestino;
    private Integer capacidadMaxima;
    private Integer capacidadOcupada;
    private Integer estado;

    @Transient
    private ZonedDateTime zonedHoraOrigen;

    @Transient
    private ZonedDateTime zonedHoraDestino;

    public boolean estaLleno() {
        return this.capacidadMaxima == this.capacidadOcupada;
    }

    @PostLoad
    private void cargarZonedDateTime() {
        this.zonedHoraOrigen = horaOrigen.atZone(ZoneId.of(husoHorarioOrigen));
        this.zonedHoraDestino = horaDestino.atZone(ZoneId.of(husoHorarioDestino));
    }
}
