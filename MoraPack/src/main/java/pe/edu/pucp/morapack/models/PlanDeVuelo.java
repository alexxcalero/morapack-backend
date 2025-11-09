package pe.edu.pucp.morapack.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;

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
    private Boolean mismoContinente;

    private Integer estado;

    @Transient
    private ZonedDateTime zonedHoraOrigen;

    @Transient
    private ZonedDateTime zonedHoraDestino;

    public Integer getCapacidadLibre() {
        return this.capacidadMaxima - this.capacidadOcupada;
    }

    void asignar(Integer cantidad) {
        // ✅ Asegúrate de que esté sumando, no reemplazando
        this.capacidadOcupada += cantidad;

        // Y verifica que no exceda la capacidad máxima
        if(this.capacidadOcupada > this.getCapacidadMaxima()) {
            System.err.printf("⚠️  SOBREASIGNACIÓN: %s->%s | %d > %d%n", this.getCiudadOrigen(), this.getCiudadDestino(), this.capacidadOcupada, this.getCapacidadMaxima());
            this.capacidadOcupada = this.getCapacidadMaxima();
        }
    }

    void desasignar(Integer cantidad) {
        // Mejor implementación que evita capacidades negativas
        if(this.capacidadOcupada >= cantidad) {
            this.capacidadOcupada -= cantidad;
        } else {
            // Esto no debería pasar, pero por seguridad
            System.err.printf("⚠️  Intento de desasignar más de lo asignado: %d > %d%n", cantidad, this.capacidadOcupada);
            this.capacidadOcupada = 0;
        }
    }

    @PostLoad
    private void cargarZonedDateTime() {
        Integer offsetOrigen = Integer.parseInt(this.husoHorarioOrigen);
        Integer offsetDestino = Integer.parseInt(this.husoHorarioDestino);

        ZoneOffset zoneOrigen = ZoneOffset.ofHours(offsetOrigen);
        ZoneOffset zoneDestino = ZoneOffset.ofHours(offsetDestino);

        this.zonedHoraOrigen = this.horaOrigen.atZone(zoneOrigen);
        this.zonedHoraDestino = this.horaDestino.atZone(zoneDestino);
    }
}
