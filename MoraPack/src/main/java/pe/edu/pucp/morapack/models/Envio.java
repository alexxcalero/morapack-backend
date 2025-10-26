package pe.edu.pucp.morapack.models;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.*;

import java.time.*;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "envio")
public class Envio {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(unique = true, nullable = false)
    private Integer id;

    // Cada parte tiene su propia ruta y cantidad
    @OneToMany(mappedBy = "envio", cascade = CascadeType.ALL)
    @JsonManagedReference
    private List<ParteAsignada> parteAsignadas = new ArrayList<>();

    private LocalDateTime fechaIngreso;
    private String husoHorarioDestino;

    @Transient
    private List<Aeropuerto> aeropuertosOrigen = new ArrayList<>(); // Multihub

    @ManyToOne
    @JoinColumn(name = "id_aeropuerto_destino")
    private Aeropuerto aeropuertoDestino;

    @ManyToOne
    @JoinColumn(name = "id_aeropuerto_origen")
    private Aeropuerto aeropuertoOrigen;

    private Integer numProductos;

    private LocalDateTime fechaLlegadaMax;

    @Transient
    private ZonedDateTime zonedFechaIngreso;

    @Transient
    private ZonedDateTime zonedFechaLlegadaMax;

    public Envio(LocalDateTime fechaIngreso, String husoHorarioDestino, Aeropuerto destino, Integer numProductos) {
        this.parteAsignadas = new ArrayList<>();
        this.fechaIngreso = fechaIngreso;
        this.husoHorarioDestino = husoHorarioDestino;
        this.aeropuertosOrigen = new ArrayList<>();
        this.aeropuertoDestino = destino;
        this.husoHorarioDestino = husoHorarioDestino;
        this.numProductos = numProductos;
    }

    public Integer cantidadAsignada() {
        return this.parteAsignadas.stream().mapToInt(ParteAsignada::getCantidad).sum();
    }

    public Integer cantidadRestante() {
        return Math.max(0, this.numProductos - this.cantidadAsignada());
    }

    public Boolean estaCompleto() {
        return this.cantidadRestante() == 0;
    }

    public Boolean esMismoContinente(Aeropuerto orig) {
        return orig.getPais().getContinente().getId().equals(this.aeropuertoDestino.getPais().getContinente().getId());
    }

    public Duration deadlineDesde(Aeropuerto origen) {
        return esMismoContinente(origen) ? Duration.ofDays(2) : Duration.ofDays(3);
    }

    @PostLoad
    private void cargarZonedDateTime() {
        Integer offsetDestino = Integer.parseInt(husoHorarioDestino);

        ZoneOffset zoneDestino = ZoneOffset.ofHours(offsetDestino);

        this.zonedFechaIngreso = fechaIngreso.atZone(zoneDestino);
    }
}
