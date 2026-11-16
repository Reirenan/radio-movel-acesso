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
import com.google.home.HomeException
import com.google.home.google.ExtendedFanControl
import com.google.home.google.ExtendedThermostat
import com.google.home.google.ExtendedThermostatTrait.ExtendedThermostatMode
import com.google.home.google.GoogleDeviceTypeRegistry
import com.google.home.matter.standard.FanControl
import com.google.home.matter.standard.OnOff
import com.google.home.matter.standard.RoomAirConditionerDevice
import com.google.home.matter.standard.StandardDeviceTypeRegistry
import com.google.home.matter.standard.Thermostat
import com.google.home.matter.standard.ThermostatTrait.SetpointRaiseLowerModeEnum
import com.google.home.matter.standard.ThermostatTrait.SystemModeEnum
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
                        traits = listOf(OnOff, Thermostat, FanControl, ExtendedThermostat, ExtendedFanControl),
                        types = StandardDeviceTypeRegistry.types + GoogleDeviceTypeRegistry.types,
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
        val access = Button(this).apply { text = "Gerenciar acesso ao Google Home" }
        fun connectPanel(interactive: Boolean, manageAccess: Boolean = false) {
            if (!connect.isEnabled) return
            connect.isEnabled = false
            access.isEnabled = false
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
                    if (manageAccess || permission != PermissionsState.GRANTED) {
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
                    access.isEnabled = true
                }
            }
        }
        connect.setOnClickListener { connectPanel(interactive = true) }
        access.setOnClickListener { connectPanel(interactive = true, manageAccess = true) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 72, 36, 36)
            addView(endpoint)
            addView(relayKey)
            addView(connect)
            addView(access)
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
                    withTimeout(25_000) {
                        lock.withLock {
                            when {
                                session.method == Method.GET && session.uri == "/api/casa" -> inventory()
                                session.method == Method.POST && session.uri == "/api/ar" -> command(
                                    session.parameters["acao"]?.singleOrNull(),
                                    session.parameters["valor"]?.singleOrNull(),
                                )
                                else -> throw IllegalArgumentException("Rota inválida.")
                            }
                        }
                    }
                }
                json(Response.Status.OK, payload)
            } catch (exception: IllegalArgumentException) {
                json(Response.Status.BAD_REQUEST, JSONObject().put("erro", exception.message))
            } catch (exception: IllegalStateException) {
                json(Response.Status.SERVICE_UNAVAILABLE, JSONObject().put("erro", exception.message))
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
            val structures = selectedStructures()
            val rooms = structures.flatMap { it.rooms().list() }.associate { it.id to it.name }
            val devices = structures.flatMap { it.devices().list() }.distinctBy { it.id }
            val entries = devices.sortedBy { it.name }.map { device ->
                val power = powerTraits(device)
                val states = power.map { it.onOff }.distinct()
                JSONObject().put("id", device.id.toString())
                    .put("nome", device.name)
                    .put("ambiente", rooms[device.roomId] ?: "Sem ambiente")
                    .put("ligado", states.singleOrNull() ?: JSONObject.NULL)
                    .put("liga", power.any { it.supports(OnOff.Command.On) })
                    .put("desliga", power.any { it.supports(OnOff.Command.Off) })
                    .put("clima", climate(device) ?: JSONObject.NULL)
            }
            val air = entries.singleOrNull { it.getString("nome").equals("Ar", ignoreCase = true) }
            return JSONObject()
                .put("casa", structures.joinToString(" · ") { it.name })
                .put("ambientes", JSONArray(rooms.values.sorted()))
                .put("dispositivos", JSONArray(entries))
                .put("ar", air?.optJSONObject("clima") ?: JSONObject.NULL)
        }

        private suspend fun powerTraits(device: HomeDevice): List<OnOff> =
            device.types().first().flatMap { it.traits().filterIsInstance<OnOff>() }.distinct()

        private suspend fun climate(device: HomeDevice): JSONObject? {
            val type = device.typeOrNull(RoomAirConditionerDevice).first() ?: return null
            val traits = type.standardTraits
            val onOff = traits.onOff
            val thermostat = traits.thermostat
            val fan = traits.fanControl
            val ext = type.googleTraits.extendedThermostat
            val extFan = type.googleTraits.extendedFanControl
            val heating = thermostat?.systemMode == SystemModeEnum.Heat
            val target = if (heating) thermostat.occupiedHeatingSetpoint else thermostat?.occupiedCoolingSetpoint
            return JSONObject()
                .put("ligado", onOff?.onOff ?: JSONObject.NULL)
                .put("liga", onOff?.supports(OnOff.Command.On) == true)
                .put("desliga", onOff?.supports(OnOff.Command.Off) == true)
                .put("temperatura", target?.div(100.0) ?: JSONObject.NULL)
                .put("ventilador", fan?.percentSetting?.toInt() ?: JSONObject.NULL)
                .put("velocidade", extFan?.customFanMode ?: JSONObject.NULL)
                .put("velocidades", JSONArray(extFan?.customFanModes?.speeds.orEmpty().map { it.speedName }))
                .put("modo", if (ext?.extendedSystemMode == ExtendedThermostatMode.SmarthomeAuto) "automatico"
                    else thermostat?.systemMode?.name ?: JSONObject.NULL)
                .put("modos", JSONArray(operatingModes(thermostat, ext)))
        }

        private fun operatingModes(thermostat: Thermostat?, extended: ExtendedThermostat?): List<String> = buildList {
            if (thermostat?.systemMode != null) {
                if (thermostat.featureMap.cooling) add("Cool")
                if (thermostat.featureMap.heating) add("Heat")
            }
            if (ExtendedThermostatMode.SmarthomeAuto in extended?.extendedSupportedModes.orEmpty()) add("automatico")
        }

        private suspend fun command(action: String?, raw: String?, deviceId: String? = null): JSONObject {
            val value = raw?.toIntOrNull()
            val device = selectedStructures().flatMap { it.devices().list() }.distinctBy { it.id }
                .singleOrNull { if (deviceId == null) it.name.equals("Ar", ignoreCase = true) else it.id.toString() == deviceId }
                ?: throw IllegalArgumentException("O ar-condicionado não está disponível no Google Home.")
            val type = device.typeOrNull(RoomAirConditionerDevice).first()
                ?: throw IllegalArgumentException("O Google Home não disponibilizou controles de ar-condicionado para Ar.")
            val traits = type.standardTraits
            val extended = type.googleTraits.extendedThermostat
            when (action) {
                "ligar" -> {
                    val trait = traits.onOff
                    require(trait?.supports(OnOff.Command.On) == true) { "O aparelho não oferece o comando de ligar." }
                    val thermostat = traits.thermostat
                    if (raw != null) {
                        require(value != null && value in 16..30) { "Use uma temperatura entre 16 e 30 °C." }
                        require(thermostat != null) { "O ajuste de temperatura não está disponível." }
                    }
                    if (trait.onOff != true) trait.on()
                    if (value != null) {
                        try {
                            setTemperature(device, checkNotNull(thermostat), extended, value)
                        } catch (exception: IllegalStateException) {
                            throw IllegalStateException("O comando de ligar foi aceito. ${exception.message}", exception)
                        }
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
                    require(trait != null) { "O ajuste de temperatura não está disponível." }
                    setTemperature(device, trait, extended, value)
                }
                "ventilador" -> {
                    require(value != null && value in 0..100) { "Use uma velocidade entre 0 e 100%." }
                    val trait = traits.fanControl
                    require(trait?.percentSetting != null) { "O ajuste de velocidade em porcentagem não está disponível." }
                    trait.update { setPercentSetting(value.toUByte()) }
                }
                "velocidade" -> {
                    val fan = type.googleTraits.extendedFanControl
                    require(raw != null && fan != null && fan.customFanModes?.speeds?.any { it.speedName == raw } == true) {
                        "Escolha uma velocidade oferecida pelo aparelho."
                    }
                    if (fan.customFanMode != raw) {
                        try {
                            fan.update { setCustomFanMode(raw) }
                        } catch (exception: HomeException) {
                            throw IllegalStateException("O Google Home rejeitou o ajuste de velocidade.", exception)
                        }
                        confirm("velocidade solicitada") {
                            device.typeOrNull(RoomAirConditionerDevice).first()?.googleTraits?.extendedFanControl?.customFanMode == raw
                        }
                    }
                }
                "modo" -> {
                    val thermostat = traits.thermostat
                    require(raw != null && raw in operatingModes(thermostat, extended)) { "Escolha um modo oferecido pelo aparelho." }
                    try {
                        if (raw == "automatico") {
                            checkNotNull(extended).update { setExtendedSystemMode(ExtendedThermostatMode.SmarthomeAuto) }
                        } else {
                            checkNotNull(thermostat).update { setSystemMode(SystemModeEnum.valueOf(raw)) }
                        }
                    } catch (exception: HomeException) {
                        throw IllegalStateException("O Google Home rejeitou a mudança de modo.", exception)
                    }
                    confirm("mudança de modo") { climate(device)?.optString("modo") == raw }
                }
                else -> throw IllegalArgumentException("Comando inválido.")
            }
            return JSONObject().put("enviado", true)
        }

        private suspend fun power(action: String, ids: JSONArray): JSONObject = coroutineScope {
            require(action == "ligar" || action == "desligar") { "Comando inválido." }
            val devices = selectedStructures().flatMap { it.devices().list() }.associateBy { it.id.toString() }
            val turnOn = action == "ligar"
            val results = (0 until ids.length()).map { index ->
                val id = ids.getString(index)
                async {
                    val device = devices[id]
                    val accepted = withTimeoutOrNull(10_000) {
                        try {
                            val traits = device?.let { powerTraits(it) }.orEmpty().filter {
                                if (turnOn) it.supports(OnOff.Command.On) else it.supports(OnOff.Command.Off)
                            }
                            val applied = traits.map { trait ->
                                try {
                                    if (turnOn) trait.on() else trait.off()
                                    true
                                } catch (exception: HomeException) {
                                    Log.w("PainelCasa", "Falha no comando de energia", exception)
                                    false
                                }
                            }
                            applied.isNotEmpty() && applied.all { it }
                        } catch (exception: HomeException) {
                            Log.w("PainelCasa", "Falha ao ler dispositivo", exception)
                            false
                        }
                    } == true
                    JSONObject().put("id", id).put("enviado", accepted)
                }
            }.awaitAll()
            JSONObject().put("enviado", results.all { it.getBoolean("enviado") })
                .put("dispositivos", JSONArray(results))
        }

        private suspend fun setTemperature(device: HomeDevice, thermostat: Thermostat, extended: ExtendedThermostat?, value: Int) {
            val target = value * 100
            val heating = thermostat.systemMode == SystemModeEnum.Heat
            fun setpoint(trait: Thermostat) = if (heating) trait.occupiedHeatingSetpoint else trait.occupiedCoolingSetpoint
            suspend fun reported(expected: Int): Boolean = waitForState {
                device.typeOrNull(RoomAirConditionerDevice).first()?.standardTraits?.thermostat
                    ?.let(::setpoint)?.toInt() == expected
            }
            suspend fun setAbsolute() = thermostat.update {
                if (heating) setOccupiedHeatingSetpoint(target.toShort())
                else setOccupiedCoolingSetpoint(target.toShort())
            }
            var current = setpoint(thermostat)?.toInt() ?: error("O aparelho não reportou uma temperatura ajustável.")
            if (current == target) return
            try {
                if (extended?.supports(ExtendedThermostat.Command.SetTemperatureAndMode) == true) {
                    extended.setTemperatureAndMode(null, target.toShort().takeIf { heating }, target.toShort().takeUnless { heating }, null)
                } else if (thermostat.supports(Thermostat.Command.SetpointRaiseLower)) {
                    val mode = if (heating) SetpointRaiseLowerModeEnum.Heat else SetpointRaiseLowerModeEnum.Cool
                    try {
                        while (current != target) {
                            val delta = ((target - current) / 10).coerceIn(-120, 120)
                            check(delta != 0) { "O aparelho não atingiu a temperatura solicitada." }
                            val next = current + delta * 10
                            try {
                                thermostat.setpointRaiseLower(mode, delta.toByte())
                            } catch (exception: HomeException) {
                                if (!reported(next)) throw exception
                                current = next
                                continue
                            }
                            check(reported(next)) { "O Google Home não reportou a temperatura solicitada." }
                            current = next
                        }
                        return
                    } catch (exception: HomeException) {
                        Log.i("PainelCasa", "Ajuste relativo rejeitado. Tentando definir a temperatura diretamente.", exception)
                        setAbsolute()
                    }
                } else {
                    setAbsolute()
                }
            } catch (exception: HomeException) {
                Log.w("PainelCasa", "O comando de temperatura retornou erro. Conferindo o estado reportado.", exception)
            }
            check(reported(target)) { "O Google Home não reportou a temperatura de $value °C." }
        }

        private suspend fun confirm(label: String, matches: suspend () -> Boolean) {
            check(waitForState(matches)) { "O Google Home não confirmou a $label." }
        }

        private suspend fun waitForState(matches: suspend () -> Boolean): Boolean {
            repeat(6) {
                if (matches()) return true
                delay(1_500)
            }
            return matches()
        }

        private suspend fun selectedStructures() = home.structures().first { structures ->
            Log.i("PainelCasa", "Casas retornadas pelo Google Home: ${structures.size}")
            structures.isNotEmpty()
        }

        suspend fun sync(remote: URI, key: String) {
            var result: JSONObject? = null
            while (true) {
                try {
                    val snapshot = kotlinx.coroutines.withTimeoutOrNull(10_000) { inventory() }
                    val payload = JSONObject().put("casa", snapshot).put("resultado", result)
                        .put("controlePorDispositivo", true)
                    val response = withContext(Dispatchers.IO) { syncRequest(remote, key, payload) }
                    result = null
                    val queued = response.optJSONObject("comando")
                    if (queued != null) {
                        val outcome = JSONObject().put("id", queued.getString("id"))
                        val sent = try {
                            kotlinx.coroutines.withTimeoutOrNull(25_000) {
                                val value = if (queued.isNull("valor")) null else queued.getString("valor")
                                val action = queued.getString("acao")
                                val response = queued.optJSONArray("dispositivos")?.let { power(action, it) }
                                    ?: command(action, value, queued.optString("dispositivo").takeIf { it.isNotBlank() })
                                response.optJSONArray("dispositivos")?.let { outcome.put("dispositivos", it) }
                                response.getBoolean("enviado")
                            } == true
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            Log.w("PainelCasa", "Falha no comando remoto", exception)
                            if (exception is IllegalStateException || exception is IllegalArgumentException) {
                                outcome.put("erro", exception.message)
                            }
                            false
                        }
                        result = outcome.put("enviado", sent)
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
