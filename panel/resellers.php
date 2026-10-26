<?php
require __DIR__ . '/inc/bootstrap.php';
require_admin();

$pdo = db();
$id = (int)($_GET['id'] ?? 0);
$action = $_GET['action'] ?? '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $op = $_POST['op'] ?? '';
    $id = (int)($_POST['id'] ?? 0);
    if ($op === 'save') {
        $name = post_str('name', 60);
        $user = strtolower(post_str('username', 32));
        $pass = (string)($_POST['password'] ?? '');
        $code = strtoupper(preg_replace('/[^A-Za-z0-9]/', '', post_str('code', 12)));
        $data = [
            'name' => $name ?: $user,
            'username' => $user,
            'code' => $code,
            'max_devices' => max(0, min(100000, (int)($_POST['max_devices'] ?? 50))),
            'max_servers' => max(0, min(50, (int)($_POST['max_servers'] ?? 5))),
            'expires_at' => preg_match('/^\d{4}-\d{2}-\d{2}$/', post_str('expires_at', 10)) ? post_str('expires_at', 10) : '',
            'active' => !empty($_POST['active']) ? 1 : 0,
            'whatsapp' => post_str('whatsapp', 40),
            'notes' => post_str('notes', 500),
        ];
        $errors = [];
        if (!preg_match('/^[a-z0-9_.-]{3,32}$/', $user)) $errors[] = 'Usuário: 3 a 32 caracteres (letras, números, _ . -).';
        if (strlen($code) < 4) $errors[] = 'O código da revenda precisa ter pelo menos 4 letras/números.';
        $dupe = $pdo->prepare('SELECT COUNT(*) FROM resellers WHERE (username=? OR code=? COLLATE NOCASE) AND id<>?');
        $dupe->execute([$user, $code, $id]);
        if ((int)$dupe->fetchColumn() > 0) $errors[] = 'Usuário ou código já usado por outra revenda.';
        $adm = $pdo->prepare('SELECT COUNT(*) FROM admins WHERE username=?');
        $adm->execute([$user]);
        if ((int)$adm->fetchColumn() > 0) $errors[] = 'Esse usuário já é do administrador.';
        if (!$id || $pass !== '') {
            if ($m = password_ok($pass)) $errors[] = $m;
        }
        if ($errors) {
            foreach ($errors as $er) flash($er, 'err');
            $_SESSION['reseller_form'] = $_POST;
            redirect('resellers.php?action=' . ($id ? 'edit&id=' . $id : 'new'));
        }
        if ($pass !== '') $data['password_hash'] = password_hash($pass, PASSWORD_DEFAULT);
        if ($id) {
            $set = implode(',', array_map(fn($k) => "$k=:$k", array_keys($data)));
            $data['id'] = $id;
            $pdo->prepare("UPDATE resellers SET $set WHERE id=:id")->execute($data);
            audit('revenda_editada', $user);
        } else {
            $cols = implode(',', array_keys($data));
            $ph = implode(',', array_map(fn($k) => ":$k", array_keys($data)));
            $pdo->prepare("INSERT INTO resellers ($cols) VALUES ($ph)")->execute($data);
            audit('revenda_criada', $user);
        }
        unset($_SESSION['reseller_form']);
        flash('Revenda salva.');
        redirect('resellers.php');
    }
    if ($op === 'toggle' && $id) {
        $pdo->prepare('UPDATE resellers SET active = 1 - active WHERE id=?')->execute([$id]);
        audit('revenda_status', (string)$id);
        redirect('resellers.php');
    }
    if ($op === 'delete' && $id) {
        $pdo->prepare('DELETE FROM servers WHERE reseller_id=?')->execute([$id]);
        $pdo->prepare('UPDATE devices SET reseller_id=0 WHERE reseller_id=?')->execute([$id]);
        $pdo->prepare('DELETE FROM resellers WHERE id=?')->execute([$id]);
        audit('revenda_excluida', (string)$id);
        flash('Revenda excluída (os servidores dela também).');
        redirect('resellers.php');
    }
}

