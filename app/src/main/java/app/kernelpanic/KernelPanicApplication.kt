package app.kernelpanic

import android.app.Application
import androidx.room.Room
import app.kernelpanic.data.KernelPanicDatabase
import app.kernelpanic.data.SessionRepository

class KernelPanicApplication : Application() {
    val database: KernelPanicDatabase by lazy {
        Room.databaseBuilder(this, KernelPanicDatabase::class.java, "kernel-panic.db").build()
    }
    val sessions: SessionRepository by lazy { SessionRepository(database.sessionDao()) }
}
