package br.com.renan.painelhome

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.util.Log
import com.google.home.HomeException
import com.google.home.PermissionsState
import java.net.URI
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class RelayService : Service() {
    companion object {
        internal val status = MutableStateFlow<String?>(null)
        private const val CHANNEL = "painel-casa-connection"
    }

    private val scope = MainScope()
    private var connection: Job? = null
    private var configuration: Pair<String, String>? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Conexão do Painel Casa", NotificationManager.IMPORTANCE_LOW))
        val openPanel = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Painel Casa em segundo plano")
            .setContentText("Mantendo a conexão com o painel web.")
            .setContentIntent(openPanel)
            .setOngoing(true)
            .build()
        startForeground(1, notification)

        val prefs = getSharedPreferences("relay", MODE_PRIVATE)
        val address = prefs.getString("endpoint", "").orEmpty()
        val key = prefs.getString("token", "").orEmpty()
        if (address.isBlank() || key.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val next = address to key
        if (next == configuration && connection?.isActive == true) return START_STICKY
        configuration = next
        val previous = connection
        connection = scope.launch {
            previous?.cancelAndJoin()
            status.value = "Verificando acesso ao Google Home…"
            val home = MainActivity.homeClient(this@RelayService)
            var permission: PermissionsState? = null
            while (permission == null) {
                try {
                    permission = withTimeoutOrNull(10_000) {
                        home.hasPermissions().first { it != PermissionsState.PERMISSIONS_STATE_UNINITIALIZED }
                    }
                } catch (exception: HomeException) {
                    Log.w("PainelCasa", "Falha ao consultar acesso ao Google Home", exception)
                }
                if (permission == null) delay(5_000)
            }
            if (permission != PermissionsState.GRANTED) {
                status.value = "Toque em Conectar painel para autorizar o Google Home."
                stopSelf()
                return@launch
            }
            status.value = "Conectando à API HTTPS…"
            MainActivity.PanelServer("127.0.0.1", "", home).sync(URI(address), key) { status.value = it }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
