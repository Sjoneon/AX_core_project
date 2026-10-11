package com.project.backend;

import java.util.HashMap;
import java.util.Map;

import io.github.cdimascio.dotenv.Dotenv;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BackendApplication {

	public static void main(String[] args) {
		Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
		Map<String, Object> properties = new HashMap<>();
		dotenv.entries(Dotenv.Filter.DECLARED_IN_ENV_FILE)
				.forEach(entry -> properties.put(entry.getKey(), entry.getValue()));

		SpringApplication application = new SpringApplication(BackendApplication.class);
		application.setDefaultProperties(properties);
		application.run(args);
	}

}
