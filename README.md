# APK do jogo

O GitHub monta o APK sozinho a cada envio: aba **Actions** → último "Gerar APK" → **SmashWorldGO-APK** (download), e publica em **Releases**.

## Endereço do jogo (escondido)

O endereço do servidor NÃO fica mais como texto no app nem no GitHub:

- `app/src/main/res/values/strings.xml` → `game_url` guarda o endereço **embaralhado**.
- Para trocar de endereço: `node embaralhar_url.js http://novo-endereco:8080/` e cole o resultado em `game_url`.
- Opcional (recomendado): no GitHub, **Settings → Secrets and variables → Actions → New repository secret**, nome `GAME_URL`, valor `http://seu-endereco:8080` (sem barra no fim). O "Gerar APK" usa esse segredo para baixar o jogo; sem ele, desembaralha o `game_url`.

## Anúncios (Unity)

Configurados no painel admin (aba 📺 Anúncios Unity → `game/anuncios.json`). O app suporta:

- **Com prêmio** (Rewarded): `SmashApp.mostrarAnuncio(id)`
- **Tela cheia** (Interstitial): ao abrir o app / a cada X minutos, e pelo jogo com `SmashApp.mostrarIntersticial(id)`
- **Banner** embaixo da tela: `SmashApp.mostrarBanner(placement)` / `SmashApp.esconderBanner()` (o jogo pede nos combates)
