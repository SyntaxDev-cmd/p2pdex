<?php
require __DIR__ . '/inc/bootstrap.php';

if (admin_count() > 0) redirect('login.php');

$errors = [];
$checks = [
    'PHP 8.0+' => version_compare(PHP_VERSION, '8.0', '>='),
    'Extensão pdo_sqlite' => extension_loaded('pdo_sqlite'),
    'Pasta data/ gravável' => is_writable(DATA_DIR),
    'Pasta uploads/ gravável' => is_dir(UPLOAD_DIR) ? is_writable(UPLOAD_DIR) : @mkdir(UPLOAD_DIR, 0775, true),
    'Extensão cURL (testes de DNS)' => function_exists('curl_init'),
];

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    csrf_check();
    $u = trim((string)($_POST['username'] ?? ''));
    $p = (string)($_POST['password'] ?? '');
    if (!preg_match('/^[a-zA-Z0-9_.-]{3,32}$/', $u)) $errors[] = 'Usuário deve ter 3 a 32 caracteres (letras, números, _ . -).';
    if (strlen($p) < 8) $errors[] = 'A senha precisa de pelo menos 8 caracteres.';
    if ($p !== ($_POST['password2'] ?? '')) $errors[] = 'As senhas não conferem.';
    if (!$errors) {
        db()->prepare('INSERT INTO admins (username, password_hash) VALUES (?, ?)')->execute([$u, password_hash($p, PASSWORD_DEFAULT)]);
        $_SESSION['admin_id'] = (int)db()->lastInsertId();
        $_SESSION['admin_user'] = $u;
        $_SESSION['login_at'] = time();
        flash('Painel instalado! Agora configure a aparência e cadastre seus servidores.');
        redirect('index.php');
    }
}
?><!doctype html>
<html lang="pt-br"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Instalação do painel</title><link rel="stylesheet" href="assets/panel.css?v=<?= PANEL_VERSION ?>"></head>
<body><div class="auth"><form class="card" method="post" style="max-width:480px">
  <h1>Instalação</h1><p class="hint" style="margin-bottom:16px">Crie o administrador do painel.</p>
  <table style="margin-bottom:16px">
  <?php foreach ($checks as $label => $ok): ?>
    <tr><td><?= e($label) ?></td><td><span class="badge <?= $ok ? 'on' : 'off' ?>"><?= $ok ? 'OK' : 'Falhou' ?></span></td></tr>
  <?php endforeach; ?>
  </table>
  <?php foreach ($errors as $er): ?><div class="alert err"><?= e($er) ?></div><?php endforeach; ?>
  <?= csrf_field() ?>
  <div class="field"><label>Usuário admin</label><input type="text" name="username" required value="<?= e($_POST['username'] ?? 'admin') ?>"></div>
  <div class="field"><label>Senha (mín. 8)</label><input type="password" name="password" required></div>
  <div class="field"><label>Repita a senha</label><input type="password" name="password2" required></div>
  <button class="btn" style="width:100%;justify-content:center">Criar e entrar</button>
</form></div></body></html>
