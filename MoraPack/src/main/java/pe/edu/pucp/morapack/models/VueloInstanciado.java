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
        // ✅ Asegúrate de que esté sumando, no reemplazando
        this.capacidadOcupada += cantidad;

        // Y verifica que no exceda la capacidad máxima
        if (this.capacidadOcupada > this.getCapacidadMaxima()) {
            System.err.printf("⚠️  SOBREASIGNACIÓN: %s->%s | %d > %d%n",
                    this.getVueloBase().getCiudadOrigen(), this.getVueloBase().getCiudadDestino(),
                    this.capacidadOcupada, this.getCapacidadMaxima());
            this.capacidadOcupada = this.getCapacidadMaxima();
        }
    }

    void desasignar(Integer cantidad) {
        // Mejor implementación que evita capacidades negativas
        if (this.capacidadOcupada >= cantidad) {
            this.capacidadOcupada -= cantidad;
        } else {
            // Esto no debería pasar, pero por seguridad
            System.err.printf("⚠️  Intento de desasignar más de lo asignado: %d > %d%n",
                    cantidad, this.capacidadOcupada);
            this.capacidadOcupada = 0;
        }
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
