package app.kernelpanic.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY timestampEpochMs DESC")
    List<SessionEntity> getAll();

    @Insert
    long insert(SessionEntity session);

    @Delete
    void delete(SessionEntity session);

    @Query("DELETE FROM sessions")
    void deleteAll();
}
