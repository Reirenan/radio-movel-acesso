const assert = require('node:assert/strict');
(async () => {
  const origin = process.env.PAINEL_TEST_ORIGIN || 'http://127.0.0.1:8793';
  const call = (route, key, body, method = body ? 'POST' : 'GET') => fetch(origin + route, {
    method, headers: { Authorization: 'Bearer ' + key, 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  const ponte = 'ponte-teste';
  const plataforma = 'plataforma-teste';
  assert.equal((await call('/api/casa', ponte)).status, 401);
  assert.equal((await call('/api/ar?acao=desligar', plataforma, null, 'POST')).status, 503);
  const casa = { casa: 'Casa', dispositivos: [], ar: { modos: ['Cool'], velocidades: ['low'] } };
  assert.equal((await call('/api/ponte/sincronizar', ponte, { casa })).status, 200);
  assert.equal((await (await call('/api/casa', plataforma)).json()).casa, 'Casa');
  const pending = await call('/api/ar?acao=desligar', plataforma, null, 'POST');
  assert.equal(pending.status, 202);
  const { id } = await pending.json();
  assert.equal((await (await call('/api/ponte/sincronizar', ponte, {})).json()).comando.id, id);
  assert.equal((await (await call('/api/ponte/sincronizar', ponte, { resultado: { id, enviado: true } })).json()).comando, null);
  assert.equal((await (await call('/api/comando?id=' + id, plataforma)).json()).status, 'enviado');
  assert.equal((await call('/api/ar?acao=velocidade&valor=invalid', plataforma, null, 'POST')).status, 400);
  const speed = await call('/api/ar?acao=velocidade&valor=low', plataforma, null, 'POST');
  assert.equal(speed.status, 202);
  const { id: speedId } = await speed.json();
  const { comando } = await (await call('/api/ponte/sincronizar', ponte, {})).json();
  assert.equal(comando.valor, 'low');
  assert.equal(comando.id, speedId);
  await call('/api/ponte/sincronizar', ponte, { resultado: { id: speedId, enviado: false, erro: 'Ajuste rejeitado.' } });
  const failed = await (await call('/api/comando?id=' + speedId, plataforma)).json();
  assert.equal(failed.status, 'sem_confirmacao');
  assert.equal(failed.erro, 'Ajuste rejeitado.');
  const temperature = await call('/api/ar?acao=ligar&valor=16', plataforma, null, 'POST');
  assert.equal(temperature.status, 202);
  const { id: temperatureId } = await temperature.json();
  const temperatureStatus = async () => (await call('/api/comando?id=' + temperatureId, plataforma)).json();
  await call('/api/ponte/sincronizar', ponte, {});
  await call('/api/ponte/sincronizar', ponte, { resultado: { id: temperatureId, enviado: false, erro: 'Ajuste rejeitado.' } });
  assert.equal((await temperatureStatus()).status, 'verificando');
  assert.equal((await call('/api/ar?acao=desligar', plataforma, null, 'POST')).status, 409);
  casa.ar.temperatura = 16;
  await call('/api/ponte/sincronizar', ponte, { casa });
  assert.equal((await temperatureStatus()).status, 'verificando');
  casa.ar.ligado = true;
  await call('/api/ponte/sincronizar', ponte, { casa });
  const confirmed = await temperatureStatus();
  assert.equal(confirmed.status, 'enviado');
  assert.equal(confirmed.erro, undefined);
  const sync = body => call('/api/ponte/sincronizar', ponte, { controlePorDispositivo: true, ...body });
  const groupPath = '/api/dispositivos?acao=desligar';
  assert.equal((await call(groupPath, plataforma, null, 'POST')).status, 409);
  casa.dispositivos = [
    { id: 'ar', nome: 'Ar', desliga: true, clima: { temperatura: 16 } },
    { id: 'leo', nome: 'Ar leonardo', desliga: true, clima: { temperatura: 22 } },
    { id: 'sensor', nome: 'Sensor' },
  ];
  await sync({ casa });
  const group = await call(groupPath, plataforma, null, 'POST');
  assert.equal(group.status, 202);
  const groupJob = await group.json();
  assert.deepEqual(groupJob.dispositivos, ['ar', 'leo']);
  assert.equal((await (await sync({})).json()).comando.id, groupJob.id);
  await sync({ resultado: { id: groupJob.id, enviado: false, dispositivos: [
    { id: 'ar', enviado: true }, { id: 'leo', enviado: false },
  ] } });
  const groupResult = await (await call('/api/comando?id=' + groupJob.id, plataforma)).json();
  assert.equal(groupResult.status, 'sem_confirmacao');
  assert.deepEqual(groupResult.resultados, [{ id: 'ar', enviado: true }, { id: 'leo', enviado: false }]);
  const individual = await call(groupPath + '&dispositivo=leo', plataforma, null, 'POST');
  assert.equal(individual.status, 202);
  assert.deepEqual((await individual.json()).dispositivos, ['leo']);
  assert.equal((await (await call('/api/ponte/sincronizar', ponte, {})).json()).comando, null);
  await sync({ casa });
  const leo = await call('/api/ar?acao=temperatura&valor=16&dispositivo=leo', plataforma, null, 'POST');
  assert.equal(leo.status, 202);
  const leoJob = await leo.json();
  assert.equal((await (await sync({})).json()).comando.dispositivo, 'leo');
  await sync({ casa, resultado: { id: leoJob.id, enviado: true } });
  assert.equal((await (await call('/api/comando?id=' + leoJob.id, plataforma)).json()).status, 'verificando');
  casa.dispositivos[1].clima.temperatura = 16;
  await sync({ casa });
  assert.equal((await (await call('/api/comando?id=' + leoJob.id, plataforma)).json()).status, 'enviado');
  console.log('Autenticação, estado, entrega única, opções e erros OK');
})().catch(error => { console.error(error); process.exitCode = 1; });
