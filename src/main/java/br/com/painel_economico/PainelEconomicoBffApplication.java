package br.com.painel_economico;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class PainelEconomicoBffApplication {

	public static void main(String[] args) {
		SpringApplication.run(PainelEconomicoBffApplication.class, args);
	}

}
