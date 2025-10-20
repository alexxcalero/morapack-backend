package pe.edu.pucp.morapack.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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

    private LocalDateTime fechaIngreso;
    private LocalDateTime fechaLlegadaMax;
    private String husoHorarioOrigen;
    private String husoHorarioDestino;
    private Integer aeropuertoOrigen;
    private Integer aeropuertoDestino;
    private Integer numProductos;

    @OneToMany(mappedBy = "envio", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    private List<Producto> productos = new ArrayList<>();

    @Transient
    private ZonedDateTime zonedFechaIngreso;

    @Transient
    private ZonedDateTime zonedFechaLlegadaMax;

    @PostLoad
    private void cargarZonedDateTime() {
        this.zonedFechaIngreso = fechaIngreso.atZone(ZoneId.of(husoHorarioOrigen));
        this.zonedFechaLlegadaMax = fechaLlegadaMax.atZone(ZoneId.of(husoHorarioOrigen));
    }
}
