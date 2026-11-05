# Coletor Dimensono

App Android (Flutter) enxuto que **só coleta e envia**: conecta à **Huawei Band 10**, ativa os
monitoramentos, sincroniza a cada N horas (padrão 2) e envia os dados brutos para um banco
**Supabase**. Não mostra gráficos nem resultados de saúde — só o status da conexão e um botão
**Atualizar dados**.

É uma versão simplificada do BandSono: reaproveita toda a camada nativa (conexão, protocolo,
serviço em segundo plano e envio ao Supabase) e troca a interface por três telas.

## Telas

1. **Parear** (primeira vez): encontra a pulseira e conecta. Na primeira conexão a Band 10 pode
   pedir confirmação na tela.
2. **Principal**: anel de status (conectada / sincronizando / desconectada), última sincronização,
   situação do envio ao servidor e o botão **Atualizar dados**.
3. **Ajustes**: pulseira (reconectar / esquecer), envio ao servidor (URL, chave anon/publishable,
   código do participante, testar conexão, enviar agora) e sincronização (intervalo, rodar em
   segundo plano, otimização de bateria).

## Como gerar o APK no GitHub

1. Crie um repositório e envie todo o conteúdo desta pasta (incluindo `.github/`).
2. O workflow **Build APK** roda a cada push na `main` (ou em *Actions → Build APK → Run workflow*).
3. Baixe o artefato **bandsono-apk** (contém `app-release.apk`, assinado com a chave de debug).

O workflow usa `flutter create .` para gerar os arquivos padrão do Android e fixa `minSdk = 26`.
O identificador do app é `br.dimensono.bandsono` e o nome exibido é **Coletor Dimensono**.

## Supabase

1. *Authentication → Sign In / Providers →* ative **Allow anonymous sign-ins**.
2. *SQL Editor* → rode [`supabase/schema.sql`](supabase/schema.sql).
3. No app: *Ajustes → Envio para o servidor* → URL do projeto, chave anon/publishable e código do
   participante → ative **Enviar dados brutos** → **Testar conexão**.

Nunca use a chave `service_role` no celular. Cada celular entra como usuário anônimo e só grava os
próprios dados (RLS). As tabelas e o detalhe do envio estão no README do schema e no documento de
especificação do conector.

## Primeiro uso

1. **Desvincule a Band 10 do app Huawei Saúde** (ou redefina a pulseira): ela só se conecta a um
   celular por vez.
2. Abra o Coletor → *Parear pulseira* → permita "Dispositivos próximos" e notificações.
3. Em *Ajustes*, libere a **otimização de bateria** (necessário para a sincronização em segundo
   plano ser confiável) e preencha o Supabase.

A partir daí o app conecta sozinho, aplica as configurações na pulseira, sincroniza a cada N horas
e envia os dados ao servidor — tudo em segundo plano.

## Arquitetura

| Parte | Onde |
|---|---|
| Interface (3 telas) | `lib/` (Dart): `main_screen`, `pair_screen`, `settings_screen` |
| Ponte | `BandChannel.java` + `MainActivity.kt` |
| Serviço / BLE / protocolo / banco / envio | `android/.../bandsono/` e `.../huawei/` (idêntico ao BandSono) |

Testes sem celular: `tools/protocol-harness/run.sh` (protocolo contra uma pulseira simulada).

## Licença

GNU AGPL-3.0-or-later. O código em `android/.../huawei/` deriva do
[Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge), também AGPL-3.0.
