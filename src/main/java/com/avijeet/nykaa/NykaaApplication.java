package com.avijeet.nykaa;

import com.avijeet.nykaa.repository.elasticsearch.ProductSearchRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories(
    basePackages = "com.avijeet.nykaa.repository", 
    excludeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
        type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE, 
        classes = ProductSearchRepository.class
    )
)
@EnableElasticsearchRepositories(basePackages = "com.avijeet.nykaa.repository.elasticsearch")
public class NykaaApplication {

	public static void main(String[] args) {
		SpringApplication.run(NykaaApplication.class, args);
	}

}