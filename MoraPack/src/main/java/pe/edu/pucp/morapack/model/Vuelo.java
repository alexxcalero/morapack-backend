package pe.edu.pucp.morapack.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalTime;
import java.math.BigDecimal;

@Entity
@Table(name = "vuelo")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Vuelo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_vuelo")
    private Long id;

    @Column(name = "codigo_vuelo", nullable = false, length = 20)
    private String codigoVuelo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_aeropuerto_origen", nullable = false)
    private Aeropuerto aeropuertoOrigen;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_aeropuerto_destino", nullable = false)
    private Aeropuerto aeropuertoDestino;

    @Column(name = "hora_salida", nullable = false)
    private LocalTime horaSalida;

    @Column(name = "hora_llegada_estimada", nullable = false)
    private LocalTime horaLlegadaEstimada;

    @Column(name = "capacidad_maxima", nullable = false)
    private Integer capacidadMaxima;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_ruta", nullable = false, length = 20)
    private TipoRuta tipoRuta;

    @Column(name = "tiempo_vuelo", nullable = false)
    private LocalTime tiempoVuelo;

    @Column(name = "latitud", precision = 9, scale = 6)
    private BigDecimal latitud;

    @Column(name = "longitud", precision = 9, scale = 6)
    private BigDecimal longitud;
}
