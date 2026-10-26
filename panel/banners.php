<?php
require __DIR__ . '/inc/bootstrap.php';
require_login();

$pdo = db();
$actions = ['none' => 'Nenhuma', 'search' => 'Pesquisar no app (texto)', 'tab' => 'Abrir aba (tv, movies, series, kids, anime)', 'url' => 'Abrir link externo'];

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $op = $_POST['op'] ?? '';
    $id = (int)($_POST['id'] ?? 0);
    if ($op === 'save') {
        $errors = [];
        $img = handle_upload('image', $errors);
        $data = [
            'title' => mb_substr(trim((string)($_POST['title'] ?? '')), 0, 80),
            'subtitle' => mb_substr(trim((string)($_POST['subtitle'] ?? '')), 0, 160),
            'action_type' => isset($actions[$_POST['action_type'] ?? '']) ? $_POST['action_type'] : 'none',
            'action_value' => mb_substr(trim((string)($_POST['action_value'] ?? '')), 0, 300),
            'position' => (int)($_POST['position'] ?? 0),
            'active' => !empty($_POST['active']) ? 1 : 0,
        ];
        $imgUrl = trim((string)($_POST['image_url'] ?? ''));
        if ($img) $data['image'] = $img;
        elseif ($imgUrl !== '' && preg_match('#^https?://#i', $imgUrl)) $data['image'] = $imgUrl;
        foreach ($errors as $er) flash($er, 'err');
        if ($id) {
            $set = implode(',', array_map(fn($k) => "$k=:$k", array_keys($data)));
            $data['id'] = $id;
            $pdo->prepare("UPDATE banners SET $set WHERE id=:id")->execute($data);
        } elseif (!empty($data['image'])) {
            $cols = implode(',', array_keys($data));
            $ph = implode(',', array_map(fn($k) => ":$k", array_keys($data)));
            $pdo->prepare("INSERT INTO banners ($cols) VALUES ($ph)")->execute($data);
        } else {
            flash('Envie uma imagem ou informe a URL da imagem.', 'err');
            redirect('banners.php');
        }
        flash('Banner salvo.');
        redirect('banners.php');
    }
    if ($op === 'delete' && $id) {
        $st = $pdo->prepare('SELECT image FROM banners WHERE id=?');
        $st->execute([$id]);
        delete_upload((string)$st->fetchColumn());
        $pdo->prepare('DELETE FROM banners WHERE id=?')->execute([$id]);
        flash('Banner removido.');
        redirect('banners.php');
    }
}

$edit = null;
if (isset($_GET['edit'])) {
    $st = $pdo->prepare('SELECT * FROM banners WHERE id=?');
    $st->execute([(int)$_GET['edit']]);
    $edit = $st->fetch() ?: null;
}
$edit = $edit ?: ['id' => 0, 'title' => '', 'subtitle' => '', 'image' => '', 'action_type' => 'none', 'action_value' => '', 'position' => 0, 'active' => 1];
$list = $pdo->query('SELECT * FROM banners ORDER BY position ASC, id DESC')->fetchAll();

$pageTitle = 'Destaques (banners)';
$active = 'banners';
require __DIR__ . '/inc/header.php';
?>
<div class="grid g2">
<form method="post" enctype="multipart/form-data" class="card">
  <?= csrf_field() ?><input type="hidden" name="op" value="save"><input type="hidden" name="id" value="<?= (int)$edit['id'] ?>">
  <h2><?= $edit['id'] ? 'Editar banner' : 'Novo banner' ?></h2>
  <div class="field"><label>Título</label><input type="text" name="title" value="<?= e($edit['title']) ?>"></div>
  <div class="field"><label>Subtítulo</label><input type="text" name="subtitle" value="<?= e($edit['subtitle']) ?>"></div>
  <div class="field"><label>Imagem (1280×540 recomendado)</label>
    <?php if ($edit['image']): ?><img class="thumb" src="<?= e(asset_url($edit['image'])) ?>" alt=""><?php endif; ?>
    <input type="file" name="image" accept="image/*"><p class="hint">ou URL da imagem:</p><input type="text" name="image_url" placeholder="https://..."></div>
  <div class="grid g2">
    <div class="field"><label>Ação ao clicar</label><select name="action_type"><?php foreach ($actions as $k => $l): ?><option value="<?= $k ?>" <?= $edit['action_type'] === $k ? 'selected' : '' ?>><?= e($l) ?></option><?php endforeach; ?></select></div>
    <div class="field"><label>Valor da ação</label><input type="text" name="action_value" value="<?= e($edit['action_value']) ?>" placeholder="ex.: Vingadores / movies / https://..."></div>
  </div>
  <div class="grid g2">
    <div class="field"><label>Ordem</label><input type="number" name="position" value="<?= (int)$edit['position'] ?>"></div>
    <div class="field"><label>&nbsp;</label><label class="switch"><input type="checkbox" name="active" value="1" <?= $edit['active'] ? 'checked' : '' ?>> Ativo</label></div>
  </div>
  <div class="row"><button class="btn">💾 Salvar</button><?php if ($edit['id']): ?><a class="btn ghost" href="banners.php">Novo</a><?php endif; ?></div>
</form>
<div class="card">
  <h2>Banners</h2>
  <?php if (!$list): ?><div class="empty">Sem banners. A aba Destaques mostra automaticamente os lançamentos do servidor.</div><?php endif; ?>
  <?php foreach ($list as $b): ?>
    <div class="row" style="border-bottom:1px solid var(--line);padding:10px 0">
      <img class="thumb" src="<?= e(asset_url($b['image'])) ?>" alt="" style="width:120px">
      <div style="flex:1;min-width:120px"><strong><?= e($b['title'] ?: '(sem título)') ?></strong><br><small class="hint"><?= e($actions[$b['action_type']] ?? '') ?> <?= e($b['action_value']) ?></small></div>
      <span class="badge <?= $b['active'] ? 'on' : 'off' ?>"><?= $b['active'] ? 'Ativo' : 'Off' ?></span>
      <a class="btn small ghost" href="?edit=<?= $b['id'] ?>">Editar</a>
      <form method="post"><?= csrf_field() ?><input type="hidden" name="op" value="delete"><input type="hidden" name="id" value="<?= $b['id'] ?>"><button class="btn small danger" data-confirm="Remover banner?">✕</button></form>
    </div>
  <?php endforeach; ?>
</div>
</div>
<?php require __DIR__ . '/inc/footer.php';
