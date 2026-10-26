<?php
require __DIR__ . '/inc/bootstrap.php';
require_login();

$pdo = db();
$stats = [
    'servers' => (int)$pdo->query('SELECT COUNT(*) FROM servers WHERE active=1')->fetchColumn(),
    'devices' => (int)$pdo->query('SELECT COUNT(*) FROM devices')->fetchColumn(),
    'online' => (int)$pdo->query("SELECT COUNT(*) FROM devices WHERE last_seen >= datetime('now','localtime','-1 day')")->fetchColumn(),
    'tv' => (int)$pdo->query("SELECT COUNT(*) FROM devices WHERE platform='tv'")->fetchColumn(),
];
$recent = $pdo->query('SELECT * FROM devices ORDER BY last_seen DESC LIMIT 8')->fetchAll();
$apiUrl = base_url() . '/api/config.php';

$pageTitle = 'Visão geral';
$active = 'index';
require __DIR__ . '/inc/header.php';
?>
<?php if (setting('maintenance') === '1'): ?>
  <div class="alert warn">⚠ Modo manutenção ATIVO — o app está mostrando a mensagem de manutenção para todos.</div>
<?php endif; ?>

<div class="grid g4" style="margin-bottom:18px">
  <div class="stat"><b><?= $stats['servers'] ?></b><span>Servidores ativos</span></div>
  <div class="stat"><b><?= $stats['devices'] ?></b><span>Dispositivos registrados</span></div>
  <div class="stat"><b><?= $stats['online'] ?></b><span>Ativos nas últimas 24h</span></div>
  <div class="stat"><b><?= $stats['tv'] ?></b><span>TV Box / Smart TV</span></div>
</div>

<div class="card">
  <h2>🔗 Conexão do aplicativo</h2>
  <p class="hint">O endereço do painel fica no APK em <span class="mono">assets/config.json</span> (<span class="mono">panel_url</span>) — dá para trocar com MT Manager / APK Editor. A chave só é necessária se você ativar "Exigir chave da API" em Sistema.</p>
  <div class="grid g2" style="margin-top:12px">
    <div><label>PANEL_URL</label><div class="result copy" data-copy><?= e(base_url()) ?></div></div>
    <div><label>PANEL_KEY</label><div class="result copy" data-copy><?= e(setting('api_key')) ?></div></div>
  </div>
  <p class="hint" style="margin-top:10px">Teste a API: <a class="mono" target="_blank" href="<?= e($apiUrl . '?key=' . setting('api_key')) ?>"><?= e($apiUrl) ?>?key=…</a></p>
</div>

<div class="card">
  <h2>Últimos dispositivos</h2>
  <?php if (!$recent): ?><div class="empty">Nenhum dispositivo conectou ainda.</div><?php else: ?>
  <div class="tbl"><table>
    <tr><th>Modelo</th><th>Tipo</th><th>Usuário</th><th>Versão</th><th>Último acesso</th></tr>
    <?php foreach ($recent as $d): ?>
    <tr><td><?= e($d['model']) ?></td><td><span class="badge"><?= $d['platform'] === 'tv' ? '📺 TV' : '📱 Mobile' ?></span></td>
      <td><?= e($d['username']) ?></td><td><?= e($d['app_version']) ?></td><td><?= e($d['last_seen']) ?></td></tr>
    <?php endforeach; ?>
  </table></div>
  <?php endif; ?>
</div>
<?php require __DIR__ . '/inc/footer.php';
