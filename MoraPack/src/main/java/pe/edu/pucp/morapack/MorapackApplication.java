package pe.edu.pucp.morapack;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MorapackApplication {

	public static void main(String[] args) {
		SpringApplication.run(MorapackApplication.class, args);
	}

}
