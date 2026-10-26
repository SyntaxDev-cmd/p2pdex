<?php
require __DIR__ . '/inc/bootstrap.php';
require_admin();

$pdo = db();

function hex_color(string $v, string $def): string
{
    $v = trim($v);
    return preg_match('/^#[0-9a-fA-F]{6}$/', $v) ? strtoupper($v) : $def;
}

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $op = $_POST['op'] ?? '';
    $id = (int)($_POST['id'] ?? 0);
    if ($op === 'section') {
        save_settings([
            'streamings_enabled' => !empty($_POST['streamings_enabled']) ? '1' : '0',
            'streamings_title' => mb_substr(trim((string)($_POST['streamings_title'] ?? '')), 0, 40) ?: 'Streamings',
        ]);
        flash('Seção salva.');
        redirect('streamings.php');
    }
    if ($op === 'save') {
        $errors = [];
        $img = handle_upload('logo', $errors);
        $data = [
            'name' => mb_substr(trim((string)($_POST['name'] ?? '')), 0, 40),
            'keywords' => mb_substr(trim((string)($_POST['keywords'] ?? '')), 0, 300),
            'color' => hex_color((string)($_POST['color'] ?? ''), '#FFFFFF'),
            'text_color' => hex_color((string)($_POST['text_color'] ?? ''), '#000000'),
            'position' => (int)($_POST['position'] ?? 0),
            'active' => !empty($_POST['active']) ? 1 : 0,
        ];
        if ($data['name'] === '') {
            flash('Informe o nome do streaming.', 'err');
            redirect('streamings.php' . ($id ? '?edit=' . $id : ''));
        }
        if ($data['keywords'] === '') $data['keywords'] = mb_strtolower($data['name']);
        $logoUrl = trim((string)($_POST['logo_url'] ?? ''));
        $old = '';
        if ($id) {
            $st = $pdo->prepare('SELECT logo FROM streamings WHERE id=?');
            $st->execute([$id]);
            $old = (string)$st->fetchColumn();
        }
        if ($img) $data['logo'] = $img;
        elseif ($logoUrl !== '' && preg_match('#^https?://#i', $logoUrl)) $data['logo'] = $logoUrl;
        elseif (!empty($_POST['remove_logo'])) $data['logo'] = '';
        if (isset($data['logo']) && $old !== '' && $old !== $data['logo']) delete_upload($old);
        foreach ($errors as $er) flash($er, 'err');
        if ($id) {
            $set = implode(',', array_map(fn($k) => "$k=:$k", array_keys($data)));
            $data['id'] = $id;
            $pdo->prepare("UPDATE streamings SET $set WHERE id=:id")->execute($data);
        } else {
            if ($data['position'] === 0) $data['position'] = (int)$pdo->query('SELECT COALESCE(MAX(position),0)+1 FROM streamings')->fetchColumn();
            $cols = implode(',', array_keys($data));
            $ph = implode(',', array_map(fn($k) => ":$k", array_keys($data)));
            $pdo->prepare("INSERT INTO streamings ($cols) VALUES ($ph)")->execute($data);
        }
        audit('streaming_salvo', $data['name']);
        flash('Streaming salvo.');
        redirect('streamings.php');
    }
    if ($op === 'delete' && $id) {
        $st = $pdo->prepare('SELECT logo FROM streamings WHERE id=?');
        $st->execute([$id]);
        delete_upload((string)$st->fetchColumn());
        $pdo->prepare('DELETE FROM streamings WHERE id=?')->execute([$id]);
        flash('Streaming removido.');
        redirect('streamings.php');
    }
    if ($op === 'toggle' && $id) {
        $pdo->prepare('UPDATE streamings SET active = 1 - active WHERE id=?')->execute([$id]);
        redirect('streamings.php');
    }
    if (($op === 'up' || $op === 'down') && $id) {
        // reordena trocando de lugar com o vizinho
        $list = $pdo->query('SELECT id FROM streamings ORDER BY position ASC, id ASC')->fetchAll(PDO::FETCH_COLUMN);
        $i = array_search($id, array_map('intval', $list), true);
        $j = $op === 'up' ? $i - 1 : $i + 1;
        if ($i !== false && isset($list[$j])) {
            [$list[$i], $list[$j]] = [$list[$j], $list[$i]];
            $st = $pdo->prepare('UPDATE streamings SET position=? WHERE id=?');
            foreach ($list as $pos => $sid) $st->execute([$pos + 1, (int)$sid]);
        }
        redirect('streamings.php');
    }
    if ($op === 'restore') {
        $have = array_map('mb_strtolower', $pdo->query('SELECT name FROM streamings')->fetchAll(PDO::FETCH_COLUMN));
        $st = $pdo->prepare('INSERT INTO streamings (name, keywords, color, text_color, position) VALUES (?, ?, ?, ?, ?)');
        $n = 0;
        $pos = (int)$pdo->query('SELECT COALESCE(MAX(position),0) FROM streamings')->fetchColumn();
        foreach (default_streamings() as $d) {
            if (in_array(mb_strtolower($d[0]), $have, true)) continue;
            $st->execute([$d[0], $d[1], $d[2], $d[3], ++$pos]);
            $n++;
        }
        flash($n ? "$n streamings padrão adicionados." : 'Todos os streamings padrão já estão na lista.');
        redirect('streamings.php');
    }
}

