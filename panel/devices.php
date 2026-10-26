<?php
require __DIR__ . '/inc/bootstrap.php';
require_login();

$pdo = db();
$rid = reseller_id();
// admin pode filtrar por revenda (?reseller=ID; -1 = só do admin)
$filterRid = is_admin() ? ((isset($_GET['reseller']) && $_GET['reseller'] !== '') ? (int)$_GET['reseller'] : null) : $rid;

function device_owned(int $id): bool
{
    if (is_admin()) return true;
    $st = db()->prepare('SELECT reseller_id FROM devices WHERE id=?');
    $st->execute([$id]);
    return (int)$st->fetchColumn() === reseller_id() && reseller_id() > 0;
}

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $id = (int)($_POST['id'] ?? 0);
    $op = $_POST['op'] ?? '';
    if ($id && !device_owned($id)) {
        flash('Aparelho não encontrado.', 'err');
        redirect('devices.php');
    }
    if ($op === 'block' && $id) {
        $pdo->prepare('UPDATE devices SET blocked = 1 - blocked WHERE id=?')->execute([$id]);
        audit('aparelho_bloqueio', (string)$id);
    }
    if ($op === 'delete' && $id) {
        $pdo->prepare('DELETE FROM devices WHERE id=?')->execute([$id]);
        audit('aparelho_removido', (string)$id);
        flash('Aparelho removido — a vaga foi liberada.');
    }
    if ($op === 'purge') {
        if (is_admin()) {
            $n = $pdo->exec("DELETE FROM devices WHERE blocked=0 AND last_seen < datetime('now','localtime','-60 days')");
        } else {
            $st = $pdo->prepare("DELETE FROM devices WHERE reseller_id=? AND blocked=0 AND last_seen < datetime('now','localtime','-30 days')");
            $st->execute([$rid]);
            $n = $st->rowCount();
        }
        audit('aparelhos_limpos', (string)$n);
        flash("$n aparelhos inativos removidos.");
    }
    $qs = http_build_query(array_filter(['q' => $_GET['q'] ?? null, 'reseller' => $_GET['reseller'] ?? null], fn($v) => $v !== null));
    redirect('devices.php' . ($qs ? '?' . $qs : ''));
}

