package pe.edu.pucp.morapack.models;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "aeropuerto")
public class Aeropuerto {
    @Id
    @Column(unique = true, nullable = false)
    private Integer id;

    @OneToOne
    @JoinColumn(name = "id_pais")
    private Pais pais;

    private String codigo;
    private String husoHorario;
    private Integer capacidadMaxima;
    private Integer capacidadOcupada;
    private String ciudad;
    private String abreviatura;
    private Integer estado;
    private String longitud; // Verificar luego como sera esto
    private String latitud; // Verificar luego como sera esto

    public boolean estaLleno() {
        return this.capacidadMaxima == this.capacidadOcupada;
    }

    @JsonIgnore
    public Integer getCapacidadLibre() {
        if (capacidadMaxima == null)
            return null;
        int ocupada = capacidadOcupada != null ? capacidadOcupada : 0;
        return capacidadMaxima - ocupada;
    }

    public void asignarCapacidad(Integer cantidad) {
        if (this.capacidadOcupada == null) {
            this.capacidadOcupada = 0;
        }
        this.capacidadOcupada += cantidad;

        // Verificar que no exceda la capacidad máxima
        if (this.capacidadOcupada > this.capacidadMaxima) {
            System.err.printf("⚠️  SOBREASIGNACIÓN AEROPUERTO: %s | %d > %d%n",
                    this.codigo, this.capacidadOcupada, this.capacidadMaxima);
            this.capacidadOcupada = this.capacidadMaxima;
        }
    }

    public void desasignarCapacidad(Integer cantidad) {
        if (this.capacidadOcupada == null) {
            this.capacidadOcupada = 0;
        }
        if (this.capacidadOcupada >= cantidad) {
            this.capacidadOcupada -= cantidad;
        } else {
            // System.err.printf("⚠️ Intento de desasignar más de lo asignado en aeropuerto
            // %s: %d > %d%n", this.codigo, cantidad, this.capacidadOcupada);
            this.capacidadOcupada = 0;
        }
    }

    public void setIdPais(Integer id) {
        if (this.pais == null)
            this.pais = new Pais();
        this.pais.setId(id);
    }

    public Integer getIdPais() {
        if (this.pais == null)
            return -1;
        return this.pais.getId();
    }
}
