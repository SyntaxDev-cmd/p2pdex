<?php
require __DIR__ . '/inc/bootstrap.php';
require_login();

$pdo = db();
$rid = reseller_id();
$scope = is_admin() ? '' : ' AND reseller_id=' . $rid;
$q = fn(string $sql) => (int)$pdo->query($sql)->fetchColumn();
$stats = [
    'servers' => $q("SELECT COUNT(*) FROM servers WHERE active=1$scope"),
    'devices' => $q("SELECT COUNT(*) FROM devices WHERE blocked=0$scope"),
    'online' => $q("SELECT COUNT(*) FROM devices WHERE last_seen >= datetime('now','localtime','-1 day')$scope"),
    'tv' => $q("SELECT COUNT(*) FROM devices WHERE platform='tv'$scope"),
];
$st = $pdo->prepare('SELECT d.*, r.name AS reseller_name FROM devices d LEFT JOIN resellers r ON r.id=d.reseller_id ' . (is_admin() ? '' : 'WHERE d.reseller_id=?') . ' ORDER BY d.last_seen DESC LIMIT 8');
$st->execute(is_admin() ? [] : [$rid]);
$recent = $st->fetchAll();
$me = is_admin() ? null : reseller($rid);

$pageTitle = 'Visão geral';
$active = 'index';
require __DIR__ . '/inc/header.php';
?>
<?php if (is_admin() && setting('maintenance') === '1'): ?>
  <div class="alert warn">⚠ Modo manutenção ATIVO — o app está mostrando a mensagem de manutenção para todos.</div>
<?php endif; ?>

<div class="grid g4" style="margin-bottom:18px">
  <div class="stat"><b><?= $stats['servers'] ?></b><span>Servidores ativos</span></div>
  <div class="stat"><b><?= $stats['devices'] ?><?= $me ? '<small> / ' . (int)$me['max_devices'] . '</small>' : '' ?></b><span><?= $me ? 'Vagas de aparelhos em uso' : 'Aparelhos ativos' ?></span></div>
  <div class="stat"><b><?= $stats['online'] ?></b><span>Ativos nas últimas 24h</span></div>
  <div class="stat"><b><?= $stats['tv'] ?></b><span>TV Box / Smart TV</span></div>
</div>

<?php if ($me): ?>
<div class="card">
  <h2>🤝 Sua revenda</h2>
  <div class="grid g3">
    <div><label>Código da revenda</label><div class="result copy mono big-code" data-copy><?= e($me['code']) ?></div>
      <p class="hint">Seu cliente usa esse código no app (ou ele já vem no seu APK).</p></div>
    <div><label>Validade</label><div class="result"><?= $me['expires_at'] ? e(date('d/m/Y', strtotime($me['expires_at']))) : 'Sem prazo' ?></div></div>
    <div><label>Servidores</label><div class="result"><?= $stats['servers'] ?> ativo(s) · limite <?= (int)$me['max_servers'] ?></div></div>
  </div>
  <p class="hint" style="margin-top:12px">Para um APK com a sua marca de revenda: abra o APK no MT Manager, edite <span class="mono">assets/config.json</span> e coloque <span class="mono">"reseller_code": "<?= e($me['code']) ?>"</span>.</p>
</div>
<?php else: ?>
<div class="card">
  <h2>🔗 Conexão do aplicativo</h2>
  <p class="hint">O app procura o painel no endereço gravado em <span class="mono">assets/config.json</span> (<span class="mono">panel_url</span>). Troque com MT Manager / APK Editor sem recompilar.</p>
  <div class="grid g2" style="margin-top:12px">
    <div><label>panel_url</label><div class="result copy mono" data-copy><?= e(base_url()) ?></div></div>
    <div><label>panel_key (só se “Exigir chave” estiver ligado)</label><div class="result copy mono" data-copy><?= e(setting('api_key')) ?></div></div>
  </div>
  <p class="hint" style="margin-top:10px">Teste da API: <a class="mono" target="_blank" rel="noopener" href="<?= e(base_url() . '/api/config.php') ?>">/api/config.php</a> · Jogos: <a class="mono" target="_blank" rel="noopener" href="<?= e(base_url() . '/api/games.php') ?>">/api/games.php</a></p>
</div>
<?php endif; ?>

<div class="card">
  <h2>Últimos aparelhos</h2>
  <?php if (!$recent): ?><div class="empty">Nenhum aparelho conectou ainda.</div><?php else: ?>
  <div class="tbl"><table>
    <tr><th>Modelo</th><th>Tipo</th><th>Usuário</th><?php if (is_admin()): ?><th>Revenda</th><?php endif; ?><th>Versão</th><th>Último acesso</th></tr>
    <?php foreach ($recent as $d): ?>
    <tr><td><?= e($d['model']) ?></td><td><?= $d['platform'] === 'tv' ? '📺 TV' : '📱 Celular' ?></td>
      <td><?= e($d['username']) ?></td><?php if (is_admin()): ?><td><?= $d['reseller_name'] ? e($d['reseller_name']) : 'Admin' ?></td><?php endif; ?>
      <td><?= e($d['app_version']) ?></td><td><?= e($d['last_seen']) ?></td></tr>
    <?php endforeach; ?>
  </table></div>
  <?php endif; ?>
</div>
<?php require __DIR__ . '/inc/footer.php';
