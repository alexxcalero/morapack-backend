package pe.edu.pucp.morapack.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "vuelo_instanciado")
public class VueloInstanciado {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(unique = true, nullable = false)
    private Integer id;

    @OneToOne
    @JoinColumn(name = "id_plan_de_vuelo")
    private PlanDeVuelo vueloBase;

    private Integer capacidadOcupada;

    @Transient
    private ZonedDateTime zonedHoraOrigen;

    @Transient
    private ZonedDateTime zonedHoraDestino;

    public VueloInstanciado(PlanDeVuelo vueloBase, ZonedDateTime origen, ZonedDateTime destino) {
        this.vueloBase = vueloBase;
        this.zonedHoraOrigen = origen;
        this.zonedHoraDestino = destino;
        this.capacidadOcupada = 0;
    }

    public Integer getCapacidadMaxima() {
        return this.vueloBase.getCapacidadMaxima();
    }

    public Integer getCapacidadLibre() {
        return this.vueloBase.getCapacidadMaxima() - this.capacidadOcupada;
    }

    void asignar(Integer cantidad) {
        this.capacidadOcupada += cantidad;
    }

    void desasignar(Integer cantidad) {
        this.capacidadOcupada -= cantidad;
    }

    @PostLoad
    private void cargarZonedDateTime() {
        Integer offsetOrigen = Integer.parseInt(this.vueloBase.getHusoHorarioOrigen());
        Integer offsetDestino = Integer.parseInt(this.vueloBase.getHusoHorarioDestino());

        ZoneOffset zoneOrigen = ZoneOffset.ofHours(offsetOrigen);
        ZoneOffset zoneDestino = ZoneOffset.ofHours(offsetDestino);

        this.zonedHoraOrigen = this.vueloBase.getHoraOrigen().atZone(zoneOrigen);
        this.zonedHoraDestino = this.vueloBase.getHoraDestino().atZone(zoneDestino);
    }
}
