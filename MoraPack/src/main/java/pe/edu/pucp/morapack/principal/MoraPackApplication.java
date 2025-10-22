package pe.edu.pucp.morapack.principal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "pe.edu.pucp.morapack")
@EntityScan(basePackages = "pe.edu.pucp.morapack")
@EnableJpaRepositories(basePackages = "pe.edu.pucp.morapack")
public class MoraPackApplication {

	public static void main(String[] args) {
		SpringApplication.run(MoraPackApplication.class, args);
	}

}
