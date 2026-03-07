package graduation_project_be.infrastructure.configurations;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class DataSourceConfiguration {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties primaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.configuration")
    public DataSource primaryDataSource() {
        return primaryDataSourceProperties().initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @ConfigurationProperties("spring.exam-datasource")
    public DataSourceProperties examDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "examDataSource")
    @ConfigurationProperties("spring.exam-datasource.configuration")
    public DataSource examDataSource() {
        return examDataSourceProperties().initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean(name = "examJdbcTemplate")
    public JdbcTemplate examJdbcTemplate(@Qualifier("examDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
