const el = id => document.getElementById(id);
const terminal = {
  enviado: "Comando aceito pelo Google Home. Confira o último estado reportado.",
  sem_confirmacao: "Comando sem confirmação. Confira o aparelho.",
  expirado: "O comando expirou antes de ser entregue.",
};
let signedIn = false;
let pending = false;
let inventory;
let revision = 0;
const labels = { Cool: "Resfriar", Heat: "Aquecer", automatico: "Automático", Off: "Desligado",
  Dry: "Desumidificar", FanOnly: "Ventilar", auto: "Automática", low: "Baixa", mid: "Média", high: "Alta" };

function renderChoices(form, field, state, options, reported) {
  const select = el(field);
  const values = Array.isArray(options) ? options : [];
  el(form).hidden = values.length === 0;
  el(state).textContent = reported == null ? "" : "Reportado: " + (labels[reported] || reported);
  if (JSON.stringify([...select.options].map(option => option.value)) === JSON.stringify(["", ...values])) return;
  const previous = select.value;
  select.replaceChildren(new Option("Selecione", ""), ...values.map(value => new Option(labels[value] || value, value)));
  select.value = values.includes(previous) ? previous : values.includes(reported) ? reported : "";
}

async function api(path, options = {}) {
  const response = await fetch(path, { ...options, cache: "no-store" });
  const data = await response.json();
  if (!response.ok) throw Object.assign(new Error(data.erro || "Falha na comunicação."), { status: response.status });
  return data;
}

function showPanel(visible) {
  signedIn = visible;
  el("dashboard").hidden = !visible;
  el("logout").hidden = !visible;
  el("navigation").hidden = !visible;
  el("login").hidden = visible;
}

function controls() {
  const disabled = pending || !inventory?.ponteOnline;
  el("controlHint").textContent = pending ? "Comando em andamento" : inventory?.ponteOnline ? "" : "POCO offline";
  el("on").disabled = disabled || !inventory?.ar?.liga;
  el("on").textContent = inventory?.ar?.temperatura == null ? "Ligar" : "Ligar na temperatura selecionada";
  el("off").disabled = disabled || !inventory?.ar?.desliga;
  for (const [id, key] of [["temperature", "temperatura"], ["fan", "ventilador"]]) {
    el(id).hidden = inventory?.ar?.[key] == null;
    for (const input of el(id).elements) input.disabled = disabled;
  }
  for (const id of ["mode", "fanMode"]) {
    for (const input of el(id).elements) input.disabled = disabled;
  }
}

function render(data) {
  inventory = data;
  const devices = Array.isArray(data.dispositivos) ? data.dispositivos : [];
  const rooms = Array.isArray(data.ambientes) ? data.ambientes : [];
  const air = devices.find(device => device.nome === "Ar");
  el("house").textContent = data.casa || "Minha casa";
  el("updated").textContent = data.atualizadoEm
    ? "Atualizado em " + new Date(data.atualizadoEm).toLocaleString("pt-BR") : "Aguardando sincronização";
  el("device").textContent = air?.nome || "Ar-condicionado";
  el("room").textContent = air?.ambiente || "";
  el("setpoint").textContent = data.ar?.temperatura ?? "—";
  if (!el("degrees").value && data.ar?.temperatura != null) el("degrees").value = data.ar.temperatura;
  if (!el("speed").value && data.ar?.ventilador != null) el("speed").value = data.ar.ventilador;
  renderChoices("mode", "modeChoice", "modeState", data.ar?.modos, data.ar?.modo);
  renderChoices("fanMode", "fanChoice", "fanState", data.ar?.velocidades, data.ar?.velocidade);
  const power = data.ar?.ligado;
  el("power").textContent = power === true ? "Reportado ligado" : power === false ? "Reportado desligado" : "Estado indisponível";
  el("power").dataset.active = power === true;
  el("connection").textContent = data.ponteOnline ? "Online" : "Offline";
  el("connection").dataset.active = !!data.ponteOnline;
  el("connectionHint").textContent = data.ponteOnline ? "Recebendo dados do Painel Casa." : "Abra o Painel Casa no POCO e conecte-o à internet.";
  el("count").textContent = devices.length;
  el("devices").replaceChildren();
  for (const name of new Set([...rooms, ...devices.map(device => device.ambiente)])) {
    const heading = document.createElement("h3");
    heading.textContent = name;
    const list = document.createElement("ul");
    for (const device of devices.filter(item => item.ambiente === name)) {
      const item = document.createElement("li");
      item.textContent = device.nome;
      list.append(item);
    }
    el("devices").append(heading, list);
  }
  if (!devices.length && !rooms.length) el("devices").textContent = "Aguardando dados do Google Home.";
  controls();
}

