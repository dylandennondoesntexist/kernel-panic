package app.kernelpanic.data

import app.kernelpanic.detector.CompletionReason
import app.kernelpanic.detector.DetectorSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SessionRepository(private val dao: SessionDao) {
    suspend fun all(): List<SessionEntity> = withContext(Dispatchers.IO) { dao.all }

    suspend fun save(snapshot: DetectorSnapshot): SessionEntity = withContext(Dispatchers.IO) {
        val entity = SessionEntity(
            System.currentTimeMillis(),
            snapshot.elapsedMs,
            snapshot.estimatedPopCount,
            snapshot.estimatedFirstPopMs,
            snapshot.estimatedPeakRate,
            snapshot.recentIntervalSeconds,
            snapshot.completionReason == CompletionReason.DONE_DETECTED,
            (snapshot.completionReason ?: CompletionReason.INTERRUPTED).name,
        )
        entity.id = dao.insert(entity)
        entity
    }

    suspend fun delete(session: SessionEntity) = withContext(Dispatchers.IO) { dao.delete(session) }
    suspend fun deleteAll() = withContext(Dispatchers.IO) { dao.deleteAll() }
}