$edit = null;
if (isset($_GET['edit'])) {
    $st = $pdo->prepare('SELECT * FROM streamings WHERE id=?');
    $st->execute([(int)$_GET['edit']]);
    $edit = $st->fetch() ?: null;
}
$edit = $edit ?: ['id' => 0, 'name' => '', 'logo' => '', 'keywords' => '', 'color' => '#FFFFFF', 'text_color' => '#000000', 'position' => 0, 'active' => 1];
$list = $pdo->query('SELECT * FROM streamings ORDER BY position ASC, id ASC')->fetchAll();
$s = settings();

$pageTitle = 'Streamings (logos da tela inicial)';
$active = 'streamings';
require __DIR__ . '/inc/header.php';
?>
<style>
  .st-circle{width:64px;height:64px;border-radius:50%;display:flex;align-items:center;justify-content:center;overflow:hidden;flex:none;
    font-weight:800;font-size:11px;text-align:center;line-height:1.1;box-shadow:0 2px 10px rgba(0,0,0,.35);border:1px solid rgba(255,255,255,.18)}
  .st-circle img{width:100%;height:100%;object-fit:contain}
  .st-preview{display:flex;gap:14px;flex-wrap:wrap;padding:14px;border-radius:14px;background:linear-gradient(135deg,#0b1020,#151b33)}
  .st-row{display:flex;align-items:center;gap:12px;border-bottom:1px solid var(--line);padding:10px 0;flex-wrap:wrap}
  .st-row .grow{flex:1;min-width:140px}
  .st-row form{display:inline}
  .st-actions{display:flex;gap:6px;align-items:center;flex-wrap:nowrap;margin-left:auto}
  .st-circle span{padding:0 4px;word-break:keep-all;overflow-wrap:normal}
</style>

<div class="card">
  <h2>Como aparece no app</h2>
  <p class="hint">Fileira de círculos logo abaixo do destaque da tela inicial (e do início de Filmes e Séries). O app mostra só os streamings
    que têm categorias na lista do cliente: ele procura as <b>palavras-chave</b> no nome das categorias (ex.: "NETFLIX", "Filmes | Globoplay").</p>
  <div class="st-preview">
    <?php foreach ($list as $it): if (!$it['active']) continue; ?>
      <div class="st-circle" style="background:<?= e($it['color']) ?>;color:<?= e($it['text_color']) ?>" title="<?= e($it['name']) ?>">
        <?php if ($it['logo']): ?><img src="<?= e(asset_url($it['logo'])) ?>" alt="<?= e($it['name']) ?>"><?php else: ?><span style="font-size:<?= mb_strlen($it['name']) > 7 ? 9 : 11 ?>px"><?= e($it['name']) ?></span><?php endif; ?>
      </div>
    <?php endforeach; ?>
    <?php if (!$list): ?><div class="empty">Nenhum streaming cadastrado.</div><?php endif; ?>
  </div>
  <form method="post" class="row" style="margin-top:14px;gap:12px;align-items:flex-end;flex-wrap:wrap">
    <?= csrf_field() ?><input type="hidden" name="op" value="section">
    <div class="field" style="margin:0"><label>Título da fileira</label><input type="text" name="streamings_title" value="<?= e($s['streamings_title'] ?? 'Streamings') ?>"></div>
    <label class="switch"><input type="checkbox" name="streamings_enabled" value="1" <?= ($s['streamings_enabled'] ?? '1') === '1' ? 'checked' : '' ?>> Mostrar no app</label>
    <button class="btn">💾 Salvar</button>
  </form>
</div>

<div class="grid g2">
<form method="post" enctype="multipart/form-data" class="card">
  <?= csrf_field() ?><input type="hidden" name="op" value="save"><input type="hidden" name="id" value="<?= (int)$edit['id'] ?>">
  <h2><?= $edit['id'] ? 'Editar streaming' : 'Novo streaming' ?></h2>
  <div class="field"><label>Nome</label><input type="text" name="name" value="<?= e($edit['name']) ?>" placeholder="Ex.: Netflix" required></div>
  <div class="field"><label>Logo (PNG quadrado, 256×256, fundo transparente ou da cor do círculo)</label>
    <?php if ($edit['logo']): ?>
      <div class="st-circle" style="background:<?= e($edit['color']) ?>;margin-bottom:8px"><img src="<?= e(asset_url($edit['logo'])) ?>" alt=""></div>
      <label class="switch" style="margin-bottom:8px"><input type="checkbox" name="remove_logo" value="1"> Remover logo (usar o nome escrito)</label>
    <?php endif; ?>
    <input type="file" name="logo" accept="image/*"><p class="hint">ou URL da imagem:</p><input type="text" name="logo_url" placeholder="https://.../netflix.png"></div>
  <div class="field"><label>Palavras-chave das categorias (separadas por vírgula)</label>
    <input type="text" name="keywords" value="<?= e($edit['keywords']) ?>" placeholder="netflix">
    <p class="hint">O círculo abre as categorias de filmes/séries cujo nome contém uma dessas palavras.</p></div>
  <div class="grid g3">
    <div class="field"><label>Cor do círculo</label><input type="color" name="color" value="<?= e($edit['color']) ?>"></div>
    <div class="field"><label>Cor do nome (sem logo)</label><input type="color" name="text_color" value="<?= e($edit['text_color']) ?>"></div>
    <div class="field"><label>Ordem</label><input type="number" name="position" value="<?= (int)$edit['position'] ?>"></div>
  </div>
  <label class="switch"><input type="checkbox" name="active" value="1" <?= $edit['active'] ? 'checked' : '' ?>> Ativo</label>
  <div class="row" style="margin-top:12px"><button class="btn">💾 Salvar</button><?php if ($edit['id']): ?><a class="btn ghost" href="streamings.php">Novo</a><?php endif; ?></div>
</form>

<div class="card">
  <h2>Streamings (<?= count($list) ?>)</h2>
  <?php foreach ($list as $i => $it): ?>
    <div class="st-row">
      <div class="st-circle" style="width:48px;height:48px;background:<?= e($it['color']) ?>;color:<?= e($it['text_color']) ?>;font-size:9px">
        <?php if ($it['logo']): ?><img src="<?= e(asset_url($it['logo'])) ?>" alt=""><?php else: ?><span style="font-size:<?= mb_strlen($it['name']) > 7 ? 7 : 9 ?>px"><?= e($it['name']) ?></span><?php endif; ?>
      </div>
      <div class="grow"><strong><?= e($it['name']) ?></strong><br><small class="hint"><?= e($it['keywords']) ?><?= $it['logo'] ? '' : ' · sem logo' ?></small></div>
      <div class="st-actions">
      <form method="post"><?= csrf_field() ?><input type="hidden" name="op" value="up"><input type="hidden" name="id" value="<?= $it['id'] ?>"><button class="btn small ghost" <?= $i === 0 ? 'disabled' : '' ?> title="Subir">↑</button></form>
      <form method="post"><?= csrf_field() ?><input type="hidden" name="op" value="down"><input type="hidden" name="id" value="<?= $it['id'] ?>"><button class="btn small ghost" <?= $i === count($list) - 1 ? 'disabled' : '' ?> title="Descer">↓</button></form>
      <form method="post"><?= csrf_field() ?><input type="hidden" name="op" value="toggle"><input type="hidden" name="id" value="<?= $it['id'] ?>"><button class="badge <?= $it['active'] ? 'on' : 'off' ?>" style="border:0;cursor:pointer"><?= $it['active'] ? 'Ativo' : 'Off' ?></button></form>
      <a class="btn small ghost" href="?edit=<?= $it['id'] ?>">Editar</a>
      <form method="post"><?= csrf_field() ?><input type="hidden" name="op" value="delete"><input type="hidden" name="id" value="<?= $it['id'] ?>"><button class="btn small danger" data-confirm="Remover <?= e($it['name']) ?>?">✕</button></form>
      </div>
    </div>
  <?php endforeach; ?>
  <form method="post" style="margin-top:12px"><?= csrf_field() ?><input type="hidden" name="op" value="restore"><button class="btn ghost small">↺ Adicionar streamings padrão que faltam</button></form>
</div>
</div>
<?php require __DIR__ . '/inc/footer.php';
