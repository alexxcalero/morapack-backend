package pe.edu.pucp.morapack.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "aeropuerto")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Aeropuerto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_aeropuerto")
    private Integer id;

    @Column(name = "codigo_iata", nullable = false, length = 10, unique = true)
    private String codigoIata;

    @Column(name = "nombre", nullable = false, length = 120)
    private String nombre;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_ciudad", nullable = false)
    private Ciudad ciudad;

    @Column(name = "capacidad_maxima", nullable = false)
    private Integer capacidadMaxima;

    @Column(name = "ocupacion_actual", nullable = false)
    private Integer ocupacionActual;

    @Column(name = "es_sede", nullable = false)
    private boolean esSede;

    @Column(name = "huso_horario", length = 50)
    private String husoHorario;

    @Column(name = "latitud", precision = 9, scale = 6)
    private BigDecimal latitud;

    @Column(name = "longitud", precision = 9, scale = 6)
    private BigDecimal longitud;
}
