<?php
require __DIR__ . '/inc/bootstrap.php';
require __DIR__ . '/inc/games.php';
require_admin();

$preview = null;
$tmdbTest = null;
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $op = $_POST['op'] ?? '';
    if ($op === 'games') {
        $url = post_str('games_api_url', 500);
        $map = trim((string)($_POST['games_map'] ?? ''));
        if ($map !== '' && !is_array(json_decode($map, true))) {
            flash('O mapeamento precisa ser um JSON válido, ex.: {"home":"time1","away":"time2"}', 'err');
            redirect('integrations.php');
        }
        if ($url !== '' && !preg_match('#^https?://#i', $url)) {
            flash('A URL da API precisa começar com http:// ou https://', 'err');
            redirect('integrations.php');
        }
        save_settings([
            'games_enabled' => !empty($_POST['games_enabled']) ? '1' : '0',
            'games_api_url' => $url,
            'games_cache_minutes' => (string)max(1, min(120, (int)($_POST['games_cache_minutes'] ?? 10))),
            'games_map' => $map,
        ]);
        @unlink(games_cache_file());
        audit('jogos_config', $url);
        flash('Configuração de jogos salva.');
        if (!empty($_POST['test'])) {
            $_SESSION['games_test'] = 1;
        }
        redirect('integrations.php#jogos');
    }
    if ($op === 'tmdb') {
        save_settings([
            'tmdb_enabled' => !empty($_POST['tmdb_enabled']) ? '1' : '0',
            'tmdb_api_key' => preg_replace('/[^a-f0-9]/i', '', post_str('tmdb_api_key', 64)),
            'tmdb_token' => preg_replace('/[^A-Za-z0-9._\-]/', '', post_str('tmdb_token', 600)),
            'tmdb_language' => preg_match('/^[a-z]{2}(-[A-Z]{2})?$/', post_str('tmdb_language', 5)) ? post_str('tmdb_language', 5) : 'pt-BR',
        ]);
        audit('tmdb_config');
        flash('TMDB salvo.');
        if (!empty($_POST['test'])) $_SESSION['tmdb_test'] = 1;
        redirect('integrations.php#tmdb');
    }
}
if (!empty($_SESSION['games_test'])) {
    unset($_SESSION['games_test']);
    $preview = games_get(true);
}
if (!empty($_SESSION['tmdb_test'])) {
    unset($_SESSION['tmdb_test']);
    $q = setting('tmdb_api_key') !== '' ? '?api_key=' . urlencode(setting('tmdb_api_key')) : '';
    $ch = curl_init('https://api.themoviedb.org/3/movie/550' . $q . ($q ? '&' : '?') . 'language=' . urlencode(setting('tmdb_language')));
    $headers = ['Accept: application/json'];
    if (setting('tmdb_token') !== '') $headers[] = 'Authorization: Bearer ' . setting('tmdb_token');
    curl_setopt_array($ch, [CURLOPT_RETURNTRANSFER => true, CURLOPT_TIMEOUT => 12, CURLOPT_HTTPHEADER => $headers]);
    $body = curl_exec($ch);
    $code = (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    $j = json_decode((string)$body, true);
    $tmdbTest = $code === 200 && !empty($j['title'])
        ? ['ok' => true, 'msg' => 'Conectado! Teste: "' . $j['title'] . '" (' . substr((string)($j['release_date'] ?? ''), 0, 4) . ')']
        : ['ok' => false, 'msg' => 'Falhou (HTTP ' . $code . '): ' . ($j['status_message'] ?? 'verifique a chave/token')];
}
$s = settings();
$pageTitle = 'Jogos do dia e TMDB';
$active = 'integrations';
require __DIR__ . '/inc/header.php';
?>
<form method="post" class="card" id="jogos">
  <?= csrf_field() ?><input type="hidden" name="op" value="games">
  <h2>⚽ Jogos do dia</h2>
  <p class="hint" style="margin-bottom:14px">O app mostra os jogos de hoje na aba JOGOS. Ao tocar no jogo, o cliente vai direto para o canal que está transmitindo.</p>
  <label class="switch" style="margin-bottom:14px"><input type="checkbox" name="games_enabled" value="1" <?= $s['games_enabled'] === '1' ? 'checked' : '' ?>> Mostrar jogos do dia no app</label>
  <div class="grid g3">
    <div class="field" style="grid-column:span 2"><label>URL da API de jogos (JSON)</label><input type="url" name="games_api_url" value="<?= e($s['games_api_url']) ?>" placeholder="https://.../jogos.json"></div>
    <div class="field"><label>Atualizar a cada (minutos)</label><input type="number" name="games_cache_minutes" min="1" max="120" value="<?= e($s['games_cache_minutes']) ?>"></div>
  </div>
  <details class="field">
    <summary style="cursor:pointer;color:var(--muted)">Avançado: mapear campos manualmente</summary>
    <p class="hint">O painel detecta sozinho os campos mais comuns (time1/mandante, time2/visitante, hora, campeonato, canais...). Se a sua API usar outros nomes, informe aqui. Use ponto para subcampos.</p>
    <textarea name="games_map" class="mono" placeholder='{"list":"data.jogos","home":"time_casa","away":"time_fora","time":"horario","league":"campeonato.nome","channels":"transmissao","home_logo":"escudo_casa","away_logo":"escudo_fora"}'><?= e($s['games_map']) ?></textarea>
  </details>
  <div class="row"><button class="btn">💾 Salvar</button><button class="btn ghost" name="test" value="1">⚡ Salvar e testar agora</button></div>
</form>

<?php if ($preview !== null): ?>
<div class="card">
  <h2>Resultado do teste</h2>
  <?php if (!$preview['ok']): ?>
    <div class="alert err"><?= e($preview['error'] ?: 'Não foi possível ler a API.') ?></div>
  <?php elseif (!$preview['games']): ?>
    <div class="alert warn">A API respondeu, mas nenhum jogo foi reconhecido. Use o mapeamento manual acima.</div>
  <?php else: ?>
    <div class="alert ok"><?= count($preview['games']) ?> jogos reconhecidos. Veja como o app vai mostrar:</div>
    <div class="games-preview">
      <?php foreach (array_slice($preview['games'], 0, 12) as $g): ?>
      <div class="game">
        <div class="g-top"><span class="g-time"><?= e($g['time']) ?></span><?php if ($g['status'] === 'live'): ?><span class="badge live">AO VIVO</span><?php endif; ?><small><?= e($g['league']) ?></small></div>
        <div class="g-teams">
          <span><?php if ($g['home_logo']): ?><img src="<?= e($g['home_logo']) ?>" alt=""><?php endif; ?><?= e($g['home']) ?></span>
          <b>x</b>
          <span><?php if ($g['away_logo']): ?><img src="<?= e($g['away_logo']) ?>" alt=""><?php endif; ?><?= e($g['away']) ?></span>
        </div>
        <div class="g-ch"><?php foreach ($g['channels'] as $c): ?><span class="chip">📺 <?= e($c) ?></span><?php endforeach; ?><?= $g['channels'] ? '' : '<small class="hint">sem canal informado</small>' ?></div>
      </div>
      <?php endforeach; ?>
    </div>
  <?php endif; ?>
</div>
<?php endif; ?>

<form method="post" class="card" id="tmdb">
  <?= csrf_field() ?><input type="hidden" name="op" value="tmdb">
  <h2>🎬 TMDB (capas, sinopses, elenco e trailers)</h2>
  <p class="hint" style="margin-bottom:14px">Com o TMDB o app completa filmes e séries com sinopse em português, fundo em alta resolução, elenco com fotos, trailer e classificação indicativa, e monta a fileira “Em alta”. Crie a chave grátis em themoviedb.org → Configurações → API.</p>
  <?php if ($tmdbTest): ?><div class="alert <?= $tmdbTest['ok'] ? 'ok' : 'err' ?>"><?= e($tmdbTest['msg']) ?></div><?php endif; ?>
  <label class="switch" style="margin-bottom:14px"><input type="checkbox" name="tmdb_enabled" value="1" <?= $s['tmdb_enabled'] === '1' ? 'checked' : '' ?>> Usar TMDB no app</label>
  <div class="grid g3">
    <div class="field"><label>Chave da API (v3)</label><input type="text" name="tmdb_api_key" value="<?= e($s['tmdb_api_key']) ?>" class="mono" autocomplete="off"></div>
    <div class="field"><label>Token de leitura (v4, opcional)</label><input type="password" name="tmdb_token" value="<?= e($s['tmdb_token']) ?>" class="mono" autocomplete="off"></div>
    <div class="field"><label>Idioma</label><input type="text" name="tmdb_language" value="<?= e($s['tmdb_language']) ?>" maxlength="5"></div>
  </div>
  <div class="row"><button class="btn">💾 Salvar</button><button class="btn ghost" name="test" value="1">⚡ Salvar e testar</button></div>
  <p class="hint" style="margin-top:12px">Este produto usa a API do TMDB, mas não é endossado nem certificado pelo TMDB.</p>
</form>
<?php require __DIR__ . '/inc/footer.php';
