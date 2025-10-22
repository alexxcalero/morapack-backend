package pe.edu.pucp.morapack.principal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class Main {
    @GetMapping("/")
    public String home() {
        return "Bienvenido al sistema de MoraPack";
    }
}
