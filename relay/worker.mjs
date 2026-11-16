const reply = (data, status = 200) =>
  Response.json(data, { status, headers: { "Cache-Control": "no-store" } });

const isTemperature = raw => /^\d+$/.test(raw ?? "") && Number(raw) >= 16 && Number(raw) <= 30;
const needsTemperature = command => command.acao === "temperatura" ||
  (command.acao === "ligar" && command.valor !== null);
const inProgress = command => ["pendente", "entregue", "verificando"].includes(command.status);
const commandStatus = (command, now) => now >= command.expiraEm && inProgress(command)
  ? command.status === "pendente" ? "expirado" : "sem_confirmacao"
  : command.status;

function deviceStates(snapshot) {
  return (snapshot?.dispositivos ?? []).map(device => {
    const air = device.clima ?? (device.nome === "Ar" ? snapshot.ar : null);
    return {
      id: device.id, nome: device.nome, ambiente: device.ambiente,
      ligado: device.ligado ?? air?.ligado ?? null,
      temperatura: air?.temperatura ?? null,
      modo: air?.modo ?? null,
      velocidade: air?.velocidade ?? air?.ventilador ?? null,
    };
  });
}

const encode = new TextEncoder();
const hex = bytes => Array.from(new Uint8Array(bytes), b => b.toString(16).padStart(2, "0")).join("");
const sessionName = "__Host-painel-casa";
const sessionSeconds = 12 * 60 * 60;

const sign = async (value, secret) => {
  const key = await crypto.subtle.importKey("raw", encode.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return hex(await crypto.subtle.sign("HMAC", key, encode.encode(value)));
};

const sessionCookie = (value, age) =>
  sessionName + "=" + value + "; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=" + age;

async function hasSession(request, env) {
  if (!env.PAINEL_SENHA) return false;
  const value = request.headers.get("Cookie")?.split(/;\s*/).find(part => part.startsWith(sessionName + "="));
  const match = value?.slice(sessionName.length + 1).match(/^(\d{10})\.([a-f0-9]{64})$/);
  return !!match && Number(match[1]) > Date.now() / 1000 &&
    await sign("sessao:" + match[1], env.PAINEL_SENHA) === match[2];
}

const read = async request => {
  const body = await request.text();
  if (body.length > 32768) throw new Error("Corpo excede o limite.");
  return JSON.parse(body);
};

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.protocol !== "https:" && url.hostname !== "127.0.0.1") {
      return reply({ erro: "Use HTTPS." }, 426);
    }
    const path = url.pathname;
    if (path === "/health" && request.method === "GET") return reply({ ok: true });
    if (request.method === "GET" && ["/", "/panel.css", "/panel.js", "/clima.webp"].includes(path)) {
      const asset = await env.ASSETS.fetch(request);
      const response = new Response(asset.body, asset);
      response.headers.set("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
      response.headers.set("X-Content-Type-Options", "nosniff");
      return response;
    }
    if (request.method === "POST" && ["/login", "/sair"].includes(path)) {
      if (request.headers.get("Origin") !== url.origin) return reply({ erro: "Origem inválida." }, 403);
      if (path === "/sair") {
        const response = reply({ ok: true });
        response.headers.set("Set-Cookie", sessionCookie("", 0));
        return response;
      }
      if (!env.PAINEL_SENHA) return reply({ erro: "Login indisponível." }, 503);
      let credentials;
      try { credentials = await read(request); } catch { return reply({ erro: "Dados inválidos." }, 400); }
      const password = credentials?.senha;
      if (credentials?.usuario !== env.PAINEL_USUARIO || typeof password !== "string" ||
        await sign(password, env.PAINEL_SENHA) !== await sign(env.PAINEL_SENHA, env.PAINEL_SENHA)) {
        return reply({ erro: "Usuário ou senha incorretos." }, 401);
      }
      const expiry = Math.floor(Date.now() / 1000) + sessionSeconds;
      const signature = await sign("sessao:" + expiry, env.PAINEL_SENHA);
      const response = reply({ ok: true });
      response.headers.set("Set-Cookie", sessionCookie(expiry + "." + signature, sessionSeconds));
      return response;
    }
    const key = path.startsWith("/api/ponte/") ? env.PONTE_TOKEN : env.PLATAFORMA_TOKEN;
    const bearer = !!key && request.headers.get("Authorization") === "Bearer " + key;
    if (!bearer) {
      const panelRoute = ["/api/casa", "/api/ar", "/api/dispositivos", "/api/comando", "/api/auditoria"].includes(path);
      if (!panelRoute || !await hasSession(request, env)) return reply({ erro: "Acesso não autorizado." }, 401);
      if (request.method === "POST" && request.headers.get("Origin") !== url.origin) {
        return reply({ erro: "Origem inválida." }, 403);
      }
    }
    const forwarded = new Request(request);
    forwarded.headers.set("X-Painel-Origem", bearer ? "api" : "web");
    return env.CASA.getByName("casa").fetch(forwarded);
  },
};

