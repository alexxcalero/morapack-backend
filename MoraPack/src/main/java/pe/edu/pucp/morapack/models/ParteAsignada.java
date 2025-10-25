package pe.edu.pucp.morapack.models;

import jakarta.persistence.*;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonBackReference;

import java.time.ZonedDateTime;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "parte_asignada")
public class ParteAsignada {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(unique = true, nullable = false)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "id_envio")
    @JsonBackReference
    private Envio envio;

    @Transient
    private List<VueloInstanciado> ruta;

    private ZonedDateTime llegadaFinal;
    private Integer cantidad;

    @ManyToOne
    @JoinColumn(name = "id_aeropuerto_origen")
    private Aeropuerto aeropuertoOrigen;

    public ParteAsignada(List<VueloInstanciado> ruta, ZonedDateTime llegadaFinal, Integer cantidad, Aeropuerto aeropuertoOrigen) {
        this.ruta = ruta;
        this.llegadaFinal = llegadaFinal;
        this.cantidad = cantidad;
        this.aeropuertoOrigen = aeropuertoOrigen;

    }
}