$editing = null;
if ($action === 'new' || ($action === 'edit' && $id)) {
    $editing = $id ? reseller($id) : null;
    $editing = $editing ?: ['id' => 0, 'name' => '', 'username' => '', 'code' => new_reseller_code(), 'max_devices' => 50, 'max_servers' => 5,
        'expires_at' => '', 'active' => 1, 'whatsapp' => '', 'notes' => ''];
    if (!empty($_SESSION['reseller_form'])) {
        $editing = array_merge($editing, array_intersect_key($_SESSION['reseller_form'], $editing));
        unset($_SESSION['reseller_form']);
    }
}

$list = $pdo->query("
    SELECT r.*,
      (SELECT COUNT(*) FROM devices d WHERE d.reseller_id=r.id AND d.blocked=0) AS used,
      (SELECT COUNT(*) FROM devices d WHERE d.reseller_id=r.id AND d.last_seen >= datetime('now','localtime','-1 day')) AS online,
      (SELECT COUNT(*) FROM servers s WHERE s.reseller_id=r.id) AS nservers
    FROM resellers r ORDER BY r.active DESC, r.name COLLATE NOCASE
")->fetchAll();

$pageTitle = 'Revendedores';
$active = 'resellers';
require __DIR__ . '/inc/header.php';
?>
<?php if ($editing): ?>
<form method="post" class="card" autocomplete="off">
  <?= csrf_field() ?><input type="hidden" name="op" value="save"><input type="hidden" name="id" value="<?= (int)$editing['id'] ?>">
  <h2><?= $editing['id'] ? 'Editar revenda' : 'Nova revenda' ?></h2>
  <div class="grid g3">
    <div class="field"><label>Nome da revenda</label><input type="text" name="name" value="<?= e($editing['name']) ?>" required maxlength="60"></div>
    <div class="field"><label>Usuário de acesso</label><input type="text" name="username" value="<?= e($editing['username']) ?>" required maxlength="32" autocomplete="off"></div>
    <div class="field"><label><?= $editing['id'] ? 'Nova senha (vazio = manter)' : 'Senha' ?></label><input type="password" name="password" <?= $editing['id'] ? '' : 'required' ?> autocomplete="new-password"></div>
  </div>
  <div class="grid g4">
    <div class="field"><label>Código da revenda</label><input type="text" name="code" value="<?= e($editing['code']) ?>" maxlength="12" class="mono" style="text-transform:uppercase">
      <p class="hint">Vai no APK (assets/config.json → reseller_code) ou o cliente digita no login.</p></div>
    <div class="field"><label>Limite de dispositivos</label><input type="number" name="max_devices" min="0" value="<?= (int)$editing['max_devices'] ?>">
      <p class="hint">0 = sem novos aparelhos.</p></div>
    <div class="field"><label>Limite de servidores</label><input type="number" name="max_servers" min="0" max="50" value="<?= (int)$editing['max_servers'] ?>"></div>
    <div class="field"><label>Válido até (opcional)</label><input type="date" name="expires_at" value="<?= e($editing['expires_at']) ?>"></div>
  </div>
  <div class="grid g2">
    <div class="field"><label>WhatsApp da revenda</label><input type="text" name="whatsapp" value="<?= e($editing['whatsapp']) ?>" maxlength="40" placeholder="Aparece como suporte no app dos clientes dela"></div>
    <div class="field"><label>Observações</label><input type="text" name="notes" value="<?= e($editing['notes']) ?>" maxlength="500"></div>
  </div>
  <label class="switch" style="margin-bottom:16px"><input type="checkbox" name="active" value="1" <?= $editing['active'] ? 'checked' : '' ?>> Revenda ativa</label>
  <div class="row"><button class="btn">💾 Salvar</button><a class="btn ghost" href="resellers.php">Cancelar</a></div>
</form>
<?php else: ?>

<div class="grid g3" style="margin-bottom:18px">
  <div class="stat"><b><?= count($list) ?></b><span>Revendas</span></div>
  <div class="stat"><b><?= array_sum(array_column($list, 'used')) ?></b><span>Aparelhos em revendas</span></div>
  <div class="stat"><b><?= array_sum(array_column($list, 'online')) ?></b><span>Ativos nas últimas 24h</span></div>
</div>

<div class="card">
  <div class="row" style="justify-content:space-between;margin-bottom:12px"><h2 style="margin:0">Revendedores</h2><a class="btn" href="resellers.php?action=new">+ Nova revenda</a></div>
  <?php if (!$list): ?><div class="empty">Nenhuma revenda ainda. Cada revenda entra no painel com usuário e senha próprios, cadastra as DNS/P2P dela e você define quantos aparelhos ela pode ativar.</div><?php else: ?>
  <div class="tbl"><table class="cards">
    <tr><th>Revenda</th><th>Código</th><th>Aparelhos</th><th>Servidores</th><th>Validade</th><th>Status</th><th></th></tr>
    <?php foreach ($list as $r):
        $pct = $r['max_devices'] > 0 ? min(100, (int)round($r['used'] * 100 / $r['max_devices'])) : 100;
        $exp = reseller_expired($r); ?>
    <tr>
      <td data-label="Revenda"><strong><?= e($r['name']) ?></strong><br><small class="hint">@<?= e($r['username']) ?><?= $r['last_login'] ? ' · último acesso ' . e($r['last_login']) : '' ?></small></td>
      <td data-label="Código"><span class="mono copy badge" data-copy><?= e($r['code']) ?></span></td>
      <td data-label="Aparelhos">
        <div class="meter"><i style="width:<?= $pct ?>%" class="<?= $pct >= 90 ? 'hot' : '' ?>"></i></div>
        <small><?= (int)$r['used'] ?> / <?= (int)$r['max_devices'] ?> · <?= (int)$r['online'] ?> online</small></td>
      <td data-label="Servidores"><?= (int)$r['nservers'] ?> / <?= (int)$r['max_servers'] ?></td>
      <td data-label="Validade"><?= $r['expires_at'] ? e(date('d/m/Y', strtotime($r['expires_at']))) : 'Sem prazo' ?><?= $exp ? ' <span class="badge off">vencida</span>' : '' ?></td>
      <td data-label="Status"><form method="post" style="display:inline"><?= csrf_field() ?><input type="hidden" name="op" value="toggle"><input type="hidden" name="id" value="<?= $r['id'] ?>"><button class="badge <?= $r['active'] ? 'on' : 'off' ?>" style="border:0;cursor:pointer"><?= $r['active'] ? 'Ativa' : 'Inativa' ?></button></form></td>
      <td class="row" style="justify-content:flex-end">
        <a class="btn small ghost" href="devices.php?reseller=<?= $r['id'] ?>">Aparelhos</a>
        <a class="btn small ghost" href="resellers.php?action=edit&id=<?= $r['id'] ?>">Editar</a>
        <form method="post"><?= csrf_field() ?><input type="hidden" name="op" value="delete"><input type="hidden" name="id" value="<?= $r['id'] ?>"><button class="btn small danger" data-confirm="Excluir a revenda e os servidores dela?">Excluir</button></form>
      </td>
    </tr>
    <?php endforeach; ?>
  </table></div>
  <?php endif; ?>
</div>

<div class="card">
  <h2>Como funciona</h2>
  <ol class="steps">
    <li>Crie a revenda com <b>usuário, senha, código e limite de aparelhos</b>.</li>
    <li>A revenda entra neste mesmo painel e cadastra as <b>DNS / P2P / M3U</b> dela.</li>
    <li>O app identifica a revenda pelo <b>código</b>: coloque em <span class="mono">assets/config.json → "reseller_code"</span> (MT Manager) ou ative em <a href="settings.php">Sistema</a> a opção para o cliente digitar o código no login.</li>
    <li>Cada aparelho novo ocupa uma vaga. Ao atingir o limite, novos aparelhos veem “limite da revenda atingido”. A revenda libera vagas removendo aparelhos.</li>
  </ol>
</div>
<?php endif; ?>
<?php require __DIR__ . '/inc/footer.php';