export class Casa {
  constructor(ctx) {
    this.sql = ctx.storage.sql;
    this.sql.exec("CREATE TABLE IF NOT EXISTS dados (chave TEXT PRIMARY KEY, valor TEXT NOT NULL)");
    this.sql.exec("CREATE TABLE IF NOT EXISTS auditoria (sequencia INTEGER PRIMARY KEY, comando TEXT UNIQUE, registro TEXT NOT NULL)");
  }

  get(key) {
    const row = [...this.sql.exec("SELECT valor FROM dados WHERE chave = ?", key)][0];
    return row ? JSON.parse(row.valor) : null;
  }

  put(key, value) {
    this.sql.exec("INSERT OR REPLACE INTO dados VALUES (?, ?)", key, JSON.stringify(value));
  }

  audit(event, commandId = null) {
    this.sql.exec(
      "INSERT INTO auditoria (comando, registro) VALUES (?, ?) ON CONFLICT(comando) DO UPDATE SET registro = excluded.registro",
      commandId, JSON.stringify(event),
    );
    this.sql.exec("DELETE FROM auditoria WHERE sequencia <= (SELECT MAX(sequencia) - 1000 FROM auditoria)");
  }

  saveCommand(command, now, details) {
    const row = [...this.sql.exec("SELECT registro FROM auditoria WHERE comando = ?", command.id)][0];
    const entry = details ?? (row && JSON.parse(row.registro));
    if (entry && (entry.status !== command.status || entry.erro !== command.erro)) {
      this.audit({ ...entry, ...command, atualizadoEm: now }, command.id);
    }
    this.put("comando", command);
  }

