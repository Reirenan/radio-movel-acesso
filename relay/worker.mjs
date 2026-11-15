const reply = (data, status = 200) =>
  Response.json(data, { status, headers: { "Cache-Control": "no-store" } });

const isTemperature = raw => /^\d+$/.test(raw ?? "") && Number(raw) >= 16 && Number(raw) <= 30;

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
    if (!key || request.headers.get("Authorization") !== "Bearer " + key) {
      const panelRoute = ["/api/casa", "/api/ar", "/api/comando"].includes(path);
      if (!panelRoute || !await hasSession(request, env)) return reply({ erro: "Acesso não autorizado." }, 401);
      if (request.method === "POST" && request.headers.get("Origin") !== url.origin) {
        return reply({ erro: "Origem inválida." }, 403);
      }
    }
    if (request.method === "POST" && path === "/api/ar") {
      const action = url.searchParams.get("acao");
      const value = url.searchParams.get("valor");
      if ((action === "temperatura" || (action === "ligar" && value !== null)) && !isTemperature(value)) {
        return reply({ erro: "Use uma temperatura entre 16 e 30 °C." }, 400);
      }
    }
    return env.CASA.getByName("casa").fetch(request);
  },
};

export class Casa {
  constructor(ctx) {
    this.sql = ctx.storage.sql;
    this.sql.exec("CREATE TABLE IF NOT EXISTS dados (chave TEXT PRIMARY KEY, valor TEXT NOT NULL)");
  }

  get(key) {
    const row = [...this.sql.exec("SELECT valor FROM dados WHERE chave = ?", key)][0];
    return row ? JSON.parse(row.valor) : null;
  }

  put(key, value) {
    this.sql.exec("INSERT OR REPLACE INTO dados VALUES (?, ?)", key, JSON.stringify(value));
  }

  async fetch(request) {
    const url = new URL(request.url);
    const route = request.method + " " + url.pathname;
    if (route === "GET /api/casa") {
      const state = this.get("casa");
      const lastSeen = this.get("conexao");
      return reply({
        ...state?.data,
        atualizadoEm: state?.at ?? null,
        ponteOnline: !!lastSeen && Date.now() - lastSeen < 45000,
      });
    }
    if (route === "POST /api/ar") {
      const action = url.searchParams.get("acao");
      const raw = url.searchParams.get("valor");
      const value = /^\d+$/.test(raw ?? "") ? Number(raw) : null;
      const validTemperature = isTemperature(raw);
      const valid = (action === "ligar" && (raw === null || validTemperature)) || action === "desligar" ||
        (action === "temperatura" && validTemperature) ||
        (action === "ventilador" && value !== null && value <= 100);
      if (!valid) return reply({ erro: "Comando inválido." }, 400);
      const now = Date.now();
      const previous = this.get("comando");
      if (previous && ["pendente", "entregue"].includes(previous.status) && previous.expiraEm > now) {
        return reply({ erro: "Já existe um comando em andamento." }, 409);
      }
      if (now - (this.get("conexao") ?? 0) >= 45000) {
        return reply({ erro: "POCO desconectado." }, 503);
      }
      const command = { id: crypto.randomUUID(), acao: action, valor: value, status: "pendente", expiraEm: now + 45000 };
      this.put("comando", command);
      return reply(command, 202);
    }
    if (route === "GET /api/comando") {
      const command = this.get("comando");
      if (command?.id !== url.searchParams.get("id") || !command) return reply({ erro: "Comando não encontrado." }, 404);
      if (Date.now() >= command.expiraEm && ["pendente", "entregue"].includes(command.status)) {
        return reply({ ...command, status: command.status === "entregue" ? "sem_confirmacao" : "expirado" });
      }
      return reply(command);
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
      if (input.casa && typeof input.casa.casa === "string" && Array.isArray(input.casa.dispositivos)) {
        this.put("casa", { data: input.casa, at: new Date(now).toISOString() });
      }
      const command = this.get("comando");
      const result = input.resultado;
      if (command?.status === "entregue" && command.id === result?.id && typeof result.enviado === "boolean") {
        this.put("comando", { ...command, status: result.enviado ? "enviado" : "sem_confirmacao" });
      }
      if (command?.status === "pendente" && command.expiraEm > now) {
        this.put("comando", { ...command, status: "entregue" });
        return reply({ comando: command });
      }
      return reply({ comando: null });
    }
    return reply({ erro: "Rota inexistente." }, 404);
  }
}
