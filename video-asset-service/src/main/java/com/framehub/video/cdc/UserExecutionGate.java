package com.framehub.video.cdc;

import com.framehub.video.config.VideoProperties;
import com.framehub.video.service.CheckpointTransactions.Busy;
import java.sql.*;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

@Component
public class UserExecutionGate {
  private final DataSource data;
  private final VideoProperties p;

  public UserExecutionGate(DataSource data, VideoProperties p) {
    this.data = data;
    this.p = p;
  }

  // Connection-scoped advisory locks only bound per-user concurrency; they never dispatch work.
  public Permit enter(long user) throws SQLException {
    Connection c = data.getConnection();
    try {
      for (int i = 0; i < p.perUserConcurrency(); i++) {
        String key = "video-user:" + user + ":" + i;
        try (var statement = c.prepareStatement("SELECT GET_LOCK(?,0)")) {
          statement.setString(1, key);
          try (var rs = statement.executeQuery()) {
            if (rs.next() && rs.getInt(1) == 1) return new Permit(c, key);
          }
        }
      }
      throw new Busy("User transcode concurrency limit");
    } catch (Exception e) {
      c.close();
      throw e;
    }
  }

  public record Permit(Connection connection, String key) implements AutoCloseable {
    public void close() throws SQLException {
      try (var statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
        statement.setString(1, key);
        statement.execute();
      } finally {
        connection.close();
      }
    }
  }
}