  async fetch(request) {
    const url = new URL(request.url);
    const route = request.method + " " + url.pathname;
    if (route === "GET /api/auditoria") {
      const now = Date.now();
      const eventos = [...this.sql.exec("SELECT registro FROM auditoria ORDER BY sequencia DESC LIMIT 100")].map(row => {
        const entry = JSON.parse(row.registro);
        if (entry.tipo !== "comando") return entry;
        const status = commandStatus(entry, now);
        return status === entry.status ? entry : { ...entry, status, atualizadoEm: entry.expiraEm };
      });
      return reply({ eventos });
    }
    if (route === "GET /api/casa") {
      const state = this.get("casa");
      const lastSeen = this.get("conexao");
      return reply({
        ...state?.data,
        atualizadoEm: state?.at ?? null,
        controlePorDispositivo: this.get("controlePorDispositivo") === true,
        ponteOnline: !!lastSeen && Date.now() - lastSeen < 45000,
      });
    }
    if (route === "POST /api/ar" || route === "POST /api/dispositivos") {
      const power = route === "POST /api/dispositivos";
      const action = url.searchParams.get("acao");
      const deviceId = url.searchParams.get("dispositivo");
      const raw = url.searchParams.get("valor");
      const named = action === "modo" || action === "velocidade";
      const value = named ? raw : /^\d+$/.test(raw ?? "") ? Number(raw) : null;
      const validTemperature = isTemperature(raw);
      const now = Date.now();
      const state = this.get("casa")?.data;
      const devices = state?.dispositivos ?? [];
      const target = devices.find(device => device.id === deviceId);
      const group = power && deviceId === null;
      const label = target?.nome ?? (group ? "Todos os dispositivos compatíveis" : deviceId === null ? "Ar" : "Dispositivo indisponível");
      const details = { tipo: "comando", em: now, origem: request.headers.get("X-Painel-Origem"), alvo: label, ambiente: target?.ambiente };
      const deny = (erro, status) => {
        this.audit({ ...details, acao: action?.slice(0, 80), valor: raw?.slice(0, 80), status: "recusado", erro, atualizadoEm: now });
        return reply({ erro }, status);
      };
      const valid = (action === "ligar" && (raw === null || validTemperature)) || action === "desligar" ||
        (action === "temperatura" && validTemperature) ||
        (action === "ventilador" && value !== null && value <= 100) ||
        (named && typeof value === "string" && value.length > 0 && value.length <= 80);
      if (!valid) {
        if (!power && (action === "temperatura" || (action === "ligar" && raw !== null))) {
          return deny("Use uma temperatura entre 16 e 30 °C.", 400);
        }
        return deny("Comando inválido.", 400);
      }
      if (power && (raw !== null || !["ligar", "desligar"].includes(action))) {
        return deny("Escolha ligar ou desligar.", 400);
      }
      const previous = this.get("comando");
      if (previous && inProgress(previous) && previous.expiraEm > now) {
        return deny("Já existe um comando em andamento.", 409);
      }
      if (now - (this.get("conexao") ?? 0) >= 45000) {
        return deny("POCO desconectado.", 503);
      }
      if ((power || deviceId !== null) && this.get("controlePorDispositivo") !== true) {
        return deny("Atualize o Painel Casa no POCO para controlar por dispositivo.", 409);
      }
      const air = deviceId === null ? state?.ar : target?.clima;
      if (!power && deviceId !== null && !air) {
        return deny("Ar-condicionado indisponível.", 400);
      }
      if (named) {
        const options = action === "modo" ? air?.modos : air?.velocidades;
        if (!Array.isArray(options) || !options.includes(value)) {
          return deny("Opção indisponível para este ar-condicionado.", 400);
        }
      }
      const command = { id: crypto.randomUUID(), acao: action, valor: value, status: "pendente", expiraEm: now + 45000 };
      if (power) {
        const capability = action === "ligar" ? "liga" : "desliga";
        command.dispositivos = (deviceId === null ? devices : [target])
          .filter(device => device?.id && device[capability] === true).map(device => device.id);
        if (!command.dispositivos.length) return deny("Nenhum dispositivo aceita esse comando.", 400);
        details.alvos = devices.filter(device => command.dispositivos.includes(device.id))
          .map(device => ({ id: device.id, nome: device.nome }));
      } else if (deviceId !== null) {
        command.dispositivo = deviceId;
      }
      this.saveCommand(command, now, details);
      return reply(command, 202);
    }
    if (route === "GET /api/comando") {
      const command = this.get("comando");
      if (command?.id !== url.searchParams.get("id") || !command) return reply({ erro: "Comando não encontrado." }, 404);
      return reply({ ...command, status: commandStatus(command, Date.now()) });
    }
    if (route === "POST /api/ponte/sincronizar") {
      let input;
      try {
        input = await read(request);
      } catch {
        return reply({ erro: "JSON inválido." }, 400);
      }
      if (!input || typeof input !== "object" || Array.isArray(input)) return reply({ erro: "JSON inválido." }, 400);
      const now = Date.now();
      this.put("conexao", now);
      this.put("controlePorDispositivo", input.controlePorDispositivo === true);
      const hasSnapshot = input.casa && typeof input.casa.casa === "string" && Array.isArray(input.casa.dispositivos);
      if (hasSnapshot) {
        const previousStates = deviceStates(this.get("casa")?.data);
        for (const current of deviceStates(input.casa)) {
          const previous = previousStates.find(device => current.id && device.id
            ? current.id === device.id
            : current.nome === device.nome && current.ambiente === device.ambiente);
          if (!previous) continue;
          const mudancas = ["ligado", "temperatura", "modo", "velocidade"]
            .filter(field => previous[field] !== current[field])
            .map(field => ({ campo: field, antes: previous[field], depois: current[field] }));
          if (mudancas.length) this.audit({ tipo: "estado", em: now, alvo: current.nome, ambiente: current.ambiente, mudancas });
        }
        this.put("casa", { data: input.casa, at: new Date(now).toISOString() });
      }
      const command = this.get("comando");
      const result = input.resultado;
      const hasResult = command?.status === "entregue" && command.id === result?.id && typeof result.enviado === "boolean";
      if (command && needsTemperature(command) &&
          (hasResult || (command.status === "verificando" && now < command.expiraEm))) {
        const air = hasSnapshot && (command.dispositivo
          ? input.casa.dispositivos.find(device => device.id === command.dispositivo)?.clima
          : input.casa.ar);
        const reached = air?.temperatura === command.valor && (command.acao !== "ligar" || air.ligado === true);
        const status = reached ? "enviado" : now < command.expiraEm ? "verificando" : "sem_confirmacao";
        const erro = reached ? undefined : "O Google Home ainda não reportou a temperatura solicitada.";
        this.saveCommand({ ...command, status, erro }, now);
      } else if (hasResult) {
        const erro = !result.enviado && typeof result.erro === "string" ? result.erro.slice(0, 240) : undefined;
        const resultados = command.dispositivos?.map(id => ({
          id, enviado: Array.isArray(result.dispositivos) && result.dispositivos.some(device => device?.id === id && device.enviado === true),
        }));
        const sent = resultados ? resultados.every(device => device.enviado) : result.enviado;
        this.saveCommand({ ...command, status: sent ? "enviado" : "sem_confirmacao", erro, resultados }, now);
      }
      if (command?.status === "pendente" && command.expiraEm > now) {
        if ((command.dispositivos || command.dispositivo) && input.controlePorDispositivo !== true) {
          this.saveCommand({ ...command, status: "sem_confirmacao", erro: "Atualize o Painel Casa no POCO." }, now);
          return reply({ comando: null });
        }
        this.saveCommand({ ...command, status: "entregue" }, now);
        return reply({ comando: command });
      }
      return reply({ comando: null });
    }
    return reply({ erro: "Rota inexistente." }, 404);
  }
}
