const assert = require('node:assert/strict');
(async () => {
  const call = (route, key, body, method = body ? 'POST' : 'GET') => fetch('http://127.0.0.1:8793' + route, {
    method, headers: { Authorization: 'Bearer ' + key, 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  const ponte = 'ponte-teste';
  const plataforma = 'plataforma-teste';
  assert.equal((await call('/api/casa', ponte)).status, 401);
  assert.equal((await call('/api/ar?acao=desligar', plataforma, null, 'POST')).status, 503);
  assert.equal((await call('/api/ponte/sincronizar', ponte, { casa: { casa: 'Casa', dispositivos: [] } })).status, 200);
  assert.equal((await (await call('/api/casa', plataforma)).json()).casa, 'Casa');
  const pending = await call('/api/ar?acao=desligar', plataforma, null, 'POST');
  assert.equal(pending.status, 202);
  const { id } = await pending.json();
  assert.equal((await (await call('/api/ponte/sincronizar', ponte, {})).json()).comando.id, id);
  assert.equal((await (await call('/api/ponte/sincronizar', ponte, { resultado: { id, enviado: true } })).json()).comando, null);
  assert.equal((await (await call('/api/comando?id=' + id, plataforma)).json()).status, 'enviado');
  console.log('Autenticação, estado, entrega única e retorno OK');
})().catch(error => { console.error(error); process.exitCode = 1; });