async function refresh() {
  const current = revision;
  try {
    const data = await api("/api/casa");
    if (revision !== current) return;
    render(data);
    showPanel(true);
    el("pageError").textContent = "";
  } catch (error) {
    if (revision !== current) return;
    if (error.status === 401) showPanel(false);
    else {
      if (!signedIn) showPanel(false);
      inventory = null;
      controls();
      el("pageError").textContent = error.message;
    }
  }
}

async function send(action, value) {
  if (pending || !inventory?.ponteOnline) return;
  pending = true;
  controls();
  const current = revision;
  el("command").textContent = "Enviando comando…";
  try {
    const params = new URLSearchParams({ acao: action });
    if (value !== undefined) params.set("valor", value);
    const command = await api("/api/ar?" + params, { method: "POST" });
    for (let attempt = 0; attempt < 25 && current === revision; attempt++) {
      await new Promise(resolve => setTimeout(resolve, 2000));
      if (current !== revision) return;
      const result = await api("/api/comando?id=" + encodeURIComponent(command.id));
      if (result.status === "enviado" && (action === "temperatura" || (action === "ligar" && value !== undefined))) {
        el("command").textContent = `Temperatura reportada pelo Google Home: ${value} °C.`;
      } else if (result.status === "verificando") {
        el("command").textContent = "Aguardando o Google Home reportar a temperatura…";
      } else {
        el("command").textContent = result.erro || terminal[result.status] ||
          (result.status === "entregue" ? "Entregue ao POCO." : "Aguardando o POCO…");
      }
      if (terminal[result.status]) return;
    }
    if (current === revision) el("command").textContent = "Tempo de confirmação esgotado. Confira o último estado.";
  } catch (error) {
    if (current === revision) el("command").textContent = error.message;
  } finally {
    pending = false;
    if (current === revision) await refresh();
  }
}

el("login").addEventListener("submit", async event => {
  event.preventDefault();
  const button = event.currentTarget.querySelector("button");
  button.disabled = true;
  try {
    await api("/login", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ usuario: el("user").value.trim(), senha: el("password").value }),
    });
    el("password").value = "";
    el("loginError").textContent = "";
    revision++;
    await refresh();
  } catch (error) {
    el("loginError").textContent = error.message;
  } finally {
    button.disabled = false;
  }
});

el("logout").onclick = async () => {
  try {
    await api("/sair", { method: "POST" });
    revision++;
    inventory = null;
    el("command").textContent = "";
    showPanel(false);
  } catch (error) {
    el("pageError").textContent = error.message;
  }
};
el("refresh").onclick = refresh;
el("on").onclick = () => {
  if (el("temperature").hidden) return send("ligar");
  const degrees = el("degrees");
  if (degrees.reportValidity()) send("ligar", degrees.value);
};
el("off").onclick = () => send("desligar");
el("minus").onclick = () => el("degrees").stepDown();
el("plus").onclick = () => el("degrees").stepUp();
for (const [form, field, action] of [
  ["temperature", "degrees", "temperatura"], ["fan", "speed", "ventilador"],
  ["mode", "modeChoice", "modo"], ["fanMode", "fanChoice", "velocidade"],
]) {
  el(form).addEventListener("submit", event => {
    event.preventDefault();
    send(action, el(field).value);
  });
}
refresh();
setInterval(() => { if (signedIn) refresh(); }, 10000);
