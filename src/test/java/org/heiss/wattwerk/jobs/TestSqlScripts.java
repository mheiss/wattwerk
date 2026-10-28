package org.heiss.wattwerk.jobs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

final class TestSqlScripts {
    private TestSqlScripts() {
    }

    static void execute(Connection connection, String resource) throws IOException, SQLException {
        try (var stream = TestSqlScripts.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Missing SQL test resource: " + resource);
            }
            String script = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            try (Statement statement = connection.createStatement()) {
                for (String sql : script.split(";")) {
                    if (!sql.isBlank()) {
                        statement.execute(sql);
                    }
                }
            }
        }
    }
}
