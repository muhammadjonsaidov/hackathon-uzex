package uz.uzex.agent.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Connection for users and chats, under a role that can write only to the app schema. It is
 * deliberately not a DataSource bean: the agent's SQL must keep using the read-only one.
 */
@Component
public class AppDatabase {

    @ConfigurationProperties("app-db")
    public record Properties(String url, String username, String password) {}

    private final HikariDataSource dataSource;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public AppDatabase(Properties properties) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("app");
        config.setJdbcUrl(properties.url());
        config.setUsername(properties.username());
        config.setPassword(properties.password());
        config.setMaximumPoolSize(8);
        config.setConnectionTimeout(5000);
        config.setInitializationFailTimeout(-1);
        this.dataSource = new HikariDataSource(config);
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    public TransactionTemplate transactions() {
        return transactions;
    }

    @PreDestroy
    void close() {
        dataSource.close();
    }
}
