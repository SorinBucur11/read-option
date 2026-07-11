package app.readoption;

import app.readoption.agent.AgentProperties;
import app.readoption.customization.CustomizationProperties;
import app.readoption.news.NewsProperties;
import app.readoption.reconciliation.ReconcileProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({ReconcileProperties.class, CustomizationProperties.class,
        AgentProperties.class, NewsProperties.class})
public class ReadOptionApplication {

	public static void main(String[] args) {
		SpringApplication.run(ReadOptionApplication.class, args);
	}

}
