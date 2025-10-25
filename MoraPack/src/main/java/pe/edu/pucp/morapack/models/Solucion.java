package pe.edu.pucp.morapack.models;

import lombok.*;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Solucion {
    private ArrayList<Envio> envios;
    private ArrayList<VueloInstanciado> vuelos;
    private Integer enviosCompletados;
    private Duration llegadaMediaPonderada;

    public Solucion(ArrayList<Envio> e, ArrayList<VueloInstanciado> v) {
        this.envios = e;
        this.vuelos = v;
        recomputar();
    }

    public void recomputar() {
        int completos = 0;
        long sumMin = 0;
        long sumCant = 0;

        for(Envio e : this.envios) {
            if(e.estaCompleto()) completos++;

            for(ParteAsignada p : e.getParteAsignadas()) {
                long minutos = ChronoUnit.MINUTES.between(e.getZonedFechaIngreso(), p.getLlegadaFinal());
                sumMin += minutos * (long) p.getCantidad();
                sumCant += p.getCantidad();
            }
        }

        this.enviosCompletados = completos;

        if(sumCant > 0) this.llegadaMediaPonderada = Duration.ofMinutes(sumMin / sumCant);
        else this.llegadaMediaPonderada = Duration.ofDays(999);
    }
}
