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

    /**
     * Estados del envío durante su ciclo de vida
     */
    public enum EstadoEnvio {
        PLANIFICADO, // Tiene ruta asignada pero el vuelo todavía no inicia
        EN_RUTA,     // Ya se encuentra en vuelo (primer vuelo inició)
        FINALIZADO,  // Llegó a su aeropuerto destino final
        ENTREGADO    // Han pasado 2 horas desde que llegó y el cliente ya lo recogió
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(unique = true, nullable = false)
    private Integer id;

    private Long idEnvioPorAeropuerto;

    // Estado del envío para rastrear su progreso
    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = true)
    private EstadoEnvio estado;

    // LAZY para no cargar todas las partes siempre
    @OneToMany(mappedBy = "envio", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JsonManagedReference
    private List<ParteAsignada> parteAsignadas = new ArrayList<>();

    private LocalDateTime fechaIngreso;

    // Offset horario del destino (ej: "-5", "-3", "1", etc.)
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

    private String cliente;

    @Transient
    private ZonedDateTime zonedFechaIngreso;

    @Transient
    private ZonedDateTime zonedFechaLlegadaMax;

    public Envio(Long idEnvioPorAeropuerto,
                 LocalDateTime fechaIngreso,
                 String husoHorarioDestino,
                 Aeropuerto destino,
                 Integer numProductos,
                 String cliente) {

        this.idEnvioPorAeropuerto = idEnvioPorAeropuerto;
        this.parteAsignadas = new ArrayList<>();
        this.fechaIngreso = fechaIngreso;
        this.husoHorarioDestino = husoHorarioDestino;
        this.aeropuertosOrigen = new ArrayList<>();
        this.aeropuertoDestino = destino;
        this.numProductos = numProductos;
        this.cliente = cliente;
        // Estado inicial en null (no asignado aún)
        this.estado = null;
    }

    public Integer cantidadAsignada() {
        try {
            if (this.parteAsignadas == null) {
                return 0;
            }
            return this.parteAsignadas.stream()
                    .mapToInt(ParteAsignada::getCantidad)
                    .sum();
        } catch (org.hibernate.LazyInitializationException e) {
            // Si hay error de lazy loading, asumimos que no hay partes cargadas
            return 0;
        }
    }

    public Integer cantidadRestante() {
        if (this.numProductos == null) {
            return 0;
        }
        return Math.max(0, this.numProductos - this.cantidadAsignada());
    }

    public Boolean estaCompleto() {
        try {
            return this.cantidadRestante() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public Boolean esMismoContinente(Aeropuerto orig) {
        return orig.getPais().getContinente().getId()
                .equals(this.aeropuertoDestino.getPais().getContinente().getId());
    }

    public Duration deadlineDesde(Aeropuerto origen) {
        return esMismoContinente(origen) ? Duration.ofDays(2) : Duration.ofDays(3);
    }

    @PostLoad
    private void cargarZonedDateTime() {
        try {
            if (this.fechaIngreso == null || this.husoHorarioDestino == null) {
                return;
            }

            int offsetDestino = Integer.parseInt(husoHorarioDestino);
            ZoneOffset zoneDestino = ZoneOffset.ofHours(offsetDestino);

            this.zonedFechaIngreso = fechaIngreso.atZone(zoneDestino);

            if (this.fechaLlegadaMax != null) {
                this.zonedFechaLlegadaMax = fechaLlegadaMax.atZone(zoneDestino);
            }

        } catch (NumberFormatException ex) {
            // Si el husoHorarioDestino no es un entero válido, evita reventar la carga
            this.zonedFechaIngreso = null;
            this.zonedFechaLlegadaMax = null;
        }
    }
}
