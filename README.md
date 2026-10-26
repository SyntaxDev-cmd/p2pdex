# NovaTV — App IPTV/P2P para Android (TV + celular) + Painel PHP com revendas

**APK pronto para baixar:** [NovaTV.apk (sempre a última versão)](https://github.com/SyntaxDev-cmd/p2pdex/releases/download/latest/NovaTV.apk) · [todas as versões](https://github.com/SyntaxDev-cmd/p2pdex/releases). O APK é gerado e publicado automaticamente a cada atualização da branch `main`.

- **App Android** (`android/`): um único APK para TV Box, Android TV e celular (Android 5.0+). Visual no estilo dos apps de TV Box: abas no topo, mosaico de destaques, lista de canais com prévia e guia de programação.
- **Painel** (`panel/`): PHP + SQLite, sem MySQL. O administrador muda tudo e os revendedores gerenciam as próprias DNS/P2P e aparelhos.

---

## O app

| Área | O que faz |
|---|---|
| Perfis (estilo Netflix) | Até 5 perfis com avatar e cor; **perfil infantil** que mostra só conteúdo kids; **PIN por perfil**; favoritos, "continuar assistindo" e bloqueios separados por perfil |
| Início em mosaico | Destaque grande que gira sozinho (com fundo do TMDB) + dois cartazes + atalhos coloridos (**Gênero, Ano, No cinema/Em alta, Todos**); fileiras "Continuar assistindo", "Em alta esta semana", favoritos e lançamentos |
| TV ao vivo | Categorias, canais numerados, prévia com vídeo, EPG agora/a seguir (atualiza a cada minuto), favoritos, recentes |
| Guia de programação | Grade do dia por canal (botão **Guia** ou tecla GUIDE): ao vivo, próximos e detalhes |
| JOGOS do dia | Cartões com horário, times e escudos, campeonato, **AO VIVO**/contagem regressiva e canais. **Tocar abre direto o canal** que transmite (o app procura na lista do cliente e prefere HD/FHD). Filtros por campeonato e "ao vivo"; canais esportivos na mesma aba |
| Filmes / Séries | "Início" em mosaico + categorias + grade; detalhes com **TMDB**: sinopse pt-BR, fundo em alta, nota, gêneros, duração, **classificação indicativa**, **elenco com fotos** e **trailer** |
| Kids / Anime / Favoritos | Separação automática por palavras-chave; aba de favoritos (canais, filmes e séries) |
| Player | Troca de canal (↑/↓), lista sobreposta (OK), formato da tela, áudio/legenda, continuar de onde parou, próximo episódio, reconexão e troca de DNS automáticas |
| Configurações | Player interno ou **externo** (VLC/MX), **formato da tela**, formato do ao vivo (TS/HLS), buffer, próximo episódio automático, **controle parental** (PIN e categorias bloqueadas por perfil), limpar histórico/cache |
| Login responsivo | TV e tablet (marca + formulário), celular deitado (compacto) e celular em pé; mostrar/ocultar senha, código de revenda |
| Celular | Barra de navegação inferior e layouts próprios para a tela em pé |

### Trocar o painel e as imagens sem recompilar (MT Manager / APK Editor)

No APK, abra `assets/`:

- `assets/config.json`:
  ```json
  {
    "panel_url": "http://p2pwmax.dexdown.shop",
    "panel_key": "",
    "reseller_code": "",
    "tmdb_api_key": "",
    "tmdb_read_token": "",
    "tmdb_language": "pt-BR"
  }
  ```
  - `panel_url`: endereço do painel.
  - `reseller_code`: gera um APK exclusivo para uma revenda.
  - `panel_key`: preencha só se ativar "Exigir chave" no painel.
- `assets/images/logo.png`: logo usada quando o painel não tem logo.
- `assets/images/background.jpg` (opcional): fundo, 1920×1080.
- Ícone do app: `res/mipmap-*/ic_launcher.png`. Banner da Android TV: `res/drawable-xhdpi/banner.png` (320×180).

Depois de editar, o MT Manager assina o APK de novo.

---

## O painel

### Administrador
- **Aparência**: logo, fundo, temas prontos e todas as cores, com prévia ao vivo.
- **Abas e ícones**: ligar, desligar, renomear e trocar o ícone de cada aba. Tamanho ideal: **PNG 128×128 px transparente**, ícone branco ou de uma cor só.
- **Servidores / DNS / P2P / M3U** com teste de conexão por DNS.
- **Revendedores**: usuário e senha, **código**, **limite de aparelhos**, limite de servidores, validade e WhatsApp de suporte.
- **Jogos do dia e TMDB**:
  - Jogos: URL da API (pode ser trocada a qualquer momento), cache, detecção automática dos campos, mapeamento manual e **prévia dos jogos reconhecidos**.
  - TMDB: chave, token e idioma, com botão de teste.
- **Kids e controle parental**, **Destaques**, **Avisos**, **Dispositivos**, **Sistema** (manutenção, atualização do APK, backup).
- **Segurança e registros**: proteções ativas, tentativas de login, limite de requisições da API, exigir chave ou código de revenda, registro de atividades.

### Revendedor (entra no mesmo painel com o próprio usuário)
- Vê e gerencia **só os servidores dele** (até o limite definido pelo admin).
- Vê **só os aparelhos dele**, com medidor de vagas. Pode bloquear aparelhos e remover os que não usa mais para liberar vagas.
- **Código da revenda**: o cliente digita no login, ou ele já vem no APK da revenda (`reseller_code`).
- Quando o limite é atingido, o aparelho novo vê "Limite de aparelhos da revenda atingido".

### Segurança
- Senhas em bcrypt.
- Bloqueio após tentativas erradas (por IP e por usuário) e campo-armadilha contra robôs.
- Sessão HttpOnly/SameSite=Strict, com expiração por inatividade e vinculada ao navegador.
- CSRF em todos os formulários.
- Cabeçalhos CSP, X-Frame-Options e HSTS.
- Upload só de imagem real e sem execução de código.
- Testes de servidor e de API não acessam a rede interna (anti-SSRF).
- Banco com nome aleatório em pasta bloqueada.
- Limite de requisições por IP na API e registro de auditoria.

---

## Instalar o painel no subdomínio (Hostinger)

1. No hPanel, crie o subdomínio `p2pwmax.dexdown.shop`. A Hostinger cria a pasta dele, algo como `domains/dexdown.shop/public_html/p2pwmax`.
2. Em **Gerenciador de Arquivos**, envie **o conteúdo** da pasta `panel/` para a pasta do subdomínio. O `index.php` deve ficar na raiz dela.
3. Em **Avançado → Configuração PHP**, use **PHP 8.1+**. As extensões `pdo_sqlite` e `curl` já vêm ativas.
4. Em **Segurança → SSL**, ative o SSL grátis e, se quiser, troque o `panel_url` do APK para `https://`.
5. Abra `http://p2pwmax.dexdown.shop/`. A instalação cria o administrador.
6. Faça os primeiros ajustes:
   - **Servidores**: cadastre sua DNS/P2P.
   - **Jogos do dia e TMDB**: cole a chave do TMDB e clique em "Salvar e testar". A URL de jogos já vem preenchida; "Salvar e testar agora" mostra os jogos reconhecidos.
   - **Revendedores**: crie as revendas.

> As pastas `data/` e `uploads/` precisam de permissão de escrita (755 na Hostinger).

---

## Gerar o APK

Automático: todo push na `main` gera o APK e publica em **Releases** (`latest` e `vN`).

Secrets opcionais em *Settings → Secrets and variables → Actions*:
- `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` e `KEY_PASSWORD`: assinatura própria. **Recomendado**, porque sem ela cada build usa uma chave diferente e o cliente precisa desinstalar para atualizar.
- `PANEL_URL`: muda o endereço padrão embutido no build.

O número da versão (`versionCode`) é o número do build. Para avisar os clientes, coloque esse número e o link do APK em **Painel → Sistema e app**.

## Controle remoto

| Tecla | Ação |
|---|---|
| ↑ ↓ no player ao vivo | Canal anterior / próximo |
| OK no player ao vivo | Lista de canais |
| ← → no player ao vivo | Informações e EPG |
| GUIDE | Guia de programação |
| MENU | Busca (tela principal) / formato da tela (player) |
| Vermelho / Bookmark (player) | Favoritar o que está tocando |
| Segurar OK | Favoritar canal, filme ou série |
| Botões coloridos (tela principal) | Vermelho = TV, Verde = Filmes, Amarelo = Séries, Azul = Kids |
