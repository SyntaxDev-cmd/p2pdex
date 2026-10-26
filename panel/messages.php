<?php
require __DIR__ . '/inc/bootstrap.php';
require_admin();

$pdo = db();
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $op = $_POST['op'] ?? '';
    $id = (int)($_POST['id'] ?? 0);
    if ($op === 'add') {
        $title = mb_substr(trim((string)($_POST['title'] ?? '')), 0, 100);
        if ($title !== '') {
            $pdo->prepare('INSERT INTO messages (title, body, popup, active) VALUES (?, ?, ?, 1)')
                ->execute([$title, mb_substr(trim((string)($_POST['body'] ?? '')), 0, 2000), !empty($_POST['popup']) ? 1 : 0]);
            flash('Aviso publicado.');
        }
    } elseif ($op === 'toggle' && $id) {
        $pdo->prepare('UPDATE messages SET active = 1 - active WHERE id=?')->execute([$id]);
    } elseif ($op === 'delete' && $id) {
        $pdo->prepare('DELETE FROM messages WHERE id=?')->execute([$id]);
        flash('Aviso removido.');
    }
    redirect('messages.php');
}
$list = $pdo->query('SELECT * FROM messages ORDER BY id DESC')->fetchAll();
$pageTitle = 'Avisos';
$active = 'messages';
require __DIR__ . '/inc/header.php';
?>
<div class="grid g2">
<form method="post" class="card">
  <?= csrf_field() ?><input type="hidden" name="op" value="add">
  <h2>Novo aviso</h2>
  <div class="field"><label>Título</label><input type="text" name="title" required maxlength="100"></div>
  <div class="field"><label>Mensagem</label><textarea name="body"></textarea></div>
  <label class="switch" style="margin-bottom:14px"><input type="checkbox" name="popup" value="1"> Mostrar em pop-up ao abrir o app (uma vez por aviso)</label>
  <button class="btn">📣 Publicar</button>
  <p class="hint">Os avisos aparecem no sino 🔔 do app.</p>
</form>
<div class="card">
  <h2>Publicados</h2>
  <?php if (!$list): ?><div class="empty">Nenhum aviso.</div><?php endif; ?>
  <?php foreach ($list as $m): ?>
    <div style="border-bottom:1px solid var(--line);padding:10px 0">
      <div class="row"><strong style="flex:1"><?= e($m['title']) ?></strong><?= $m['popup'] ? '<span class="badge">pop-up</span>' : '' ?>
        <form method="post"><?= csrf_field() ?><input type="hidden" name="op" value="toggle"><input type="hidden" name="id" value="<?= $m['id'] ?>"><button class="badge <?= $m['active'] ? 'on' : 'off' ?>" style="border:0;cursor:pointer"><?= $m['active'] ? 'Ativo' : 'Off' ?></button></form>
        <form method="post"><?= csrf_field() ?><input type="hidden" name="op" value="delete"><input type="hidden" name="id" value="<?= $m['id'] ?>"><button class="btn small danger" data-confirm="Remover aviso?">✕</button></form></div>
      <small class="hint"><?= e($m['created_at']) ?></small>
      <p style="margin:6px 0 0;white-space:pre-wrap"><?= e($m['body']) ?></p>
    </div>
  <?php endforeach; ?>
</div>
</div>
<?php require __DIR__ . '/inc/footer.php';
