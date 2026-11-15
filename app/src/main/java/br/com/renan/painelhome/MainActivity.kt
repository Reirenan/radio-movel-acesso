package br.com.renan.painelhome

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.google.home.FactoryRegistry
import com.google.home.Home
import com.google.home.HomeClient
import com.google.home.HomeConfig
import com.google.home.HomeDevice
import com.google.home.PermissionsResultStatus
import com.google.home.PermissionsState
import com.google.home.matter.standard.FanControl
import com.google.home.matter.standard.OnOff
import com.google.home.matter.standard.RoomAirConditionerDevice
import com.google.home.matter.standard.Thermostat
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    companion object {
        private var client: HomeClient? = null
    }

    private val scope = MainScope()
    private var server: PanelServer? = null
    private var relay: Job? = null
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val home = client ?: Home.getClient(
            applicationContext,
            HomeConfig(
                coroutineContext = Dispatchers.IO,
                factoryRegistryProvider = {
                    FactoryRegistry(
                        traits = listOf(OnOff, Thermostat, FanControl),
                        types = listOf(RoomAirConditionerDevice),
                    )
                },
                homePlatformScope = HomeConfig.HomePlatformScope.HOME_PLATFORM_SCOPE_VERSION_1,
            ),
        ).also { client = it }
        home.registerActivityResultCallerForPermissions(this)
        val prefs = getSharedPreferences("relay", MODE_PRIVATE)
        val endpoint = EditText(this).apply {
            hint = "Endereço HTTPS da API"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("endpoint", ""))
        }
        val relayKey = EditText(this).apply {
            hint = "Token do POCO"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("token", ""))
        }
        status = TextView(this).apply {
            text = "Conecte o POCO e o navegador à mesma rede Wi-Fi."
            setTextIsSelectable(true)
            textSize = 17f
        }
        val connect = Button(this).apply { text = "Conectar painel" }
        fun connectPanel(interactive: Boolean) {
            if (!connect.isEnabled) return
            connect.isEnabled = false
            status.text = "Verificando acesso ao Google Home…"
            scope.launch {
                try {
                    val address = endpoint.text.toString().trim().trimEnd('/')
                    val key = relayKey.text.toString().trim()
                    val remote = if (address.isEmpty() && key.isEmpty()) null else URI(address).also {
                        require(it.scheme == "https" && it.host != null && it.userInfo == null &&
                            it.path.isEmpty() && it.query == null && it.fragment == null && key.length >= 32) {
                            "Informe o endereço HTTPS e o token do POCO."
                        }
                    }
                    val permission = withTimeoutOrNull(10_000) {
                        home.hasPermissions().first { it != PermissionsState.PERMISSIONS_STATE_UNINITIALIZED }
                    }
                    check(permission != null) { "O Google Home demorou a responder. Toque em Conectar painel para tentar novamente." }
                    if (permission != PermissionsState.GRANTED) {
                        if (!interactive) {
                            status.text = "Toque em Conectar painel para conceder acesso ao Google Home."
                            return@launch
                        }
                        val result = home.requestPermissions()
                        check(result.status == PermissionsResultStatus.SUCCESS) {
                            "Autorização do Google Home: ${result.status}"
                        }
                    }
                    val ip = wifiAddress()
                    relay?.cancelAndJoin()
                    server?.stop()
                    val token = UUID.randomUUID().toString()
                    val panel = PanelServer(ip ?: "127.0.0.1", token, home)
                    if (remote == null) {
                        require(ip != null) { "Conecte-se ao Wi-Fi." }
                        panel.start(5000, true)
                        server = panel
                        status.text = "Abra no navegador:\nhttp://$ip:8787/#$token\n\nMantenha o Painel Casa aberto."
                    } else {
                        prefs.edit().putString("endpoint", address).putString("token", key).apply()
                        status.text = "Conectando à API HTTPS…"
                        relay = scope.launch { panel.sync(remote, key) }
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    status.text = exception.message ?: "Falha ao abrir o painel."
                } finally {
                    connect.isEnabled = true
                }
            }
        }
        connect.setOnClickListener { connectPanel(interactive = true) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 72, 36, 36)
            addView(endpoint)
            addView(relayKey)
            addView(connect)
            addView(status)
        })
        if (endpoint.text.isNotBlank() && relayKey.text.isNotBlank()) connectPanel(interactive = false)
    }

    override fun onDestroy() {
        server?.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun wifiAddress(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && it.name.startsWith("wlan") }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress }?.hostAddress

    private inner class PanelServer(
        ip: String,
        private val token: String,
        private val home: HomeClient,
    ) : NanoHTTPD(ip, 8787) {
        private val lock = Mutex()
        private val page = assets.open("panel.html").bufferedReader().use { it.readText() }

        override fun serve(session: IHTTPSession): Response {
            if (session.method == Method.GET && session.uri == "/") {
                return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page)
                    .apply { addHeader("Cache-Control", "no-store") }
            }
            if (session.headers["authorization"] != "Bearer $token") {
                return json(Response.Status.UNAUTHORIZED, JSONObject().put("erro", "Link inválido."))
            }
            return try {
                val payload = runBlocking {
                    withTimeout(15_000) {
                        lock.withLock {
                            when {
                                session.method == Method.GET && session.uri == "/api/casa" -> inventory()
                                session.method == Method.POST && session.uri == "/api/ar" -> command(
                                    session.parameters["acao"]?.singleOrNull(),
                                    session.parameters["valor"]?.singleOrNull()?.toIntOrNull(),
                                )
                                else -> throw IllegalArgumentException("Rota inválida.")
                            }
                        }
                    }
                }
                json(Response.Status.OK, payload)
            } catch (exception: IllegalArgumentException) {
                json(Response.Status.BAD_REQUEST, JSONObject().put("erro", exception.message))
            } catch (exception: Exception) {
                Log.w("PainelCasa", "${session.method} ${session.uri}", exception)
                json(
                    Response.Status.SERVICE_UNAVAILABLE,
                    JSONObject().put("erro", "O Google Home não confirmou a operação. Atualize para conferir o estado."),
                )
            }
        }

        private fun json(status: Response.Status, payload: JSONObject) =
            newFixedLengthResponse(status, "application/json; charset=utf-8", payload.toString())
                .apply { addHeader("Cache-Control", "no-store") }

        private suspend fun inventory(): JSONObject {
            val structure = selectedStructure()
            val rooms = structure.rooms().list().associate { it.id to it.name }
            val devices = structure.devices().list()
            val air = devices.singleOrNull { it.name.equals("Ar", ignoreCase = true) }
            return JSONObject()
                .put("casa", structure.name)
                .put("ambientes", JSONArray(rooms.values.sorted()))
                .put("dispositivos", JSONArray(devices.sortedBy { it.name }.map {
                    JSONObject().put("nome", it.name).put("ambiente", rooms[it.roomId] ?: "Sem ambiente")
                }))
                .put("ar", air?.let { climate(it) } ?: JSONObject.NULL)
        }

        private suspend fun climate(device: HomeDevice): JSONObject {
            val traits = device.typeOrNull(RoomAirConditionerDevice).first()?.standardTraits
            val onOff = traits?.onOff
            val thermostat = traits?.thermostat
            val fan = traits?.fanControl
            return JSONObject()
                .put("ligado", onOff?.onOff ?: JSONObject.NULL)
                .put("liga", onOff?.supports(OnOff.Command.On) == true)
                .put("desliga", onOff?.supports(OnOff.Command.Off) == true)
                .put("temperatura", thermostat?.occupiedCoolingSetpoint?.div(100.0) ?: JSONObject.NULL)
                .put("ventilador", fan?.percentSetting?.toInt() ?: JSONObject.NULL)
        }

        private suspend fun command(action: String?, value: Int?): JSONObject {
            require(action in listOf("ligar", "desligar", "temperatura", "ventilador")) { "Comando inválido." }
            val device = selectedStructure().devices().list()
                .singleOrNull { it.name.equals("Ar", ignoreCase = true) }
                ?: throw IllegalArgumentException("O dispositivo Ar precisa estar autorizado e ter nome único.")
            val traits = device.typeOrNull(RoomAirConditionerDevice).first()?.standardTraits
                ?: throw IllegalArgumentException("O Google Home não disponibilizou controles de ar-condicionado para Ar.")
            when (action) {
                "ligar" -> {
                    val trait = traits.onOff
                    require(trait?.supports(OnOff.Command.On) == true) { "O aparelho não oferece o comando de ligar." }
                    val thermostat = traits.thermostat
                    if (value != null) {
                        require(value in 16..30) { "Use uma temperatura entre 16 e 30 °C." }
                        require(thermostat?.occupiedCoolingSetpoint != null) { "A temperatura de resfriamento não está disponível." }
                    }
                    trait.on()
                    if (value != null) {
                        checkNotNull(thermostat).update { setOccupiedCoolingSetpoint((value * 100).toShort()) }
                    }
                }
                "desligar" -> {
                    val trait = traits.onOff
                    require(trait?.supports(OnOff.Command.Off) == true) { "O aparelho não oferece o comando de desligar." }
                    trait.off()
                }
                "temperatura" -> {
                    require(value != null && value in 16..30) { "Use uma temperatura entre 16 e 30 °C." }
                    val trait = traits.thermostat
                    require(trait?.occupiedCoolingSetpoint != null) { "A temperatura de resfriamento não está disponível." }
                    trait.update { setOccupiedCoolingSetpoint((value * 100).toShort()) }
                }
                "ventilador" -> {
                    require(value != null && value in 0..100) { "Use uma velocidade entre 0 e 100%." }
                    val trait = traits.fanControl
                    require(trait?.percentSetting != null) { "O ajuste de velocidade em porcentagem não está disponível." }
                    trait.update { setPercentSetting(value.toUByte()) }
                }
            }
            return JSONObject().put("enviado", true)
        }

        private suspend fun selectedStructure() = home.structures().first { structures ->
            Log.i("PainelCasa", "Casas retornadas pelo Google Home: ${structures.size}")
            structures.isNotEmpty()
        }.single()

        suspend fun sync(remote: URI, key: String) {
            var result: JSONObject? = null
            while (true) {
                try {
                    val snapshot = kotlinx.coroutines.withTimeoutOrNull(10_000) { inventory() }
                    val payload = JSONObject().put("casa", snapshot).put("resultado", result)
                    val response = withContext(Dispatchers.IO) { syncRequest(remote, key, payload) }
                    result = null
                    val queued = response.optJSONObject("comando")
                    if (queued != null) {
                        val sent = try {
                            kotlinx.coroutines.withTimeoutOrNull(15_000) {
                                val value = if (queued.isNull("valor")) null else queued.getInt("valor")
                                command(queued.getString("acao"), value)
                            } != null
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            Log.w("PainelCasa", "Falha no comando remoto", exception)
                            false
                        }
                        result = JSONObject().put("id", queued.getString("id")).put("enviado", sent)
                    }
                    status.text = if (snapshot == null) "API conectada. Aguardando dados do Google Home."
                        else "API conectada. Mantenha o Painel Casa aberto."
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    status.text = "Falha ao sincronizar. Tentando novamente…"
                    Log.w("PainelCasa", "Falha na sincronização", exception)
                    delay(10_000)
                }
                if (result == null) delay(10_000)
            }
        }

        private fun syncRequest(remote: URI, key: String, payload: JSONObject): JSONObject {
            val connection = remote.resolve("/api/ponte/sincronizar").toURL().openConnection() as HttpURLConnection
            return try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.instanceFollowRedirects = false
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $key")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
                connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            } finally {
                connection.disconnect()
            }
        }
    }
}
