package de.schiggy.core.database;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * SQLite-Datenbank mit einem eigenen Datenbank-Thread.
 * Alle Zugriffe laufen nacheinander (FIFO) ueber diesen einen Thread, dadurch gibt es keine Race Conditions
 * und der Server-/Region-Thread wird nie blockiert. Funktioniert auf Paper und Folia.
 */
public final class Database implements AutoCloseable {

    @FunctionalInterface
    public interface SqlTask<T> {
        T run(Connection connection) throws SQLException;
    }

    private final Plugin plugin;
    private final ExecutorService executor;
    private final Connection connection;

    public Database(Plugin plugin, File file) throws SQLException {
        this.plugin = plugin;
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite-Treiber nicht gefunden", e);
        }
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
        }
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Schiggy-DB");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Fuehrt eine Aufgabe auf dem Datenbank-Thread aus. */
    public <T> CompletableFuture<T> submit(SqlTask<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.run(connection);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    /** Wie submit, aber in einer Transaktion: bei Fehlern wird alles zurueckgerollt. */
    public <T> CompletableFuture<T> transaction(SqlTask<T> task) {
        return submit(c -> {
            c.setAutoCommit(false);
            try {
                T result = task.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        });
    }

    /** Der Datenbank-Thread, z. B. fuer thenApplyAsync, damit die Reihenfolge erhalten bleibt. */
    public ExecutorService executor() {
        return executor;
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Datenbank-Thread hat nicht rechtzeitig beendet.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            connection.close();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Fehler beim Schliessen der Datenbank", e);
        }
    }
}
