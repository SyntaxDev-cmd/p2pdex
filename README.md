# NovaTV — App IPTV/P2P para Android (TV + celular) + Painel PHP

Projeto completo com:

- **App Android** (`android/`): visual no estilo dos apps de TV Box (abas no topo, lista de canais, prévia ao vivo e guia de programação), feito em Kotlin + Media3/ExoPlayer. É um único APK para **TV Box, Smart TV (Android TV) e celular**, do Android 5.0 em diante.
- **Painel administrativo** (`panel/`): PHP + SQLite, sem banco MySQL, pronto para a **Hostinger**. Nele o admin muda logo, fundo, cores, abas, servidores, Kids, banners, avisos e atualizações.

---

## Recursos do app

| Área | O que faz |
|---|---|
| TV ao vivo | Categorias, lista de canais numerada, prévia com vídeo, EPG (agora/a seguir com barra de progresso), favoritos (segure OK) e assistidos recentemente |
| JOGOS | Só os canais de esportes (por palavras-chave definidas no painel) |
| DESTAQUES | Banners do painel em carrossel, “Continuar assistindo”, “Minha lista” e fileiras de lançamentos |
| FILMES / SÉRIES | Grade de cartazes, detalhes com sinopse, nota, elenco e fundo; temporadas e episódios |
| KIDS | Junta canais, filmes e séries infantis; visual colorido; PIN opcional para sair do modo Kids |
| ANIME | Separado automaticamente por palavras-chave |
| Login | Responsivo: TV e tablet (marca + formulário), celular deitado (compacto com rolagem), celular em pé; mostrar/ocultar senha, escolha de servidor, erros claros, teclado não cobre os campos |
| Busca | Canais, filmes e séries ao mesmo tempo, sem diferenciar acento |
| Player | Troca de canal com ↑/↓, lista de canais sobreposta (OK), formato de tela, faixas de áudio e legenda, retomar de onde parou, próximo episódio automático, reconexão automática |
| Favoritos | Aba própria (canais, filmes e séries), coração ♥ nos cartazes e na lista de canais, botão ♥ na prévia, no player e nos detalhes; segurar OK em qualquer item; separados por conta |
| Controle parental | Categorias adultas bloqueadas por PIN (o cliente pode trocar o PIN) |
| Conta | Validade, telas em uso, troca de PIN e logout |

### Conexão (Xtream, P2P e M3U)

- **Xtream Codes (DNS)**: várias DNS por servidor. O login testa todas **ao mesmo tempo** e usa a primeira que autenticar. Se a DNS cair durante o uso, o app passa para a próxima sozinho.
- **P2P**: usa os mesmos campos do seu painel atual:
  - **API Auth**: domínio da API (uma ou mais linhas, com failover).
  - **API Email**: sufixo adicionado ao usuário, por exemplo `@seudominio.site`.
  - **Domínio LIB**: servidor que entrega o vídeo.

  A API precisa ser compatível com `player_api.php`. Se o seu P2P usar outro formato de URL, ajuste o **modelo de URL** em *Avançado* (`{base}/{kind}/{user}/{pass}/{id}.{ext}`).
- **M3U**: lista com `{username}`/`{password}`, guardada em cache por 6 horas.

### Desempenho e compatibilidade

- As listas são lidas **em streaming**, sem carregar o JSON inteiro na memória. Assim catálogos com dezenas de milhares de títulos funcionam em TV Box fraca.
- As imagens ficam em cache de memória e disco, em modo RGB565 nos aparelhos com pouca RAM.
- A interface se ajusta sozinha: na TV a escala é igual em 720p, 1080p ou 4K; no celular ela reorganiza para retrato (prévia em cima, categorias em chips).
- O app aceita o certificado Let's Encrypt em Android antigo (5.x e 6.x), necessário para o painel na Hostinger com HTTPS.
- O app guarda a configuração do painel e só baixa de novo quando algo mudou (ETag). Se o painel ficar fora do ar, o app continua funcionando com a última configuração.

---

## 1) Instalar o painel na Hostinger

1. No **hPanel → Gerenciador de Arquivos**, crie a pasta `public_html/painel`.
2. Envie todo o conteúdo da pasta `panel/` para dentro dela.
3. Em **Avançado → Configuração PHP**, use **PHP 8.1 ou superior**. As extensões `pdo_sqlite` e `curl` já vêm ativas.
4. Acesse `https://seudominio.com/painel/`. A tela de instalação confere os requisitos e cria o administrador.
5. Em **Visão geral**, copie a **PANEL_URL** e a **PANEL_KEY**.

> As pastas `data/` (banco) e `inc/` ficam bloqueadas pelo `.htaccess`, e o banco ainda recebe um nome aleatório como proteção extra. As pastas `data/` e `uploads/` precisam de permissão de escrita (755 costuma bastar na Hostinger).

### Primeiros ajustes no painel

1. **Servidores / DNS / P2P**: cadastre seu servidor e use **⚡ Testar conexão** com um usuário de teste. O teste mostra o tempo de resposta e a validade da conta para cada DNS.
2. **Aparência**: logo, fundo (degradê, imagem ou cor), temas prontos e todas as cores, com prévia ao vivo de uma TV.
3. **Abas e menus**: ligue, desligue ou renomeie as abas e escolha a aba inicial.
4. **Kids e controle parental**: palavras-chave de Kids, Jogos, Anime e Adulto, PIN e modo criança.
5. **Destaques**, **Avisos**, **Dispositivos** (bloquear aparelhos) e **Sistema** (manutenção, atualização do APK, buffer do player, backup).

---

## 2) Gerar o APK (GitHub Actions, sem instalar nada)

1. Envie este projeto para o seu repositório no GitHub.
2. Em **Settings → Secrets and variables → Actions**, crie estes secrets:
   - `PANEL_URL`: por exemplo `https://seudominio.com/painel`
   - `PANEL_KEY`: a chave do painel
   - `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` e `KEY_PASSWORD`: os dados da chave de assinatura, que vão **separados** do projeto. Não coloque a keystore no repositório.
3. Abra **Actions → Gerar APK → Run workflow**.
4. Quando terminar, baixe o APK em **Artifacts**.

O `versionCode` do APK é o número da execução no Actions. Para avisar os clientes de uma versão nova, coloque esse número e o link do APK em **Painel → Sistema e app**. O app baixa e instala sozinho.

### Ou pelo Android Studio

Abra a pasta `android/`, preencha `PANEL_URL` e `PANEL_KEY` em `android/gradle.properties` e use **Build → Build APK(s)**.

---

## Estrutura

```
panel/                 Painel PHP + SQLite
  api/config.php       API que o app consome (tema, abas, servidores, avisos...)
  inc/bootstrap.php    banco, migrações, segurança (CSRF, login com limite de tentativas)
android/               App Kotlin
  app/src/main/java/com/novatv/app/
    data/              Xtream/P2P, M3U, cache, preferências, configuração do painel
    ui/                telas: abertura, login, principal (abas), detalhes, busca, player
.github/workflows/     build automático do APK
```

## Controle remoto

| Tecla | Ação |
|---|---|
| ↑ ↓ no player ao vivo | Canal anterior/próximo |
| OK no player ao vivo | Lista de canais |
| ← → no player ao vivo | Informações e EPG |
| MENU | Busca (na tela principal) / formato de tela (no player) |
| Botões coloridos (tela principal) | Vermelho = TV, Verde = Filmes, Amarelo = Séries, Azul = Kids |
| Vermelho / Bookmark (no player) | Favoritar o que está tocando |
| Segurar OK | Favoritar canal, filme ou série |