$q = trim((string)($_GET['q'] ?? ''));
$page = max(1, (int)($_GET['p'] ?? 1));
$per = 50;
$where = [];
$args = [];
if ($filterRid !== null) {
    $where[] = 'd.reseller_id = ?';
    $args[] = max(0, $filterRid);
}
if ($q !== '') {
    $where[] = '(d.model LIKE ? OR d.username LIKE ? OR d.ip LIKE ? OR d.device_id LIKE ?)';
    array_push($args, "%$q%", "%$q%", "%$q%", "%$q%");
}
$w = $where ? 'WHERE ' . implode(' AND ', $where) : '';
$st = $pdo->prepare("SELECT COUNT(*) FROM devices d $w");
$st->execute($args);
$total = (int)$st->fetchColumn();
$st = $pdo->prepare("SELECT d.*, s.name AS server_name, r.name AS reseller_name FROM devices d
    LEFT JOIN servers s ON s.id=d.server_id LEFT JOIN resellers r ON r.id=d.reseller_id
    $w ORDER BY d.last_seen DESC LIMIT $per OFFSET " . (($page - 1) * $per));
$st->execute($args);
$rows = $st->fetchAll();

$me = is_admin() ? null : reseller($rid);
$resellers = is_admin() ? $pdo->query('SELECT id, name FROM resellers ORDER BY name')->fetchAll() : [];

$pageTitle = is_admin() ? 'Dispositivos' : 'Meus dispositivos';
$active = 'devices';
require __DIR__ . '/inc/header.php';
?>
<?php if ($me):
    $used = reseller_device_count($rid);
    $pct = $me['max_devices'] > 0 ? min(100, (int)round($used * 100 / $me['max_devices'])) : 100; ?>
<div class="card">
  <h2>Vagas de aparelhos</h2>
  <div class="meter big"><i style="width:<?= $pct ?>%" class="<?= $pct >= 90 ? 'hot' : '' ?>"></i></div>
  <p class="hint"><b><?= $used ?></b> de <b><?= (int)$me['max_devices'] ?></b> vagas em uso. Remova aparelhos que não usam mais para liberar vagas. Aparelhos bloqueados não ocupam vaga.</p>
</div>
<?php endif; ?>

<div class="card">
  <div class="row" style="margin-bottom:12px">
    <form class="row" style="flex:1">
      <input type="text" name="q" value="<?= e($q) ?>" placeholder="Buscar por modelo, usuário, IP..." style="max-width:320px">
      <?php if (is_admin()): ?>
        <select name="reseller" style="max-width:220px">
          <option value="">Todos</option>
          <option value="0" <?= $filterRid === 0 ? 'selected' : '' ?>>Só do administrador</option>
          <?php foreach ($resellers as $r): ?><option value="<?= $r['id'] ?>" <?= $filterRid === (int)$r['id'] ? 'selected' : '' ?>><?= e($r['name']) ?></option><?php endforeach; ?>
        </select>
      <?php endif; ?>
      <button class="btn ghost small">Filtrar</button>
    </form>
    <form method="post"><?= csrf_field() ?><input type="hidden" name="op" value="purge"><button class="btn small ghost" data-confirm="Remover aparelhos sem acesso há muito tempo?">Limpar inativos</button></form>
  </div>
  <p class="hint">Total: <?= $total ?>. Bloquear faz o app mostrar “dispositivo bloqueado” na próxima abertura.</p>
  <?php if (!$rows): ?><div class="empty">Nada encontrado.</div><?php else: ?>
  <div class="tbl"><table>
    <tr><th>Aparelho</th><th>Tipo</th><th>Usuário</th><?php if (is_admin()): ?><th>Revenda</th><?php endif; ?><th>Servidor</th><th>App</th><th>IP</th><th>Último acesso</th><th></th></tr>
    <?php foreach ($rows as $d): ?>
    <tr class="<?= $d['blocked'] ? 'muted' : '' ?>">
      <td><?= e($d['model']) ?><br><small class="hint">Android <?= e($d['android']) ?> · <span class="mono"><?= e(substr($d['device_id'], 0, 10)) ?></span></small></td>
      <td><?= $d['platform'] === 'tv' ? '📺 TV' : '📱 Celular' ?></td>
      <td><?= e($d['username']) ?></td>
      <?php if (is_admin()): ?><td><?= $d['reseller_name'] ? '🤝 ' . e($d['reseller_name']) : 'Admin' ?></td><?php endif; ?>
      <td><?= e($d['server_name'] ?? '-') ?></td>
      <td><?= e($d['app_version']) ?></td>
      <td class="mono"><?= e($d['ip']) ?></td>
      <td><?= e($d['last_seen']) ?><?= $d['blocked'] ? ' <span class="badge off">bloqueado</span>' : '' ?></td>
      <td class="row">
        <form method="post"><?= csrf_field() ?><input type="hidden" name="op" value="block"><input type="hidden" name="id" value="<?= $d['id'] ?>"><button class="btn small <?= $d['blocked'] ? '' : 'ghost' ?>"><?= $d['blocked'] ? 'Desbloquear' : 'Bloquear' ?></button></form>
        <form method="post"><?= csrf_field() ?><input type="hidden" name="op" value="delete"><input type="hidden" name="id" value="<?= $d['id'] ?>"><button class="btn small danger" data-confirm="Remover este aparelho e liberar a vaga?">Remover</button></form>
      </td>
    </tr>
    <?php endforeach; ?>
  </table></div>
  <div class="row" style="margin-top:12px">
    <?php $base = '?' . http_build_query(array_filter(['q' => $q, 'reseller' => $_GET['reseller'] ?? null], fn($v) => $v !== null && $v !== '')); ?>
    <?php if ($page > 1): ?><a class="btn small ghost" href="<?= e($base . '&p=' . ($page - 1)) ?>">← Anterior</a><?php endif; ?>
    <?php if ($page * $per < $total): ?><a class="btn small ghost" href="<?= e($base . '&p=' . ($page + 1)) ?>">Próxima →</a><?php endif; ?>
  </div>
  <?php endif; ?>
</div>
<?php require __DIR__ . '/inc/footer.php';
