package com.github.drafael.chat4j.storage;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class SettingsRepo {

    private final DataSource dataSource;

    public SettingsRepo(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Optional<String> get(String key) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT \"value\" FROM settings WHERE \"key\" = ?"
             )
        ) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getString(1));
                }
            }
        }

        return Optional.empty();
    }

    public String get(String key, String defaultValue) throws SQLException {
        return get(key).orElse(defaultValue);
    }

    public void put(String key, String value) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "MERGE INTO settings (\"key\", \"value\") VALUES (?, ?)"
             )
        ) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    public void remove(String key) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "DELETE FROM settings WHERE \"key\" = ?"
             )
        ) {
            ps.setString(1, key);
            ps.executeUpdate();
        }
    }

    public Map<String, String> findByPrefix(String prefix) throws SQLException {
        String safePrefix = prefix == null ? "" : prefix;
        String escapedPrefix = safePrefix
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");

        Map<String, String> entries = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT \"key\", \"value\" FROM settings "
                             + "WHERE \"key\" LIKE ? ESCAPE '!' "
                             + "ORDER BY \"key\""
             )
        ) {
            ps.setString(1, escapedPrefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.put(rs.getString(1), rs.getString(2));
                }
            }
        }

        return entries;
    }
}
