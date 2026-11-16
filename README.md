# radio-movel-acesso

App Android que hospeda um painel na rede Wi-Fi local e consulta as Home APIs do Google. O painel lista os ambientes e os dispositivos autorizados e oferece os controles disponíveis para o ar-condicionado chamado **Ar**.

O painel web está no [Painel Casa](https://painel-casa.renan-painel-casa.workers.dev/). Ele usa a API por HTTPS, mostra a conexão do POCO, separa o estado reportado do resultado dos comandos e agrupa os dispositivos por cômodo.

## Painel web

A interface fica em `relay/public/`. Para hospedar uma instância, publique `relay/` pelo Wrangler e cadastre os segredos `PAINEL_USUARIO` e `PAINEL_SENHA` com `npx wrangler secret put`. Use uma senha aleatória longa. A sessão expira em 12 horas.

Para rodar localmente com credenciais de teste, execute `npx wrangler dev --port 8793 --var PAINEL_USUARIO:teste --var PAINEL_SENHA:senha-local --var PONTE_TOKEN:ponte-teste --var PLATAFORMA_TOKEN:plataforma-teste` dentro de `relay/`.

## Compilar

1. Baixe o [SDK das Home APIs](https://developers.home.google.com/apis/android/sdk) com a conta Google. Extraia o ZIP do SDK 1.11.1 em `home-sdk/` ou aponte `GOOGLE_HOME_SDK_REPO` para a pasta que contém `com/google/android/gms`.
2. Com JDK 17 ou 21 e Android SDK 36, rode `gradlew.bat :app:assembleDebug :app:signingReport`. A configuração debug usa a chave local `~/.android/painel-casa-debug.keystore`.
3. No Google Cloud, crie um [cliente OAuth do tipo Android](https://developers.home.google.com/apis/android/oauth) para `br.com.renan.painelhome` com o SHA-1 exibido no relatório. Adicione a conta do Google Home como usuária de teste na tela de consentimento.
4. Instale o APK com `adb -d install -r app/build/outputs/apk/debug/app-debug.apk`.

## Usar

Abra **Painel Casa** no POCO, autorize a casa no Google e use o endereço completo exibido no app em um navegador na mesma rede Wi-Fi. Mantenha o app aberto durante o uso. O endereço contém um token temporário. O servidor usa HTTP na rede local.

## API HTTPS

Publique o Worker em `relay/` com Wrangler e configure dois segredos independentes, `PONTE_TOKEN` para o POCO e `PLATAFORMA_TOKEN` para o backend da plataforma. No app Android, informe a origem HTTPS do Worker e o token do POCO. Toque em **Autorizar Google Home e abrir painel** e mantenha o app aberto. Os campos são salvos no celular.

No diretório `relay/`, use `npx wrangler login` e `npx wrangler deploy`. Cadastre cada segredo com `npx wrangler secret put PONTE_TOKEN` e `npx wrangler secret put PLATAFORMA_TOKEN`. Use valores aleatórios independentes de pelo menos 32 caracteres. A publicação mostra o endereço fixo `workers.dev`. A rota pública `GET /health` permite verificar a publicação.

O backend da plataforma deve usar `Authorization: Bearer <PLATAFORMA_TOKEN>` nas chamadas abaixo. Guarde esse token no servidor.

| Método | Caminho | Resultado |
|---|---|---|
| GET | `/api/casa` | Inventário, último estado recebido, data da atualização e conexão do POCO |
| POST | `/api/dispositivos?acao=ligar` | Liga todos os dispositivos que oferecem esse comando |
| POST | `/api/dispositivos?acao=desligar&dispositivo=<id>` | Desliga um dispositivo pelo ID retornado no inventário |
| POST | `/api/ar?acao=ligar&valor=23` | Liga o ar e ajusta para 23 °C. O valor é opcional |
| POST | `/api/ar?acao=desligar` | Desliga o ar |
| POST | `/api/ar?acao=temperatura&valor=23` | Ajusta a temperatura entre 16 e 30 °C |
| POST | `/api/ar?acao=modo&valor=Cool` | Seleciona uma opção de `ar.modos` |
| POST | `/api/ar?acao=velocidade&valor=low` | Seleciona uma opção de `ar.velocidades` |
| GET | `/api/comando?id=<id>` | Retorna o resultado do comando mais recente |

O POST aceito devolve o ID com HTTP 202. As rotas de climatização aceitam `dispositivo=<id>` para escolher o aparelho. O resultado de um comando de energia inclui `resultados` por dispositivo. O estado em `/api/casa` vem do Google Home e pode demorar para refletir a mudança física.
