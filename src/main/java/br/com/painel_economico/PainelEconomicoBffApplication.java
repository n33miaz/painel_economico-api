package br.com.painel_economico;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class PainelEconomicoBffApplication {

	public static void main(String[] args) {
		Dotenv.load();

		SpringApplication.run(PainelEconomicoBffApplication.class, args);
	}
}