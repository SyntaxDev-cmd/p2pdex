<?php
require __DIR__ . '/inc/bootstrap.php';
require_login();

$pdo = db();
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $id = (int)($_POST['id'] ?? 0);
    $op = $_POST['op'] ?? '';
    if ($op === 'block' && $id) $pdo->prepare('UPDATE devices SET blocked = 1 - blocked WHERE id=?')->execute([$id]);
    if ($op === 'delete' && $id) $pdo->prepare('DELETE FROM devices WHERE id=?')->execute([$id]);
    if ($op === 'purge') {
        $n = $pdo->exec("DELETE FROM devices WHERE blocked=0 AND last_seen < datetime('now','localtime','-60 days')");
        flash("$n dispositivos inativos removidos.");
    }
    redirect('devices.php' . (isset($_GET['q']) ? '?q=' . urlencode((string)$_GET['q']) : ''));
}

$q = trim((string)($_GET['q'] ?? ''));
$page = max(1, (int)($_GET['p'] ?? 1));
$per = 50;
$where = '';
$args = [];
if ($q !== '') {
    $where = 'WHERE model LIKE ? OR username LIKE ? OR ip LIKE ? OR device_id LIKE ?';
    $args = array_fill(0, 4, "%$q%");
}
$st = $pdo->prepare("SELECT COUNT(*) FROM devices $where");
$st->execute($args);
$total = (int)$st->fetchColumn();
$st = $pdo->prepare("SELECT d.*, s.name AS server_name FROM devices d LEFT JOIN servers s ON s.id=d.server_id $where ORDER BY d.last_seen DESC LIMIT $per OFFSET " . (($page - 1) * $per));
$st->execute($args);
$rows = $st->fetchAll();

$pageTitle = 'Dispositivos';
$active = 'devices';
require __DIR__ . '/inc/header.php';
?>
<div class="card">
  <div class="row" style="margin-bottom:12px">
    <form class="row" style="flex:1"><input type="text" name="q" value="<?= e($q) ?>" placeholder="Buscar por modelo, usuário, IP..." style="max-width:360px"><button class="btn ghost small">Buscar</button></form>
    <form method="post"><?= csrf_field() ?><input type="hidden" name="op" value="purge"><button class="btn small ghost" data-confirm="Remover aparelhos sem acesso há 60 dias?">Limpar inativos</button></form>
  </div>
  <p class="hint">Total: <?= $total ?>. Bloquear um aparelho faz o app mostrar "Dispositivo bloqueado" na próxima abertura.</p>
  <?php if (!$rows): ?><div class="empty">Nada encontrado.</div><?php else: ?>
  <div class="tbl"><table>
    <tr><th>Aparelho</th><th>Tipo</th><th>Usuário</th><th>Servidor</th><th>App</th><th>IP</th><th>Último acesso</th><th></th></tr>
    <?php foreach ($rows as $d): ?>
    <tr>
      <td><?= e($d['model']) ?><br><small class="hint">Android <?= e($d['android']) ?> · <span class="mono"><?= e(substr($d['device_id'], 0, 12)) ?></span></small></td>
      <td><?= $d['platform'] === 'tv' ? '📺 TV' : '📱 Mobile' ?></td>
      <td><?= e($d['username']) ?></td>
      <td><?= e($d['server_name'] ?? '-') ?></td>
      <td><?= e($d['app_version']) ?></td>
      <td class="mono"><?= e($d['ip']) ?></td>
      <td><?= e($d['last_seen']) ?></td>
      <td class="row">
        <form method="post"><?= csrf_field() ?><input type="hidden" name="op" value="block"><input type="hidden" name="id" value="<?= $d['id'] ?>"><button class="btn small <?= $d['blocked'] ? '' : 'ghost' ?>"><?= $d['blocked'] ? 'Desbloquear' : 'Bloquear' ?></button></form>
        <form method="post"><?= csrf_field() ?><input type="hidden" name="op" value="delete"><input type="hidden" name="id" value="<?= $d['id'] ?>"><button class="btn small danger" data-confirm="Remover registro?">✕</button></form>
      </td>
    </tr>
    <?php endforeach; ?>
  </table></div>
  <div class="row" style="margin-top:12px">
    <?php if ($page > 1): ?><a class="btn small ghost" href="?q=<?= urlencode($q) ?>&p=<?= $page - 1 ?>">← Anterior</a><?php endif; ?>
    <?php if ($page * $per < $total): ?><a class="btn small ghost" href="?q=<?= urlencode($q) ?>&p=<?= $page + 1 ?>">Próxima →</a><?php endif; ?>
  </div>
  <?php endif; ?>
</div>
<?php require __DIR__ . '/inc/footer.php';
